package me.cortex.vulkanite.client.rendering;

import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import org.joml.Matrix4f;
import org.joml.Vector4f;

public class Uniforms {

    //Copied from CommonUniforms
    static int isEyeInWater() {
        var sub = Minecraft.getInstance().gameRenderer.mainCamera().getFluidInCamera();
        return switch (sub) {
            case WATER -> 1;
            case LAVA -> 2;
            case POWDER_SNOW -> 3;
            default -> 0;
        };
    }

    //Copied from CelestialUniforms

    static Vector4f getSunPosition() {
        return getCelestialPosition(100.0F);
    }

    static Vector4f getMoonPosition() {
        return getCelestialPosition(-100.0F);
    }

    static Vector4f getCelestialPosition(float y) {
        final float sunPathRotation = -45.0f;

        Vector4f position = new Vector4f(0.0F, y, 0.0F, 0.0F);

        Matrix4f celestial = new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView());

        // Use JOML Matrix4f rotation methods (RotationAxis removed in MC 26.2 Mojang mappings)
        celestial.rotateY((float) Math.toRadians(-90.0F));
        celestial.rotateZ((float) Math.toRadians(sunPathRotation));
        celestial.rotateX((float) Math.toRadians(getSkyAngle() * 360.0F));

        position = celestial.transform(position);

        return position;
    }

    static ClientLevel getWorld() {
        return Minecraft.getInstance().level;
    }

    private static float getSkyAngle() {
        // MC 26.2: getSkyAngle/getSunAngle removed, compute from clock time
        float tickDelta = CapturedRenderingState.INSTANCE.getTickDelta();
        long dayTime = getWorld().getOverworldClockTime();
        return ((float)(dayTime % 24000L) + tickDelta) / 24000.0F;
    }
}
