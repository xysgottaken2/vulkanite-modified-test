package me.cortex.vulkanite.mixin.minecraft;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.lib.memory.VGImage;
import net.minecraft.client.renderer.texture.AbstractTexture;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/*
 * MC 26.2: AbstractTexture no longer has glId/getGlId/clearGlId.
 * Instead uses GpuTexture from RenderSystem.getDevice().
 * We add IVGImage support for Vulkan texture sharing.
 */
@Mixin(AbstractTexture.class)
public class MixinAbstractTexture implements IVGImage {
    @Unique
    private Optional<VGImage> vgImage = Optional.empty();

    @Unique
    private void ensureVGImage() {
        if (vgImage == null) {
            vgImage = Optional.empty();
        }
    }

    @Override
    public void setVGImage(VGImage image) {
        this.vgImage = Optional.of(image);
    }

    @Override
    public Optional<VGImage> getVGImage() {
        ensureVGImage();
        return vgImage;
    }

    @Inject(method = "releaseTextures", at = @At("HEAD"))
    private void onRelease(CallbackInfo ci) {
        ensureVGImage();
        if (vgImage.isPresent()) {
            Vulkanite.INSTANCE.addSyncedCallback(vgImage.get()::free);
            vgImage = Optional.empty();
        }
    }
}
