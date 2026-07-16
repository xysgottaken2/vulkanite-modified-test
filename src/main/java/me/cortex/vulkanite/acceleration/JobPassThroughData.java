package me.cortex.vulkanite.acceleration;

import me.cortex.vulkanite.lib.memory.VBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;

import java.util.List;

public record JobPassThroughData(RenderSection section, long time, List<VBuffer> geometryBuffers) {

}
