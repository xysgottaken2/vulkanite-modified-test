package me.cortex.vulkanite.mixin.minecraft;

import me.cortex.vulkanite.client.rendering.entity.EntityRenderStateExtension;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public class MixinEntityRenderer {
    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void vulkanite$storeEntityId(Entity entity, EntityRenderState state, float partialTicks,
            CallbackInfo ci) {
        ((EntityRenderStateExtension) state).vulkanite$setEntityId(entity.getId());
    }
}
