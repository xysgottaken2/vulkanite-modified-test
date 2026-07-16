package me.cortex.vulkanite.compat;

import me.cortex.vulkanite.lib.memory.VGImage;

public interface IRenderTargetVkGetter {
    VGImage getMain();
    VGImage getAlt();
    /** Convert this render target's textures from plain GL to Vulkan-shared. */
    void vulkanite$makeShared();
    /** Check whether this render target's textures are already Vulkan-shared. */
    boolean vulkanite$isShared();
}
