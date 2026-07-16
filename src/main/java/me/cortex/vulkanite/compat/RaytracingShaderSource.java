package me.cortex.vulkanite.compat;

public class RaytracingShaderSource {
    public record RayHitSource(String close, String any, String intersection) {}

    public final String name;
    public final String raygen;
    public final String[] raymiss;
    public final RayHitSource[] rayhit;
    /** Dispatch dimensions: [W, H, D]. Values &gt; 1 are fixed pixel counts; values in (0,1] are screen-relative multipliers. */
    public final float dispatchW, dispatchH, dispatchD;
    /** Execution group. Passes in the same group run without barriers; a pipeline barrier is inserted between different groups. */
    public final int group;

    public RaytracingShaderSource(String name, String raygen, String[] raymiss, RayHitSource... rayhit) {
        this(name, raygen, raymiss, 1.0f, 1.0f, 1.0f, 0, rayhit);
    }

    public RaytracingShaderSource(String name, String raygen, String[] raymiss,
                                  float dispatchW, float dispatchH, float dispatchD,
                                  int group,
                                  RayHitSource... rayhit) {
        this.name = name;
        this.raygen = raygen;
        this.raymiss = raymiss;
        this.rayhit = rayhit;
        this.dispatchW = dispatchW;
        this.dispatchH = dispatchH;
        this.dispatchD = dispatchD;
        this.group = group;
    }
}
