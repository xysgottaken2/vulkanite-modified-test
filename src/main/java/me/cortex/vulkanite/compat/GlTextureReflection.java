package me.cortex.vulkanite.compat;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Optional;

/**
 * Constructs a real com.mojang.blaze3d.opengl.GlTexture wrapping a
 * pre-allocated
 * Vulkan-shared GL texture id, via reflection.
 *
 * Why reflection: GlTexture's constructor is `protected`, and the class itself
 * is
 * package-private-adjacent (only GlDevice normally constructs it). Two other
 * approaches
 * were tried and rejected:
 * - An access widener hit a Loom bug ("Expected official namespace for access
 * widener
 * entry, found: named") when widening a class in the *live game* jar (as
 * opposed to a
 * library jar) at this Loom version.
 * - Placing a mixin source file inside com.mojang.blaze3d.opengl itself (to get
 * genuine
 * same-package access) is rejected by Mixin at class-load time: declaring a
 * mixin
 * "package" that overlaps a real game package makes Mixin treat every class in
 * that
 * package as mixin-owned, and refuses to load
 * com.mojang.blaze3d.opengl.GlBackend
 * ("is in a defined mixin package ... and cannot be referenced directly") - a
 * hard
 * crash at startup, before any of our textures are even touched.
 *
 * Reflection with setAccessible(true) has none of these problems:
 * Minecraft/Fabric run
 * unmodularized (no module boundaries to violate), so this is a supported,
 * standard way
 * to invoke a protected constructor from an unrelated package.
 */
public final class GlTextureReflection {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanite/GlTextureReflection");
    private static volatile Constructor<?> glTextureCtor;
    private static volatile Field frameBufferCacheField;
    private static volatile Field gpuDeviceBackendField;

    private GlTextureReflection() {
    }

    /**
     * Wraps glId into a real GlTexture, using the same FrameBufferCache instance as
     * the
     * given GpuDevice (so the resulting texture behaves identically to one
     * GlDevice.
     * createTexture() would have produced - correct FBO caching, close() behavior,
     * etc.).
     *
     * Returns null if reflection setup fails (e.g. an incompatible Minecraft
     * version) -
     * callers must fall back to plain (non-Vulkan-shared) texture creation in that
     * case.
     */
    public static Optional<GpuTexture> wrapSharedTexture(Object gpuDevice, int glId, String label, int usage,
            GpuFormat format, int width, int height,
            int depthOrLayers, int mipLevels) {
        try {
            Object glDevice = unwrapBackend(gpuDevice);
            ensureInit(glDevice);
            Object frameBufferCache = frameBufferCacheField.get(glDevice);
            return Optional.of((GpuTexture) glTextureCtor.newInstance(
                    usage, label, format, width, height, depthOrLayers, mipLevels, glId, frameBufferCache));
        } catch (Exception e) {
            LOGGER.error("Failed to construct GlTexture via reflection, Vulkan texture sharing for " +
                    "block atlas / PBR textures will be disabled (falls back to plain GL)", e);
            return Optional.empty();
        }
    }

    // RenderSystem.getDevice() returns the public
    // com.mojang.blaze3d.systems.GpuDevice
    // wrapper, which delegates to a private `backend` field holding the real
    // GlDevice
    // (the class that actually owns `frameBufferCache`). Unwrap it first.
    private static Object unwrapBackend(Object gpuDevice) throws Exception {
        if (gpuDeviceBackendField == null) {
            LOGGER.info("Unwrapping GpuDevice to get GlDevice backend via reflection");
            synchronized (GlTextureReflection.class) {
                if (gpuDeviceBackendField == null) {
                    LOGGER.info("GpuDevice backend field not yet cached, looking up via reflection");
                    Field field = gpuDevice.getClass().getDeclaredField("backend");
                    field.setAccessible(true);
                    gpuDeviceBackendField = field;
                    LOGGER.info("GpuDevice backend field cached for future use: {}", field);
                }
            }
        }
        LOGGER.info("Unwrapping GpuDevice {} to get GlDevice backend", gpuDevice);
        return gpuDeviceBackendField.get(gpuDevice);
    }

    private static void ensureInit(Object glDevice) throws Exception {
        if (glTextureCtor != null) {
            LOGGER.info("GlTextureReflection already initialized, skipping reflection setup");
            return;
        }
        synchronized (GlTextureReflection.class) {
            if (glTextureCtor != null) {
                LOGGER.info("GlTextureReflection already initialized, skipping reflection setup");
                return;
            }
            LOGGER.info("GlTextureReflection not yet initialized, performing reflection setup");
            Class<?> glTextureClass = Class.forName("com.mojang.blaze3d.opengl.GlTexture");
            Class<?> frameBufferCacheClass = Class.forName("com.mojang.blaze3d.opengl.FrameBufferCache");
            Class<?> gpuFormatClass = Class.forName("com.mojang.blaze3d.GpuFormat");

            Constructor<?> ctor = glTextureClass.getDeclaredConstructor(
                    int.class, String.class, gpuFormatClass, int.class, int.class, int.class, int.class,
                    int.class, frameBufferCacheClass);
            ctor.setAccessible(true);

            Field field = glDevice.getClass().getDeclaredField("frameBufferCache");
            field.setAccessible(true);

            glTextureCtor = ctor;
            frameBufferCacheField = field;
            LOGGER.info("GlTextureReflection initialized: constructor={}, frameBufferCacheField={}",
                    glTextureCtor, frameBufferCacheField);
        }
    }
}
