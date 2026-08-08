package me.cortex.vulkanite.client.rendering.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.cortex.vulkanite.compat.SodiumResultAdapter;
import me.cortex.vulkanite.mixin.minecraft.RenderSetupAccessor;
import me.cortex.vulkanite.mixin.minecraft.RenderSetupTextureBindingAccessor;
import me.cortex.vulkanite.mixin.minecraft.RenderTypeAccessor;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class EntityGeometryCollector {
    public static final EntityGeometryCollector INSTANCE = new EntityGeometryCollector();
    private static final int GEOMETRY_VERTEX_SIZE = 40;
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanite/EntityGeometry");

    private final List<CapturedVertex> vertices = new ArrayList<>();
    private Vec3 cameraPosition = Vec3.ZERO;
    private boolean rayTracingActive;
    private boolean loggedFirstFrame;

    private EntityGeometryCollector() {
    }

    public void beginFrame(Vec3 cameraPosition) {
        vertices.clear();
        this.cameraPosition = cameraPosition;
    }

    public void setRayTracingActive(boolean active) {
        rayTracingActive = active;
        if (!active) {
            vertices.clear();
        }
    }

    public <S> boolean capture(ModelFeatureRenderer.Submit<S> submit) {
        if (!rayTracingActive) {
            return false;
        }
        if (submit.renderType().isOutline() || submit.sheetedDecalPose() != null) {
            return false;
        }

        Identifier texture = textureOf(submit.renderType(), submit.sprite());
        if (texture == null) {
            return false;
        }
        int textureIndex = EntityTextureRegistry.INSTANCE.indexOf(texture);
        if (textureIndex < 0) {
            return false;
        }

        CapturingConsumer capture = new CapturingConsumer(textureIndex, vertices);
        VertexConsumer consumer = submit.sprite() != null ? submit.sprite().wrap(capture) : capture;
        PoseStack poseStack = new PoseStack();
        poseStack.last().set(submit.pose());
        submit.model().setupAnim(submit.state());
        submit.model().renderToBuffer(poseStack, consumer, submit.lightCoords(), submit.overlayCoords(), submit.tintedColor());
        capture.finish();
        return true;
    }

    public EntityGeometryFrame endFrame() {
        int completeVertexCount = vertices.size() - vertices.size() % 4;
        int quadCount = completeVertexCount / 4;
        if (quadCount == 0) {
            vertices.clear();
            return null;
        }

        if (!loggedFirstFrame) {
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float minZ = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            float maxZ = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < completeVertexCount; i++) {
                CapturedVertex vertex = vertices.get(i);
                minX = Math.min(minX, vertex.x + (float) cameraPosition.x);
                minY = Math.min(minY, vertex.y + (float) cameraPosition.y);
                minZ = Math.min(minZ, vertex.z + (float) cameraPosition.z);
                maxX = Math.max(maxX, vertex.x + (float) cameraPosition.x);
                maxY = Math.max(maxY, vertex.y + (float) cameraPosition.y);
                maxZ = Math.max(maxZ, vertex.z + (float) cameraPosition.z);
            }
            int nonDegenerateTriangles = 0;
            for (int quad = 0; quad < quadCount; quad++) {
                List<CapturedVertex> q = vertices.subList(quad * 4, quad * 4 + 4);
                nonDegenerateTriangles += triangleAreaSquared(q.get(0), q.get(1), q.get(2)) > 1.0e-12f ? 1 : 0;
                nonDegenerateTriangles += triangleAreaSquared(q.get(0), q.get(2), q.get(3)) > 1.0e-12f ? 1 : 0;
            }
            LOGGER.info("Captured first RT entity frame: {} quads, {}/{} non-degenerate triangles, {} textures, "
                            + "bounds=[({}, {}, {})..({}, {}, {})]",
                    quadCount, nonDegenerateTriangles, quadCount * 2, EntityTextureRegistry.INSTANCE.size(),
                    minX, minY, minZ, maxX, maxY, maxZ);
            loggedFirstFrame = true;
        }

        NativeBuffer blasVertices = new NativeBuffer(completeVertexCount * 4 * Short.BYTES);
        NativeBuffer geometry = new NativeBuffer(completeVertexCount * GEOMETRY_VERTEX_SIZE);
        ByteBuffer blas = blasVertices.getDirectBuffer();
        ByteBuffer geom = geometry.getDirectBuffer();

        for (int quad = 0; quad < quadCount; quad++) {
            encodeQuad(vertices.subList(quad * 4, quad * 4 + 4), blas, geom, quad);
        }
        vertices.clear();
        return new EntityGeometryFrame(blasVertices, geometry, quadCount,
                (float) cameraPosition.x, (float) cameraPosition.y, (float) cameraPosition.z);
    }

    private static Identifier textureOf(RenderType renderType, TextureAtlasSprite sprite) {
        if (sprite != null) {
            return sprite.atlasLocation();
        }
        RenderSetup setup = ((RenderTypeAccessor) (Object) renderType).vulkanite$getState();
        Map<String, Object> textures = ((RenderSetupAccessor) (Object) setup).vulkanite$getTextures();
        Object binding = textures.get("Sampler0");
        if (binding == null && !textures.isEmpty()) {
            binding = textures.values().iterator().next();
        }
        return binding != null
                ? ((RenderSetupTextureBindingAccessor) binding).vulkanite$getLocation()
                : null;
    }

    private static void encodeQuad(List<CapturedVertex> quad, ByteBuffer blas, ByteBuffer geom, int quadIndex) {
        CapturedVertex v0 = quad.get(0);
        CapturedVertex v1 = quad.get(1);
        CapturedVertex v2 = quad.get(2);
        float[] tangent = tangent(v0, v1, v2);
        float midU = 0.0f;
        float midV = 0.0f;
        for (CapturedVertex vertex : quad) {
            midU += vertex.u;
            midV += vertex.v;
        }
        midU *= 0.25f;
        midV *= 0.25f;

        for (int i = 0; i < 4; i++) {
            CapturedVertex vertex = quad.get(i);
            int blasOffset = (quadIndex * 4 + i) * 4 * Short.BYTES;
            blas.putShort(blasOffset, (short) SodiumResultAdapter.fromFloat(vertex.x));
            blas.putShort(blasOffset + 2, (short) SodiumResultAdapter.fromFloat(vertex.y));
            blas.putShort(blasOffset + 4, (short) SodiumResultAdapter.fromFloat(vertex.z));
            blas.putShort(blasOffset + 6, (short) 0);

            int offset = (quadIndex * 4 + i) * GEOMETRY_VERTEX_SIZE;
            geom.putShort(offset, encodeGeometryPosition(vertex.x));
            geom.putShort(offset + 2, encodeGeometryPosition(vertex.y));
            geom.putShort(offset + 4, encodeGeometryPosition(vertex.z));
            geom.put(offset + 6, (byte) 0);
            geom.put(offset + 7, (byte) 0);
            geom.put(offset + 8, (byte) vertex.r);
            geom.put(offset + 9, (byte) vertex.g);
            geom.put(offset + 10, (byte) vertex.b);
            geom.put(offset + 11, (byte) vertex.a);
            geom.putShort(offset + 12, (short) Math.round(vertex.u * 65535.0f));
            geom.putShort(offset + 14, (short) Math.round(vertex.v * 65535.0f));
            geom.putShort(offset + 16, (short) vertex.lightU);
            geom.putShort(offset + 18, (short) vertex.lightV);
            geom.putShort(offset + 20, (short) Math.round(midU * 65535.0f));
            geom.putShort(offset + 22, (short) Math.round(midV * 65535.0f));
            geom.put(offset + 24, snorm8(tangent[0]));
            geom.put(offset + 25, snorm8(tangent[1]));
            geom.put(offset + 26, snorm8(tangent[2]));
            geom.put(offset + 27, snorm8(tangent[3]));
            geom.put(offset + 28, snorm8(vertex.nx));
            geom.put(offset + 29, snorm8(vertex.ny));
            geom.put(offset + 30, snorm8(vertex.nz));
            geom.put(offset + 31, (byte) 0);
            geom.putShort(offset + 32, (short) -2);
            geom.putShort(offset + 34, (short) (vertex.textureIndex + 1));
            geom.putInt(offset + 36, 0);
        }
    }

    private static short encodeGeometryPosition(float value) {
        return (short) Math.round((value + 8.0f) * 2048.0f);
    }

    private static byte snorm8(float value) {
        return (byte) Math.max(-127, Math.min(127, Math.round(value * 127.0f)));
    }

    private static float triangleAreaSquared(CapturedVertex a, CapturedVertex b, CapturedVertex c) {
        float abx = b.x - a.x;
        float aby = b.y - a.y;
        float abz = b.z - a.z;
        float acx = c.x - a.x;
        float acy = c.y - a.y;
        float acz = c.z - a.z;
        float cx = aby * acz - abz * acy;
        float cy = abz * acx - abx * acz;
        float cz = abx * acy - aby * acx;
        return cx * cx + cy * cy + cz * cz;
    }

    private static float[] tangent(CapturedVertex a, CapturedVertex b, CapturedVertex c) {
        float e1x = b.x - a.x, e1y = b.y - a.y, e1z = b.z - a.z;
        float e2x = c.x - a.x, e2y = c.y - a.y, e2z = c.z - a.z;
        float du1 = b.u - a.u, dv1 = b.v - a.v;
        float du2 = c.u - a.u, dv2 = c.v - a.v;
        float denominator = du1 * dv2 - du2 * dv1;
        if (Math.abs(denominator) < 1.0e-8f) {
            return new float[] { 1.0f, 0.0f, 0.0f, 1.0f };
        }
        float f = 1.0f / denominator;
        float tx = f * (dv2 * e1x - dv1 * e2x);
        float ty = f * (dv2 * e1y - dv1 * e2y);
        float tz = f * (dv2 * e1z - dv1 * e2z);
        float length = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (length > 1.0e-8f) {
            tx /= length;
            ty /= length;
            tz /= length;
        }
        float bx = f * (-du2 * e1x + du1 * e2x);
        float by = f * (-du2 * e1y + du1 * e2y);
        float bz = f * (-du2 * e1z + du1 * e2z);
        float px = ty * a.nz - tz * a.ny;
        float py = tz * a.nx - tx * a.nz;
        float pz = tx * a.ny - ty * a.nx;
        float handedness = bx * px + by * py + bz * pz < 0.0f ? -1.0f : 1.0f;
        return new float[] { tx, ty, tz, handedness };
    }

    private static final class CapturingConsumer implements VertexConsumer {
        private final int textureIndex;
        private final List<CapturedVertex> output;
        private CapturedVertex current;

        private CapturingConsumer(int textureIndex, List<CapturedVertex> output) {
            this.textureIndex = textureIndex;
            this.output = output;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            finish();
            current = new CapturedVertex();
            current.x = x;
            current.y = y;
            current.z = z;
            current.textureIndex = textureIndex;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            current.r = r;
            current.g = g;
            current.b = b;
            current.a = a;
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            return setColor(color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF, color >>> 24);
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            current.u = u;
            current.v = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            current.lightU = u;
            current.lightV = v;
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            current.nx = x;
            current.ny = y;
            current.nz = z;
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }

        private void finish() {
            if (current != null) {
                output.add(current);
                current = null;
            }
        }
    }

    private static final class CapturedVertex {
        float x, y, z;
        float u, v;
        float nx, ny, nz;
        int r = 255, g = 255, b = 255, a = 255;
        int lightU, lightV;
        int textureIndex;
    }
}
