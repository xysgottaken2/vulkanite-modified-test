package me.cortex.vulkanite.mixin.minecraft;

import me.cortex.vulkanite.client.rendering.entity.EntityGeometryCollector;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemFeatureRenderer.class)
public class MixinItemFeatureRenderer {
    @Inject(method = "prepareMainSubmit", at = @At("HEAD"), cancellable = true)
    private void vulkanite$captureItem(ItemFeatureRenderer.Submit submit, CallbackInfo ci) {
        if (EntityGeometryCollector.INSTANCE.capture(submit)) {
            ci.cancel();
        }
    }
}
