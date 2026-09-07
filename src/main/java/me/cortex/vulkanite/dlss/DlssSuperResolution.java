package me.cortex.vulkanite.dlss;

import me.cortex.vulkanite.lib.base.VContext;

import org.lwjgl.vulkan.VK10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.MemorySegment;

/**
 * DLSS Super Resolution: spatial + temporal upscale from a lower render resolution to the display
 * resolution, driven by color, depth and motion vectors. This is the "plain" DLSS — the counterpart
 * of {@link DlssRayReconstruction}, which additionally denoises and needs path-traced guide buffers.
 *
 * <p>Lifecycle mirrors Caustica's feature wrappers (the mod this was ported from): {@link #ensureFeature}
 * creates or recycles the NGX feature when the resolutions or quality mode change, {@link #evaluate}
 * records one upscale per frame into an open command buffer, and {@link #destroy} releases it. Every
 * failure path latches {@code failed} and reports {@code false} so the caller falls back to its
 * non-DLSS path instead of losing the frame.
 *
 * <p>Ported from Caustica (LGPL-3.0-or-later); see THIRD_PARTY_NOTICES.md.
 */
public final class DlssSuperResolution {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanite/DlssSR");

    // NVSDK_NGX_DLSS_Feature_Flags. The caller states what its buffers actually are via
    // ensureFeature's featureFlags argument rather than us guessing Vulkanite's HDR/jitter state.
    public static final int FLAG_IS_HDR = 1 << 0;
    public static final int FLAG_MV_LOW_RES = 1 << 1;
    public static final int FLAG_MV_JITTERED = 1 << 2;
    public static final int FLAG_DEPTH_INVERTED = 1 << 3;
    public static final int FLAG_DO_SHARPENING = 1 << 4;
    public static final int FLAG_AUTO_EXPOSURE = 1 << 6;

    private final VContext ctx;

    private NgxLibrary lib;
    private MemorySegment feature = MemorySegment.NULL;
    private boolean initialized;
    private boolean failed;
    private boolean loggedAvailable;

    private int featureRenderWidth = -1;
    private int featureRenderHeight = -1;
    private int featureDisplayWidth = -1;
    private int featureDisplayHeight = -1;
    private int featureQuality = Integer.MIN_VALUE;
    private int featurePreset = Integer.MIN_VALUE;
    private int featureFlags = Integer.MIN_VALUE;

    private boolean resetHistory;
    private long lastFrameNanos;

    public DlssSuperResolution(VContext ctx) {
        this.ctx = ctx;
    }

    public boolean isReady() {
        return initialized && !failed && !isNull(feature);
    }

    /** Whether a previous failure has permanently disabled this backend for the current device. */
    public boolean isFailed() {
        return failed;
    }

    /**
     * Ask NGX what render resolution the current quality mode expects for {@code displayWidth} x
     * {@code displayHeight}. Returns {@code null} when SR is off or has failed, in which case the
     * caller should render at full resolution.
     */
    public int[] queryOptimalRenderSize(int displayWidth, int displayHeight) {
        if (!DlssConfig.srEnabled() || failed) {
            return null;
        }
        ensureInitialized();
        try {
            int[] out = new int[3];
            try (var arena = java.lang.foreign.Arena.ofConfined()) {
                MemorySegment outWidth = arena.allocate(java.lang.foreign.ValueLayout.JAVA_INT);
                MemorySegment outHeight = arena.allocate(java.lang.foreign.ValueLayout.JAVA_INT);
                MemorySegment outSharpness = arena.allocate(java.lang.foreign.ValueLayout.JAVA_FLOAT);
                int rc = lib.queryOptimal(displayWidth, displayHeight, DlssConfig.srQuality(),
                        outWidth, outHeight, outSharpness);
                if (NgxRuntime.ngxFailed(rc)) {
                    throw new IllegalStateException(
                            "ngxshim_query_optimal failed: 0x" + Integer.toHexString(rc));
                }
                out[0] = outWidth.get(java.lang.foreign.ValueLayout.JAVA_INT, 0);
                out[1] = outHeight.get(java.lang.foreign.ValueLayout.JAVA_INT, 0);
                out[2] = (int) outSharpness.get(java.lang.foreign.ValueLayout.JAVA_FLOAT, 0);
            }
            if (out[0] <= 0 || out[1] <= 0) {
                throw new IllegalStateException(
                        "ngxshim_query_optimal returned invalid render size " + out[0] + "x" + out[1]);
            }
            return out;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("DLSS-SR render-size query failed; falling back to full resolution", t);
            return null;
        }
    }

