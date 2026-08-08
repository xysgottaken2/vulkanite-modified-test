package me.cortex.vulkanite.mixin.minecraft;

import java.util.List;
import me.cortex.vulkanite.client.rendering.entity.EntityGeometryCollector;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShadowFeatureRenderer.class)
public class MixinShadowFeatureRenderer {
    @Inject(method = "buildGroup", at = @At("HEAD"), cancellable = true)
    private void vulkanite$skipVanillaEntityShadows(FeatureFrameContext context,
            List<ShadowFeatureRenderer.Submit> submits, CallbackInfo ci) {
        if (EntityGeometryCollector.INSTANCE.isRayTracingActive()) {
            ci.cancel();
        }
    }
}
