package me.cortex.vulkanite.compat;

import net.caffeinemc.mods.sodium.client.util.NativeBuffer;

public record GeometryData(int quadCount, NativeBuffer data) {
}
