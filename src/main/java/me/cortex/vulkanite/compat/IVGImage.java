package me.cortex.vulkanite.compat;

import me.cortex.vulkanite.lib.memory.VGImage;
import java.util.Optional;

public interface IVGImage {
    Optional<VGImage> getVGImage();
    void setVGImage(VGImage image);
}
