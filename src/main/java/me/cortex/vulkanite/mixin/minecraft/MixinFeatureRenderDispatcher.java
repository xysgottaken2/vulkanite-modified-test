package me.cortex.vulkanite.mixin.minecraft;

import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.client.rendering.entity.EntityGeometryCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FeatureRenderDispatcher.class)
public class MixinFeatureRenderDispatcher {
    @Inject(method = "prepareFrame", at = @At("RETURN"))
    private void vulkanite$finishEntityCapture(SubmitNodeStorage storage,
            CallbackInfoReturnable<FeatureRenderDispatcher.PreparedFrame> cir) {
        // prepareFrame is also used by GUI and item rendering. Only the world
        // frame opened by LevelRenderer.submitFeatures owns entity RT history.
        if (!EntityGeometryCollector.INSTANCE.isFrameOpen()) {
            return;
        }
        Vulkanite.INSTANCE.getAccelerationManager().setEntityGeometry(EntityGeometryCollector.INSTANCE.endFrame());
    }
}
