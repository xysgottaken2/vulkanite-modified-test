package me.cortex.vulkanite.mixin.sodium.chunk;

import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ChunkBuildBuffers.class, remap = false)
public interface VertexFormatAccessor {
    @Accessor
    ChunkVertexType getVertexType();
}
