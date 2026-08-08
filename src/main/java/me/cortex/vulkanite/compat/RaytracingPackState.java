package me.cortex.vulkanite.compat;

public final class RaytracingPackState {
    private static volatile boolean active;

    private RaytracingPackState() {
    }

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean active) {
        RaytracingPackState.active = active;
    }
}