    /**
     * Ensure NGX is initialized and an SR feature exists for the given resolutions, creating it into
     * the supplied recording command buffer. {@code featureFlags} is an OR of the {@code FLAG_*}
     * constants describing the buffers that will be passed to {@link #evaluate}. Returns false (and
     * disables itself) on any failure so the caller falls back to the non-DLSS path.
     */
    public boolean ensureFeature(long cmd, int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                                 int featureFlags) {
        if (!DlssConfig.srEnabled() || failed) {
            return false;
        }
        try {
            ensureInitialized();
            int quality = DlssConfig.srQuality();
            int preset = DlssConfig.srPreset();
            if (featureRenderWidth != renderWidth || featureRenderHeight != renderHeight
                    || featureDisplayWidth != displayWidth || featureDisplayHeight != displayHeight
                    || featureQuality != quality || featurePreset != preset
                    || this.featureFlags != featureFlags || isNull(feature)) {
                releaseFeature();
                feature = lib.createDlss(cmd, renderWidth, renderHeight, displayWidth, displayHeight,
                        quality, featureFlags, preset);
                if (isNull(feature)) {
                    throw new IllegalStateException("ngxshim_create_dlss failed: last=0x"
                            + Integer.toHexString(lib.lastResult()));
                }
                featureRenderWidth = renderWidth;
                featureRenderHeight = renderHeight;
                featureDisplayWidth = displayWidth;
                featureDisplayHeight = displayHeight;
                featureQuality = quality;
                featurePreset = preset;
                this.featureFlags = featureFlags;
                resetHistory = true; // a fresh feature has no temporal history
                LOGGER.info("DLSS-SR feature created: {}x{} -> {}x{} (quality {}, preset {}, flags 0x{})",
                        renderWidth, renderHeight, displayWidth, displayHeight, quality, preset,
                        Integer.toHexString(featureFlags));
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("DLSS-SR setup failed; continuing without it", t);
            return false;
        }
    }

    /**
     * Record a DLSS-SR evaluation: upscale {@code color} (at render res) into {@code output} (at
     * display res) using {@code depth} and {@code motion}. {@code jitterX}/{@code jitterY} is the
     * sub-pixel camera jitter applied this frame, in render pixels; {@code mvScaleX}/{@code mvScaleY}
     * converts motion-vector units to render pixels (1.0 when MVs are already in render-pixel space).
     * Returns false (disabling SR) on failure.
     */
    public boolean evaluate(long cmd, DlssResource color, DlssResource depth, DlssResource motion,
                            DlssResource output,
                            int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                            float jitterX, float jitterY, float mvScaleX, float mvScaleY) {
        if (!isReady()) {
            return false;
        }
        try {
            long now = System.nanoTime();
            float frameMs = lastFrameNanos == 0 ? 16.6f
                    : Math.clamp((now - lastFrameNanos) / 1_000_000.0f, 0.1f, 200.0f);
            lastFrameNanos = now;

            int rc = lib.evaluate(cmd, feature,
                    color.view(), color.image(), colorFormat(color),
                    depth.view(), depth.image(), depthFormat(depth),
                    motion.view(), motion.image(), motionFormat(motion),
                    output.view(), output.image(), outputFormat(output),
                    renderWidth, renderHeight, displayWidth, displayHeight,
                    jitterX, jitterY, mvScaleX, mvScaleY, resetHistory ? 1 : 0, frameMs);
            resetHistory = false;
            if (NgxRuntime.ngxFailed(rc)) {
                throw new IllegalStateException("ngxshim_evaluate failed: 0x" + Integer.toHexString(rc)
                        + " last=0x" + Integer.toHexString(lib.lastResult()));
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("DLSS-SR evaluate failed; continuing without it", t);
            return false;
        }
    }

    /** Release the SR feature if it has been switched off, so its history buffers stop holding VRAM. */
    public boolean releaseIfDisabled() {
        if (DlssConfig.srEnabled() || isNull(feature)) {
            return false;
        }
        releaseFeature();
        LOGGER.info("DLSS-SR feature released: disabled");
        return true;
    }

    /**
     * Release the SR feature. Does NOT shut down NGX — that is the shared {@link NgxRuntime}'s job at
     * device teardown, so another feature can keep using NGX.
     */
    public void destroy() {
        releaseFeature();
        initialized = false;
        lib = null;
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        lib = NgxRuntime.INSTANCE.acquire(ctx);
        if (lib == null) {
            throw new IllegalStateException("NGX runtime unavailable; DLSS-SR cannot initialize");
        }
        boolean available = lib.dlssAvailable();
        if (!loggedAvailable) {
            loggedAvailable = true;
            LOGGER.info("DLSS Super Resolution available: {}", available);
        }
        if (!available) {
            throw new IllegalStateException("DLSS Super Resolution is not available on this system");
        }
        initialized = true;
    }

    private void releaseFeature() {
        if (!isNull(feature)) {
            // NGX features own device memory and may still be referenced by in-flight command buffers.
            VK10.vkDeviceWaitIdle(ctx.device);
            lib.release(feature);
            feature = MemorySegment.NULL;
        }
        featureRenderWidth = -1;
        featureRenderHeight = -1;
        featureDisplayWidth = -1;
        featureDisplayHeight = -1;
        featureQuality = Integer.MIN_VALUE;
        featurePreset = Integer.MIN_VALUE;
        featureFlags = Integer.MIN_VALUE;
    }

    // The shim takes the format from us rather than querying the image, so an unset (0) format would
    // build an invalid NGX resource. Fall back to the conventional format for each input instead of
    // silently passing 0.
    private static int colorFormat(DlssResource r) {
        return r.format() != 0 ? r.format() : VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
    }

    private static int depthFormat(DlssResource r) {
        return r.format() != 0 ? r.format() : VK10.VK_FORMAT_D32_SFLOAT;
    }

    private static int motionFormat(DlssResource r) {
        return r.format() != 0 ? r.format() : VK10.VK_FORMAT_R16G16_SFLOAT;
    }

    private static int outputFormat(DlssResource r) {
        return r.format() != 0 ? r.format() : VK10.VK_FORMAT_R8G8B8A8_UNORM;
    }

    private static boolean isNull(MemorySegment segment) {
        return segment == null || segment.equals(MemorySegment.NULL);
    }
}
