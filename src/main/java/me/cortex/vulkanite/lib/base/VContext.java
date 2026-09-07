package me.cortex.vulkanite.lib.base;

import me.cortex.vulkanite.lib.cmd.CommandManager;
import me.cortex.vulkanite.lib.other.sync.SyncManager;
import me.cortex.vulkanite.lib.memory.MemoryManager;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;

public class VContext {
    public final VkDevice device;

    /**
     * The instance and physical device this context's {@link #device} was created from. NGX (DLSS)
     * init requires all three handles plus the device proc address, and they are otherwise only
     * reachable through LWJGL's back-references on {@code VkDevice}.
     */
    public final VkInstance instance;
    public final VkPhysicalDevice physicalDevice;

    public volatile boolean deviceLost = false;

    public final MemoryManager memory;
    public final SyncManager sync;
    public final CommandManager cmd;
    public final DeviceProperties properties;
    public VContext(VkInstance instance, VkPhysicalDevice physicalDevice, VkDevice device, int queueCount,
            boolean hasDeviceAddresses) {
        this.instance = instance;
        this.physicalDevice = physicalDevice;
        this.device = device;
        memory = new MemoryManager(device, hasDeviceAddresses);
        sync = new SyncManager(device);
        cmd = new CommandManager(device, queueCount);
        properties = new DeviceProperties(device);
    }

    public void cleanup() {
        properties.cleanup();
    }
}
