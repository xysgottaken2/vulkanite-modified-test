package me.cortex.vulkanite.dlss;

import me.cortex.vulkanite.lib.base.VContext;

import org.joml.Matrix4fc;
import org.lwjgl.vulkan.VK10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * DLSS Ray Reconstruction: denoise + upscale in one learned pass. Takes the noisy 1-sample-per-pixel
 * path-traced color plus the guide buffers a path tracer naturally has (depth, motion vectors,
 * diffuse/specular albedo, normals with roughness packed in the w channel, reflection motion vectors)
 * and writes a display-resolution result.
 *
 * <p>Ported from Caustica's {@code RtDlssRr} (LGPL-3.0-or-later); see THIRD_PARTY_NOTICES.md. The
 * feature lifecycle and the NGX matrix convention are unchanged; what differs is that Caustica owns
 * the guide buffers (its path tracer emits them), while Vulkanite has to be handed them by whatever
 * ray-tracing pass produces them. See the note in {@code docs} / the README about which guide buffers
 * a Vulkanite shader pack still needs to expose before this can run in-game.
 */
public final class DlssRayReconstruction {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanite/DlssRR");

    // NVSDK_NGX_DLSS_Feature_Flags. IsHDR (bit 0): color is linear HDR — RR requires it ("HDR Color
    // required"). MVLowRes (bit 1): motion vectors are at render/input resolution, not display — RR
    // requires it ("Low resolution Motion Vectors required"). DepthInverted (bit 3): the depth guide
    // is HW reversed-Z (near=1, far=0). AutoExposure (bit 6): in HDR mode DLSS needs the scene
    // exposure; without it the output is black, so let DLSS estimate exposure from the color itself.
    // MVs are unjittered, so no MV_JITTERED.
    private static final int FEATURE_FLAG_IS_HDR = 1 << 0;
    private static final int FEATURE_FLAG_MV_LOW_RES = 1 << 1;
    private static final int FEATURE_FLAG_DEPTH_INVERTED = 1 << 3;
    private static final int FEATURE_FLAG_AUTO_EXPOSURE = 1 << 6;
    private static final int FEATURE_FLAGS = FEATURE_FLAG_IS_HDR | FEATURE_FLAG_MV_LOW_RES
            | FEATURE_FLAG_DEPTH_INVERTED | FEATURE_FLAG_AUTO_EXPOSURE;

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

    private boolean resetHistory;
    private long lastFrameNanos;

    public DlssRayReconstruction(VContext ctx) {
        this.ctx = ctx;
    }

    public boolean isReady() {
        return initialized && !failed && !isNull(feature);
    }

    public boolean isFailed() {
        return failed;
    }

    /**
     * Record a DLSS-RR evaluation: denoise + upscale the noisy path-traced color (at render res) using
     * the guide buffers, writing the display-res result into {@code out}. {@code jitterX}/{@code jitterY}
     * is the sub-pixel camera jitter applied to the primary ray this frame, in render pixels. Returns
     * false (disabling RR) on failure. MVs are expected to already be in render-pixel space (scale 1).
     */
    public boolean evaluate(long cmd, DlssResource color, DlssResource depth, DlssResource motion,
                            DlssResource diffuseAlbedo, DlssResource specularAlbedo, DlssResource normals,
                            DlssResource specularMotion, DlssResource out,
                            int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                            float jitterX, float jitterY, Matrix4fc worldToView, Matrix4fc viewToClip) {
        if (!isReady()) {
            return false;
        }
        try {
            long now = System.nanoTime();
            float frameMs = lastFrameNanos == 0 ? 16.6f
                    : Math.clamp((now - lastFrameNanos) / 1_000_000.0f, 0.1f, 200.0f);
            lastFrameNanos = now;

            int rc;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment worldToViewMatrix = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
                MemorySegment viewToClipMatrix = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
                putNgxLeftMultiplyMatrix(worldToView, worldToViewMatrix);
                putNgxLeftMultiplyMatrix(viewToClip, viewToClipMatrix);
                rc = lib.evaluateDlssd(cmd, feature,
                        color.view(), color.image(), VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                        depth.view(), depth.image(), VK10.VK_FORMAT_R32_SFLOAT,
                        motion.view(), motion.image(), VK10.VK_FORMAT_R16G16_SFLOAT,
                        diffuseAlbedo.view(), diffuseAlbedo.image(), VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                        specularAlbedo.view(), specularAlbedo.image(), VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                        normals.view(), normals.image(), VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                        specularMotion.view(), specularMotion.image(), VK10.VK_FORMAT_R16G16_SFLOAT,
                        0L, 0L, 0, // specular hit distance: optional, left null (see shim)
                        out.view(), out.image(), VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                        renderWidth, renderHeight, displayWidth, displayHeight,
                        // jitter in render pixels; MVs are already in render-pixel units, so MV scale = 1.
                        jitterX, jitterY, 1.0f, 1.0f, resetHistory ? 1 : 0, frameMs,
                        worldToViewMatrix, viewToClipMatrix);
            }
            resetHistory = false;
            if (NgxRuntime.ngxFailed(rc)) {
                throw new IllegalStateException("ngxshim_evaluate_dlssd failed: 0x" + Integer.toHexString(rc)
                        + " last=0x" + Integer.toHexString(lib.lastResult()));
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("DLSS-RR evaluate failed; continuing without it", t);
            return false;
        }
    }

