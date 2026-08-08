package me.cortex.vulkanite.mixin.iris;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.GlTextureReflection;
import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.compat.RaytracingPackState;
import me.cortex.vulkanite.lib.memory.VGImage;
import net.irisshaders.iris.pbr.texture.PBRAtlasTexture;
import net.irisshaders.iris.pbr.texture.PBRType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Makes Iris's PBR atlas textures (normal/specular) Vulkan-shared from
 * creation,
 * matching the approach used for the block atlas in MixinTextureAtlas.
 * Without this the RT shaders' blockTexNormal/blockTexSpecular samplers read
 * the placeholder (flat normal/zero specular) since the PBR textures are never
 * made Vulkan-visible.
 */
@Mixin(value = PBRAtlasTexture.class, remap = false)
public abstract class MixinPBRAtlasTexture extends AbstractTexture {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanite/PBRAtlas");

    @Shadow
    protected PBRType type;

    @Redirect(method = "createTexture", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;", remap = false))
    private GpuTexture vulkanite$makePBRAtlasShared(GpuDevice device, Supplier<String> label, int usage,
            GpuFormat format, int width, int height,
            int depthOrLayers, int mipLevels) {
        if (!RaytracingPackState.isActive()) {
            return device.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
        }
        LOGGER.info("Redirecting PBRAtlasTexture.createTexture to Vulkan-shared path for type {}", type);
        if (Vulkanite.INSTANCE == null) {
            LOGGER.warn("Vulkanite instance is null, falling back to plain GL for PBR atlas");
            return device.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
        }
        if (!Vulkanite.IS_ENABLED) {
            LOGGER.warn("Vulkanite is disabled, falling back to plain GL for PBR atlas");
            return device.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
        }
        VGImage vg = null;
        try {
            var ctx = Vulkanite.INSTANCE.getCtx();
            vg = ctx.memory.createSharedImage(width, height, 1, mipLevels,
                    VK_FORMAT_R8G8B8A8_UNORM, GL_RGBA8,
                    VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            String labelStr = label != null ? label.get() : "pbr-atlas";

            // Fill each mip level with the PBR default colour. Iris's fillWithColor
            // (called after createTexture returns) does this via FBO attach + glClear,
            // but FBO attachment to Vulkan-shared immutable textures can silently fail
            // on NVIDIA, leaving the storage zeroed. Unoccupied atlas regions (blocks
            // without _n.png / _s.png) would then read alpha=0 → height=-POM_DEPTH
            // → POM always steps to max displacement. glTextureSubImage2D always works
            // regardless of texture provenance and fixes this.
            int rgba = type.getDefaultValue();
            // fillWithColor's clearColor mapping: clearColor(R = rgba>>24, G = rgba>>16,
            // B = rgba>>8, A = rgba>>0). After glClear, texture bytes are [R, G, B, A].
            int r = (rgba >> 24) & 0xFF, g = (rgba >> 16) & 0xFF;
            int b = (rgba >> 8) & 0xFF, a = (rgba >> 0) & 0xFF;
            int maxMipLevel = mipLevels - 1;
            for (int lvl = 0; lvl <= maxMipLevel; lvl++) {
                int lvlW = Math.max(1, width >> lvl);
                int lvlH = Math.max(1, height >> lvl);
                ByteBuffer buf = MemoryUtil.memAlloc(lvlW * lvlH * 4);
                for (int p = 0; p < lvlW * lvlH; p++) {
                    buf.put((byte) r).put((byte) g).put((byte) b).put((byte) a);
                }
                buf.flip();
                GL45C.glTextureSubImage2D(vg.glId, lvl, 0, 0, lvlW, lvlH,
                        GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, buf);
                MemoryUtil.memFree(buf);
            }

            GpuTexture gpuTex = GlTextureReflection.wrapSharedTexture(
                    device, vg.glId, labelStr, usage, format, width, height, depthOrLayers, mipLevels)
                    .orElseThrow(() -> new RuntimeException("Failed to wrap shared texture for PBR atlas"));

            vg.transferGlTextureOwnership();
            ((IVGImage) this).setVGImage(vg);
            LOGGER.info("PBR atlas {} {}x{} mips={} made Vulkan-shared (glId={})",
                    labelStr, width, height, mipLevels, vg.glId);
            return gpuTex;
        } catch (Exception e) {
            if (vg != null) {
                vg.free();
            }
            LOGGER.error("Failed to make PBR atlas Vulkan-shared, falling back to plain GL", e);
            return device.createTexture(label, usage, format, width, height, depthOrLayers, mipLevels);
        }
    }
}
