package me.cortex.vulkanite.mixin.iris;

import me.cortex.vulkanite.client.rendering.entity.EntityGeometryCollector;
import net.irisshaders.iris.pathways.HandRenderer;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = HandRenderer.class, remap = false)
public class MixinHandRenderer {
    @Inject(method = "renderSolid", at = @At("HEAD"))
    private void vulkanite$beginHandCapture(Matrix4fc modelMatrix, float tickDelta, Camera camera,
            CameraRenderState cameraState, GameRenderer gameRenderer, WorldRenderingPipeline pipeline,
            CallbackInfo ci) {
        EntityGeometryCollector.INSTANCE.beginHandFrame();
    }

    @Inject(method = "renderTranslucent", at = @At("RETURN"))
    private void vulkanite$finishHandCapture(Matrix4fc modelMatrix, float tickDelta, Camera camera,
            CameraRenderState cameraState, GameRenderer gameRenderer, WorldRenderingPipeline pipeline,
            CallbackInfo ci) {
        EntityGeometryCollector.INSTANCE.endHandFrame();
    }
}
