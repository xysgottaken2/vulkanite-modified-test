package me.cortex.vulkanite.mixin.minecraft;

import me.cortex.vulkanite.client.rendering.entity.EntityGeometryCollector;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelFeatureRenderer.class)
public class MixinModelFeatureRenderer {
    @Inject(method = "prepareModel", at = @At("HEAD"), cancellable = true)
    private <S> void vulkanite$captureModel(ModelFeatureRenderer.Submit<S> submit, CallbackInfo ci) {
        if (EntityGeometryCollector.INSTANCE.capture(submit)) {
            ci.cancel();
        }
    }
}
