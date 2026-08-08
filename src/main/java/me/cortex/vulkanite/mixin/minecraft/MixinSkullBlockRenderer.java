package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.vulkanite.client.rendering.entity.EntityGeometryCollector;
import net.minecraft.client.model.object.skull.SkullModelBase;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkullBlockRenderer.class)
public class MixinSkullBlockRenderer {
    @Inject(method = "submitSkull", at = @At("HEAD"), cancellable = true)
    private static void vulkanite$captureSkull(float animationValue, PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector, int lightCoords, SkullModelBase model,
            RenderType renderType, int outlineColor,
            ModelFeatureRenderer.CrumblingOverlay breakProgress, CallbackInfo ci) {
        SkullModelBase.State state = new SkullModelBase.State();
        state.animationPos = animationValue;
        ModelFeatureRenderer.Submit<SkullModelBase.State> submit = new ModelFeatureRenderer.Submit<>(
                renderType, poseStack.last().copy(), model, state, lightCoords,
                OverlayTexture.NO_OVERLAY, -1, null, null);
        if (EntityGeometryCollector.INSTANCE.capture(submit)) {
            ci.cancel();
        }
    }
}
