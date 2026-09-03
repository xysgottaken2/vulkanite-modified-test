package me.cortex.vulkanite.mixin.iris;

import me.cortex.vulkanite.compat.IGlResourceIdAccessor;
import net.irisshaders.iris.gl.GlResource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = GlResource.class, remap = false)
public abstract class MixinGlResource implements IGlResourceIdAccessor {
    @Shadow
    @Final
    @Mutable
    private int id;

    @Override
    public int vulkanite$getGlId() {
        return id;
    }

    @Override
    public void vulkanite$setGlId(int id) {
        this.id = id;
    }
}
