package me.cortex.vulkanite.mixin.minecraft;

import me.cortex.vulkanite.client.rendering.entity.EntityRenderStateExtension;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public class MixinEntityRenderState implements EntityRenderStateExtension {
    @Unique
    private int vulkanite$entityId = -1;

    @Override
    public int vulkanite$getEntityId() {
        return vulkanite$entityId;
    }

    @Override
    public void vulkanite$setEntityId(int entityId) {
        vulkanite$entityId = entityId;
    }
}
