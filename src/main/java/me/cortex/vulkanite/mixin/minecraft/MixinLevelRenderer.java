package me.cortex.vulkanite.mixin.minecraft;

import me.cortex.vulkanite.client.rendering.entity.EntityGeometryCollector;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {
    @Inject(method = "submitFeatures", at = @At("HEAD"))
    private void vulkanite$beginEntityCapture(LevelRenderState levelRenderState,
            SubmitNodeCollector submitNodeCollector, boolean renderOutline, CallbackInfo ci) {
        EntityGeometryCollector.INSTANCE.beginFrame(levelRenderState.cameraRenderState.pos);
    }
}
