package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.vulkanite.client.rendering.entity.EntityGeometryCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntityRenderDispatcher.class)
public class MixinBlockEntityRenderDispatcher {
    @Inject(method = "submit", at = @At("HEAD"))
    private <S extends BlockEntityRenderState> void vulkanite$beginBlockEntitySubmission(S state,
            PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera,
            CallbackInfo ci) {
        EntityGeometryCollector.INSTANCE.beginBlockEntitySubmission(state.blockPos);
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private <S extends BlockEntityRenderState> void vulkanite$endBlockEntitySubmission(S state,
            PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera,
            CallbackInfo ci) {
        EntityGeometryCollector.INSTANCE.endEntitySubmission();
    }
}
