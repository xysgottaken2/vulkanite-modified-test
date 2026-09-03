package me.cortex.vulkanite.compat;

/** Internal bridge used to replace an Iris GL resource with a shared GL name. */
public interface IGlResourceIdAccessor {
    int vulkanite$getGlId();

    void vulkanite$setGlId(int id);
}
