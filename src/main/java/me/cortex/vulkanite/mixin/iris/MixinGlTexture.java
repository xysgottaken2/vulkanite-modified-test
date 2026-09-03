package me.cortex.vulkanite.mixin.iris;

import com.mojang.blaze3d.opengl.GlStateManager;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.IGlResourceIdAccessor;
import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.compat.RaytracingPackState;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.lib.other.FormatConverter;
import net.irisshaders.iris.gl.texture.GlTexture;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.shaderpack.texture.TextureFilteringData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.util.Optional;

import static org.lwjgl.opengl.GL11C.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11C.glGetError;
import static org.lwjgl.opengl.GL45C.glTextureSubImage1D;
import static org.lwjgl.opengl.GL45C.glTextureSubImage2D;
import static org.lwjgl.opengl.GL45C.glTextureSubImage3D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;

/** Shares Iris raw custom textures with Vulkan, including sampler3D LUTs. */
@Mixin(value = GlTexture.class, remap = false)
public abstract class MixinGlTexture implements IVGImage {
    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanite/IrisRawTexture");

    @Unique
    private VGImage vulkanite$sharedImage;

    @Unique
    private static boolean vulkanite$shouldShare() {
        return Vulkanite.IS_ENABLED && RaytracingPackState.isActive();
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_genTexture()I"))
    private static int vulkanite$deferTextureCreation() {
        return vulkanite$shouldShare() ? 0 : GlStateManager._genTexture();
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/gl/texture/GlTexture;getGlId()I", ordinal = 0))
    private int vulkanite$createSharedTexture(GlTexture instance, TextureType target,
            int sizeX, int sizeY, int sizeZ, int internalFormat, int format,
            int pixelType, byte[] pixels, TextureFilteringData filteringData) {
        IGlResourceIdAccessor resource = (IGlResourceIdAccessor) instance;
        if (!vulkanite$shouldShare()) {
            return resource.vulkanite$getGlId();
        }

        try {
            InternalTextureFormat textureFormat = FormatConverter.findFormatFromGlFormat(internalFormat);
            if (textureFormat == null) {
                throw new IllegalArgumentException("Unknown Iris raw texture format 0x"
                        + Integer.toHexString(internalFormat));
            }

            int width = Math.max(sizeX, 1);
            int height = target == TextureType.TEXTURE_1D ? 1 : Math.max(sizeY, 1);
            int depth = target == TextureType.TEXTURE_3D ? Math.max(sizeZ, 1) : 1;
            int sharedGlFormat = FormatConverter.getSharedGlFormat(textureFormat);

            vulkanite$sharedImage = Vulkanite.INSTANCE.getCtx().memory.createSharedImage(
                    width, height, depth, 1,
                    FormatConverter.getVkFormatFromGl(textureFormat), sharedGlFormat,
                    VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, target.getGlType());
            resource.vulkanite$setGlId(vulkanite$sharedImage.glId);
            LOGGER.info("Created shared Iris raw texture {}x{}x{}, target={}, format={}",
                    width, height, depth, target, textureFormat);
            return vulkanite$sharedImage.glId;
        } catch (RuntimeException | AssertionError e) {
            int fallbackId = GlStateManager._genTexture();
            resource.vulkanite$setGlId(fallbackId);
            LOGGER.error("Failed to create a Vulkan-shared Iris raw texture; using GL fallback", e);
            return fallbackId;
        }
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/gl/texture/TextureType;apply(IIIIIIILjava/nio/ByteBuffer;)V"))
    private void vulkanite$uploadSharedTexture(TextureType target, int glId,
            int width, int height, int depth, int internalFormat, int format,
            int pixelType, ByteBuffer data) {
        if (vulkanite$sharedImage == null) {
            target.apply(glId, width, height, depth, internalFormat, format, pixelType, data);
            return;
        }

        switch (target) {
            case TEXTURE_1D -> glTextureSubImage1D(glId, 0, 0, width, format, pixelType, data);
            case TEXTURE_2D, TEXTURE_RECTANGLE ->
                    glTextureSubImage2D(glId, 0, 0, 0, width, height, format, pixelType, data);
            case TEXTURE_3D ->
                    glTextureSubImage3D(glId, 0, 0, 0, 0, width, height, depth, format, pixelType, data);
        }

        int error = glGetError();
        if (error != GL_NO_ERROR) {
            VGImage failedImage = vulkanite$sharedImage;
            vulkanite$sharedImage = null;
            failedImage.free();
            throw new IllegalStateException("Failed to upload Iris raw texture to shared image: GL error 0x"
                    + Integer.toHexString(error));
        }
    }

    @Inject(method = "destroyInternal", at = @At("HEAD"), cancellable = true)
    private void vulkanite$destroySharedTexture(CallbackInfo ci) {
        if (vulkanite$sharedImage != null) {
            vulkanite$sharedImage.free();
            vulkanite$sharedImage = null;
            ci.cancel();
        }
    }

    @Override
    public Optional<VGImage> getVGImage() {
        return Optional.ofNullable(vulkanite$sharedImage);
    }

    @Override
    public void setVGImage(VGImage image) {
        if (vulkanite$sharedImage != null && vulkanite$sharedImage != image) {
            throw new IllegalStateException("Iris raw texture already has a shared image");
        }
        vulkanite$sharedImage = image;
    }
}
