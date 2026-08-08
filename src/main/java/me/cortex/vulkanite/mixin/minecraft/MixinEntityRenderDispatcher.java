package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.vulkanite.client.rendering.entity.EntityGeometryCollector;
import me.cortex.vulkanite.client.rendering.entity.EntityRenderStateExtension;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public class MixinEntityRenderDispatcher {
    @Inject(method = "submit", at = @At("HEAD"))
    private <S extends EntityRenderState> void vulkanite$beginEntitySubmission(S renderState,
            CameraRenderState camera, double x, double y, double z, PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
        int entityId = ((EntityRenderStateExtension) renderState).vulkanite$getEntityId();
        EntityGeometryCollector.INSTANCE.beginEntitySubmission(entityId);
    }

    @Inject(method = "submit", at = @At("RETURN"))
    private <S extends EntityRenderState> void vulkanite$endEntitySubmission(S renderState,
            CameraRenderState camera, double x, double y, double z, PoseStack poseStack,
            SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
        EntityGeometryCollector.INSTANCE.endEntitySubmission();
    }
}
