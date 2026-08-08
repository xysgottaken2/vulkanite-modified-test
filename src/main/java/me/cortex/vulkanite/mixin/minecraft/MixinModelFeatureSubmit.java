package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.blaze3d.vertex.PoseStack;
import me.cortex.vulkanite.client.rendering.entity.EntityGeometryCollector;
import me.cortex.vulkanite.client.rendering.entity.EntityOwnedSubmissionExtension;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.jspecify.annotations.Nullable;

@Mixin(ModelFeatureRenderer.Submit.class)
public class MixinModelFeatureSubmit implements EntityOwnedSubmissionExtension {
    @Unique
    private long vulkanite$ownerKey = -1L;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void vulkanite$captureOwner(RenderType renderType, PoseStack.Pose pose, Model<?> model,
            Object state, int lightCoords, int overlayCoords, int tintedColor,
            @Nullable TextureAtlasSprite sprite, PoseStack.@Nullable Pose sheetedDecalPose,
            CallbackInfo ci) {
        vulkanite$ownerKey = EntityGeometryCollector.INSTANCE.currentSubmissionOwner();
    }

    @Override
    public long vulkanite$getOwnerKey() {
        return vulkanite$ownerKey;
    }

    @Override
    public void vulkanite$setOwnerKey(long ownerKey) {
        vulkanite$ownerKey = ownerKey;
    }
}
