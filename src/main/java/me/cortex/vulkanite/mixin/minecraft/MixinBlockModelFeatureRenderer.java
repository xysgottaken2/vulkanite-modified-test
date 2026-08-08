package me.cortex.vulkanite.mixin.minecraft;

import java.util.List;
import me.cortex.vulkanite.client.rendering.entity.EntityGeometryCollector;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockModelFeatureRenderer.class)
public class MixinBlockModelFeatureRenderer {
    @Inject(method = "buildGroup", at = @At("HEAD"), cancellable = true)
    private void vulkanite$captureBlockModels(FeatureFrameContext context,
            List<BlockModelFeatureRenderer.Submit> submits, CallbackInfo ci) {
        if (submits.isEmpty()) {
            return;
        }
        for (BlockModelFeatureRenderer.Submit submit : submits) {
            if (!EntityGeometryCollector.INSTANCE.capture(submit)) {
                return;
            }
        }
        ci.cancel();
    }
}
