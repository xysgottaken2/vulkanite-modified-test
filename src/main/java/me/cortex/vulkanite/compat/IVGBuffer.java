package me.cortex.vulkanite.compat;

import me.cortex.vulkanite.lib.memory.VGBuffer;
import java.util.Optional;

public interface IVGBuffer {
    Optional<VGBuffer> getBuffer();
    void setBuffer(VGBuffer buffer);
}