    /** Release the RR feature if it has been switched off, so its history buffers stop holding VRAM. */
    public boolean releaseIfDisabled() {
        if (DlssConfig.rrEnabled() || isNull(feature)) {
            return false;
        }
        releaseFeature();
        LOGGER.info("DLSS-RR feature released: disabled");
        return true;
    }

    /**
     * Ask NGX what render resolution the current quality mode expects for the given display size.
     * Returns {@code null} when RR is off or has failed — in that state there is no feature to query
     * and the caller should trace at full resolution. Once RR is active, a failed query (stale shim,
     * old driver, bad NGX result) throws instead of silently falling back, so a broken render/display
     * sync is never masked.
     */
    public int[] queryOptimalRenderSize(int displayWidth, int displayHeight) {
        if (!DlssConfig.rrEnabled() || failed) {
            return null;
        }
        ensureInitialized();
        if (!lib.hasQueryOptimalDlssd()) {
            throw new IllegalStateException("ngxshim is missing ngxshim_query_optimal_dlssd (stale native shim)");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outWidth = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment outHeight = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment outSharpness = arena.allocate(ValueLayout.JAVA_FLOAT);
            int rc = lib.queryOptimalDlssd(displayWidth, displayHeight, DlssConfig.rrQuality(),
                    outWidth, outHeight, outSharpness);
            if (NgxRuntime.ngxFailed(rc)) {
                throw new IllegalStateException(
                        "ngxshim_query_optimal_dlssd failed: 0x" + Integer.toHexString(rc));
            }
            int renderWidth = outWidth.get(ValueLayout.JAVA_INT, 0);
            int renderHeight = outHeight.get(ValueLayout.JAVA_INT, 0);
            if (renderWidth <= 0 || renderHeight <= 0) {
                throw new IllegalStateException(
                        "ngxshim_query_optimal_dlssd returned invalid render size "
                                + renderWidth + "x" + renderHeight);
            }
            return new int[] { renderWidth, renderHeight };
        }
    }

    /**
     * Ensure NGX is initialized and an RR feature exists for the given resolutions, creating it into
     * the supplied recording command buffer. Returns false (and disables itself) on any failure so the
     * caller falls back to the non-RR path.
     */
    public boolean ensureFeature(long cmd, int renderWidth, int renderHeight, int displayWidth, int displayHeight) {
        if (!DlssConfig.rrEnabled() || failed) {
            return false;
        }
        try {
            ensureInitialized();
            int quality = DlssConfig.rrQuality();
            int preset = DlssConfig.rrPreset();
            if (featureRenderWidth != renderWidth || featureRenderHeight != renderHeight
                    || featureDisplayWidth != displayWidth || featureDisplayHeight != displayHeight
                    || featureQuality != quality || featurePreset != preset
                    || isNull(feature)) {
                releaseFeature();
                feature = lib.createDlssd(cmd, renderWidth, renderHeight, displayWidth, displayHeight,
                        quality, FEATURE_FLAGS, preset);
                if (isNull(feature)) {
                    throw new IllegalStateException("ngxshim_create_dlssd failed: last=0x"
                            + Integer.toHexString(lib.lastResult()));
                }
                featureRenderWidth = renderWidth;
                featureRenderHeight = renderHeight;
                featureDisplayWidth = displayWidth;
                featureDisplayHeight = displayHeight;
                featureQuality = quality;
                featurePreset = preset;
                resetHistory = true; // a fresh feature has no temporal history
                LOGGER.info("DLSS-RR feature created: {}x{} -> {}x{} (quality {}, preset {})",
                        renderWidth, renderHeight, displayWidth, displayHeight, quality, preset);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("DLSS-RR setup failed; continuing without it", t);
            return false;
        }
    }

    /**
     * Release the RR feature. Does NOT shut down NGX — that is the shared {@link NgxRuntime}'s job at
     * device teardown, so DLSS-SR can keep using NGX.
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
        // NGX init/shutdown is owned by the shared NgxRuntime so SR and RR can coexist (releasing one
        // feature must not tear NGX down while the other still holds a handle).
        lib = NgxRuntime.INSTANCE.acquire(ctx);
        if (lib == null) {
            throw new IllegalStateException("NGX runtime unavailable; DLSS-RR cannot initialize");
        }
        boolean available = lib.dlssdAvailable();
        if (!loggedAvailable) {
            loggedAvailable = true;
            LOGGER.info("DLSS Ray Reconstruction available: {}", available);
        }
        if (!available) {
            throw new IllegalStateException("DLSS Ray Reconstruction is not available on this system");
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
    }

    private static boolean isNull(MemorySegment segment) {
        return segment == null || segment.equals(MemorySegment.NULL);
    }

    private static void putNgxLeftMultiplyMatrix(Matrix4fc m, MemorySegment dst) {
        // NGX wants row-major matrices used with left-multiplied row vectors. Our JOML/GLSL matrices
        // are used with column vectors, so the equivalent NGX matrix is the transpose; JOML's normal
        // storage order is exactly row-major storage of that transpose.
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 0, m.m00());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 1, m.m01());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 2, m.m02());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 3, m.m03());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 4, m.m10());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 5, m.m11());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 6, m.m12());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 7, m.m13());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 8, m.m20());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 9, m.m21());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 10, m.m22());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 11, m.m23());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 12, m.m30());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 13, m.m31());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 14, m.m32());
        dst.setAtIndex(ValueLayout.JAVA_FLOAT, 15, m.m33());
    }
}
