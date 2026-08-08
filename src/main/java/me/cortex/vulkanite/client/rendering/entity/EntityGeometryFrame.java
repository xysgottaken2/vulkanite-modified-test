package me.cortex.vulkanite.client.rendering.entity;

import net.caffeinemc.mods.sodium.client.util.NativeBuffer;

public record EntityGeometryFrame(NativeBuffer vertices, NativeBuffer geometry, NativeBuffer motion, int quadCount,
        float originX, float originY, float originZ) {
    public void free() {
        vertices.free();
        geometry.free();
        motion.free();
    }
}
