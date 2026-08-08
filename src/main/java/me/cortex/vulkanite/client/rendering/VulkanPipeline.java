package me.cortex.vulkanite.client.rendering;

import me.cortex.vulkanite.acceleration.AccelerationManager;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.client.rendering.entity.EntityTextureRegistry;
import me.cortex.vulkanite.compat.IVGBuffer;
import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.compat.RaytracingShaderSet;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.cmd.VCommandPool;
import me.cortex.vulkanite.lib.descriptors.DescriptorSetLayoutBuilder;
import me.cortex.vulkanite.lib.descriptors.DescriptorUpdateBuilder;
import me.cortex.vulkanite.lib.descriptors.VDescriptorPool;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
import me.cortex.vulkanite.lib.memory.VGImage;
import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;
import me.cortex.vulkanite.lib.other.VSampler;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;
import me.cortex.vulkanite.lib.pipeline.RaytracePipelineBuilder;
import me.cortex.vulkanite.lib.pipeline.VRaytracePipeline;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.pbr.texture.PBRTextureHolder;
import net.irisshaders.iris.pbr.texture.PBRTextureManager;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;

import static org.lwjgl.opengl.EXTSemaphore.GL_LAYOUT_GENERAL_EXT;
import static org.lwjgl.opengl.GL11C.glFinish;
import static org.lwjgl.opengl.GL11C.glFlush;
import static org.lwjgl.opengl.GL11C.glGetError;
import static org.lwjgl.opengl.GL11C.GL_NO_ERROR;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.VK10.*;

public class VulkanPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanite/RTPipeline");

    private final VContext ctx;
    private final AccelerationManager accelerationManager;
    private final VCommandPool singleUsePool;

    private VRaytracePipeline[] raytracePipelines;
    private RaytracingShaderSet[] rtPasses;
    private VDescriptorSetLayout commonLayout;
    private VDescriptorSetLayout customtexLayout;
    private VDescriptorSetLayout storageBufferLayout;

    private VDescriptorPool commonDescriptorPool;
    private VDescriptorPool customtexDescriptorPool;
    private VDescriptorPool storageBufferDescriptorPool;

    private final VSampler sampler;
    private final VSampler ctexSampler;

    private final SharedImageViewTracker[] customTextureViews;
    private final SharedImageViewTracker[] entityTextureViews;
    private final SharedImageViewTracker blockAtlasView;
    private final SharedImageViewTracker blockAtlasNormalView;
    private final SharedImageViewTracker blockAtlasSpecularView;

    private final VImage fallbackImage;
    private final VImageView fallbackImageView;
    private final VBuffer fallbackMotionBuffer;
    private final FrameUniforms uniforms;

    private int fidx;
    private int frameId;
    private int frameCounter;
    private boolean loggedSsboBindingOnce = false;
    private boolean loggedEntityTextures;
    private VSemaphore previousSemaphore;

    public static final Identifier LOCATION_BLOCKS = TextureAtlas.LOCATION_BLOCKS;

    public VulkanPipeline(VContext ctx, AccelerationManager accelerationManager, RaytracingShaderSet[] passes,
            int[] ssboIds, VGImage[] customTextures) {
        this.ctx = ctx;
        this.accelerationManager = accelerationManager;
        this.singleUsePool = ctx.cmd.createSingleUsePool();
        this.uniforms = new FrameUniforms(ctx);

        this.customTextureViews = new SharedImageViewTracker[customTextures.length];
        for (int i = 0; i < customTextures.length; i++) {
            int index = i;
            this.customTextureViews[i] = new SharedImageViewTracker(ctx, () -> customTextures[index]);
        }

        this.fallbackImage = ctx.memory.createImage2D(4, 4, 1, VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        this.fallbackImageView = new VImageView(ctx, fallbackImage);
        this.fallbackMotionBuffer = ctx.memory.createBuffer(4L * Short.BYTES,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                0, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
        long fallbackMotionPtr = fallbackMotionBuffer.map();
        MemoryUtil.memSet(fallbackMotionPtr, 0, 4L * Short.BYTES);
        fallbackMotionBuffer.unmap();
        fallbackMotionBuffer.flush();

        this.entityTextureViews = new SharedImageViewTracker[EntityTextureRegistry.CAPACITY];
        for (int i = 0; i < entityTextureViews.length; i++) {
            int index = i;
            entityTextureViews[i] = new SharedImageViewTracker(ctx, () -> getEntityTexture(index));
        }

        this.blockAtlasView = new SharedImageViewTracker(ctx, () -> {
            AbstractTexture atlas = Minecraft.getInstance().getTextureManager().getTexture(LOCATION_BLOCKS);
            return atlas != null ? ((IVGImage) atlas).getVGImage().map(v -> (VImage) v).orElse((VImage) fallbackImage)
                    : (VImage) fallbackImage;
        });

        this.blockAtlasNormalView = new SharedImageViewTracker(ctx, () -> {
            AbstractTexture atlas = Minecraft.getInstance().getTextureManager().getTexture(LOCATION_BLOCKS);
            return atlas != null ? getPbrAtlas(atlas, h -> ((IVGImage) h.normalTexture()).getVGImage())
                    : (VImage) fallbackImage;
        });

        this.blockAtlasSpecularView = new SharedImageViewTracker(ctx, () -> {
            AbstractTexture atlas = Minecraft.getInstance().getTextureManager().getTexture(LOCATION_BLOCKS);
            return atlas != null ? getPbrAtlas(atlas, h -> ((IVGImage) h.specularTexture()).getVGImage())
                    : (VImage) fallbackImage;
        });

        transitionFallbackImageLayout();

        this.sampler = createSampler(VK_FILTER_NEAREST, 1.0f);
        this.ctexSampler = createSampler(VK_FILTER_LINEAR, 1.0f);

        buildLayoutsAndPipelines(ssboIds, passes);
    }

    public void renderPostShadows(int width, int height, Camera camera, ShaderStorageBuffer[] ssbos) {
        if (ctx.sync.isDeviceLost())
            return;
        this.singleUsePool.doReleases();

        final int[] signalBuffers = collectSharedSsbos(ssbos);
        final SharedTextures sharedTextures = collectSharedTextures();
        final int[] signalTextures = sharedTextures.ids();
        final int[] signalTexLayouts = sharedTextures.layouts();

        if (++frameCounter % 60 == 0) {
            LOGGER.debug("renderPostShadows frame={} fidx={} {}x{} ssboGlIds=[{}]",
                    frameCounter, fidx, width, height,
                    java.util.Arrays.toString(signalBuffers));
        }

        var in = ctx.sync.createSharedBinarySemaphore();
        in.glSignal(signalBuffers, signalTextures, signalTexLayouts);
        glFlush();

        int glErr = glGetError();
        if (glErr != GL_NO_ERROR && frameCounter % 60 == 0) {
            LOGGER.warn("GL error {} after glSignal at frame={}", glErr, frameCounter);
        }

        var tlasLink = ctx.sync.createBinarySemaphore();
        var tlas = accelerationManager.buildTLAS(in, tlasLink);
        if (tlas == null) {
            glFinish();
            tlasLink.free();
            in.free();
            return;
        }

        VBuffer uboBuffer = uniforms.update(fidx, camera, frameId++);

        long commonSet = commonDescriptorPool.get(fidx);
        long ctexSet = customtexDescriptorPool.get(fidx);
        long ssboSet = storageBufferDescriptorPool.get(fidx);
        updateDescriptorSets(commonSet, ctexSet, ssboSet, uboBuffer, tlas, ssbos);

        var cmd = singleUsePool.createCommandBuffer();
        cmd.begin(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        recordPipelineBarriers(cmd);
        recordTraceRays(cmd, commonSet, ctexSet, ssboSet, width, height);
        cmd.end();

        var out = ctx.sync.createSharedBinarySemaphore();
        var fence = ctx.sync.createFence();
        ctx.cmd.submit(0, new VCmdBuff[] { cmd }, new VSemaphore[] { tlasLink },
                new int[] { VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR }, new VSemaphore[] { out }, fence);

        var semCapture = previousSemaphore;
        previousSemaphore = out;
        ctx.sync.addCallback(fence, () -> {
            tlasLink.free();
            in.free();
            cmd.enqueueFree();
            fence.free();
            if (semCapture != null)
                semCapture.free();
        });

        out.glWait(signalBuffers, signalTextures, signalTexLayouts);
        glFlush();

        fidx = (fidx + 1) % 10;
    }

    public void destory() {
        for (var pass : raytracePipelines)
            pass.free();
        commonLayout.free();
        customtexLayout.free();
        storageBufferLayout.free();
        commonDescriptorPool.free();
        customtexDescriptorPool.free();
        storageBufferDescriptorPool.free();
        ctx.sync.checkFences();
        singleUsePool.doReleases();
        singleUsePool.free();
        if (previousSemaphore != null)
            previousSemaphore.free();
        for (SharedImageViewTracker customTexView : customTextureViews)
            customTexView.free();
        for (SharedImageViewTracker entityTextureView : entityTextureViews)
            entityTextureView.free();
        blockAtlasView.free();
        blockAtlasNormalView.free();
        blockAtlasSpecularView.free();
        fallbackImageView.free();
        fallbackImage.free();
        fallbackMotionBuffer.free();
        sampler.free();
        ctexSampler.free();
        uniforms.free();
    }

    private static void applyImageBarrier(VkImageMemoryBarrier barrier, VImage image, int targetLayout,
            int targetAccess) {
        barrier.sType$Default()
                .sType$Default()
                .image(image.image())
                .oldLayout(VK_IMAGE_LAYOUT_GENERAL)
                .newLayout(targetLayout)
                .srcAccessMask(0)
                .dstAccessMask(targetAccess)
                .subresourceRange(e -> e.levelCount(image.mipLayers).layerCount(1)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT));
    }

    private VImage getPbrAtlas(AbstractTexture atlas,
            java.util.function.Function<PBRTextureHolder, Optional<VGImage>> mapper) {
        int atlasGlId = atlas.getTexture().iris$getGlId();
        var holder = PBRTextureManager.INSTANCE.getOrLoadHolder(atlasGlId);
        return holder != null ? mapper.apply(holder).map(v -> (VImage) v).orElse(fallbackImage) : fallbackImage;
    }

    private VImage getEntityTexture(int index) {
        Identifier location = EntityTextureRegistry.INSTANCE.get(index);
        if (location == null) {
            return null;
        }
        AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(location);
        return texture != null ? ((IVGImage) texture).getVGImage().map(image -> (VImage) image).orElse(null) : null;
    }

    private void transitionFallbackImageLayout() {
        try (var stack = stackPush()) {
            var cmd = singleUsePool.createCommandBuffer();
            cmd.begin(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            var barriers = VkImageMemoryBarrier.calloc(1, stack);
            applyImageBarrier(barriers.get(0), fallbackImage, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_MEMORY_READ_BIT);
            vkCmdPipelineBarrier(cmd.buffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
                    null, null, barriers);
            cmd.end();
            ctx.cmd.submit(0, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cmd)));
            Vulkanite.INSTANCE.addSyncedCallback(cmd::enqueueFree);
        }
    }

    private VSampler createSampler(int filter, float anisotropy) {
        return new VSampler(ctx, a -> a.magFilter(filter)
                .minFilter(filter)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .compareOp(VK_COMPARE_OP_NEVER)
                .minLod(0.0f)
                .maxLod(VK_LOD_CLAMP_NONE)
                .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK)
                .maxAnisotropy(anisotropy));
    }

    private void buildLayoutsAndPipelines(int[] ssboIds, RaytracingShaderSet[] passes) {
        commonLayout = new DescriptorSetLayoutBuilder()
                .binding(0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_SHADER_STAGE_ALL)
                .binding(1, VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, VK_SHADER_STAGE_ALL)
                .binding(2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_ALL)
                .binding(3, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_ALL)
                .binding(4, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_ALL)
                .binding(5, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_ALL)
                .binding(6, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, EntityTextureRegistry.CAPACITY,
                        VK_SHADER_STAGE_ALL)
                .build(ctx);

        DescriptorSetLayoutBuilder ctexLayoutBuilder = new DescriptorSetLayoutBuilder();
        for (int i = 0; i < customTextureViews.length; i++) {
            ctexLayoutBuilder.binding(i, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_ALL);
        }
        customtexLayout = ctexLayoutBuilder.build(ctx);

        DescriptorSetLayoutBuilder ssboLayoutBuilder = new DescriptorSetLayoutBuilder();
        for (int id : ssboIds) {
            ssboLayoutBuilder.binding(id, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_SHADER_STAGE_ALL);
        }
        storageBufferLayout = ssboLayoutBuilder.build(ctx);

        commonDescriptorPool = new VDescriptorPool(ctx, VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT, 10,
                EntityTextureRegistry.CAPACITY, commonLayout.types);
        commonDescriptorPool.allocateSets(commonLayout);
        customtexDescriptorPool = new VDescriptorPool(ctx, VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT, 10,
                customtexLayout.types);
        customtexDescriptorPool.allocateSets(customtexLayout);
        storageBufferDescriptorPool = new VDescriptorPool(ctx, VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT, 10,
                storageBufferLayout.types);
        storageBufferDescriptorPool.allocateSets(storageBufferLayout);

        this.rtPasses = passes;
        raytracePipelines = new VRaytracePipeline[passes.length];
        for (int i = 0; i < passes.length; i++) {
            var builder = new RaytracePipelineBuilder()
                    .addLayout(commonLayout)
                    .addLayout(accelerationManager.getGeometryLayout())
                    .addLayout(customtexLayout)
                    .addLayout(storageBufferLayout);
            passes[i].apply(builder);
            raytracePipelines[i] = builder.build(ctx, 1);
        }
    }

    private int[] collectSharedSsbos(ShaderStorageBuffer[] ssbos) {
        final int[] sharedSsboIds = new int[ssbos.length];
        final int[] count = { 0 };
        for (ShaderStorageBuffer ssbo : ssbos) {
            ((IVGBuffer) ssbo).getBuffer().ifPresent(buf -> sharedSsboIds[count[0]++] = buf.glId);
        }
        return Arrays.copyOf(sharedSsboIds, count[0]);
    }

    private SharedTextures collectSharedTextures() {
        ArrayList<Integer> texIds = new ArrayList<>();
        for (SharedImageViewTracker view : new SharedImageViewTracker[] { blockAtlasView, blockAtlasNormalView,
                blockAtlasSpecularView }) {
            VImage img = view.getImage();
            if (img instanceof VGImage vg && !texIds.contains(vg.glId)) {
                texIds.add(vg.glId);
            }
        }
        int entityTextureCount = EntityTextureRegistry.INSTANCE.size();
        int sharedEntityTextureCount = 0;
        for (int i = 0; i < entityTextureCount; i++) {
            VImage image = entityTextureViews[i].getImage();
            if (image instanceof VGImage vg && !texIds.contains(vg.glId)) {
                texIds.add(vg.glId);
                sharedEntityTextureCount++;
            }
        }
        if (!loggedEntityTextures && entityTextureCount > 0) {
            LOGGER.info("Entity RT textures: {} registered, {} Vulkan-shared",
                    entityTextureCount, sharedEntityTextureCount);
            for (int i = 0; i < Math.min(entityTextureCount, 8); i++) {
                LOGGER.info("  entity texture [{}] {} shared={}", i, EntityTextureRegistry.INSTANCE.get(i),
                        entityTextureViews[i].getImage() instanceof VGImage);
            }
            loggedEntityTextures = true;
        }
        int[] ids = texIds.stream().mapToInt(x -> x).toArray();
        int[] layouts = new int[ids.length];
        Arrays.fill(layouts, GL_LAYOUT_GENERAL_EXT);
        return new SharedTextures(ids, layouts);
    }

    private void updateDescriptorSets(long commonSet, long ctexSet, long ssboSet, VBuffer uboBuffer,
            VAccelerationStructure tlas, ShaderStorageBuffer[] ssbos) {
        VImageView[] entityViews = new VImageView[entityTextureViews.length];
        for (int i = 0; i < entityViews.length; i++) {
            entityViews[i] = entityTextureViews[i].getView();
        }

        VBuffer entityMotionBuffer = accelerationManager.getEntityMotionBuffer();
        new DescriptorUpdateBuilder(ctx, 7, fallbackImageView)
                .set(commonSet)
                .buffer(2, entityMotionBuffer != null ? entityMotionBuffer : fallbackMotionBuffer)
                .uniform(0, uboBuffer)
                .acceleration(1, tlas) // 此时方法重载完美适配！
                .imageSampler(3, blockAtlasView.getView(), sampler)
                .imageSampler(4, blockAtlasNormalView.getView(), sampler)
                .imageSampler(5, blockAtlasSpecularView.getView(), sampler)
                .imageSamplerArray(6, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, entityViews, sampler)
                .apply();

        final var ctexUpdater = new DescriptorUpdateBuilder(ctx, customTextureViews.length, fallbackImageView)
                .set(ctexSet);
        for (int i = 0; i < customTextureViews.length; i++) {
            ctexUpdater.imageSampler(i, customTextureViews[i].getView(), ctexSampler);
        }
        ctexUpdater.apply();

        final var ssboUpdater = new DescriptorUpdateBuilder(ctx, ssbos.length, fallbackImageView).set(ssboSet);
        final int[] boundCount = { 0 };
        // Track VkBuffer handles to diagnose whether descriptor updates
        // actually switch to new buffers after resize
        StringBuilder ssboVkHandles = (frameCounter % 60 == 0) ? new StringBuilder() : null;
        for (ShaderStorageBuffer ssbo : ssbos) {
            ((IVGBuffer) ssbo).getBuffer().ifPresentOrElse(buf -> {
                ssboUpdater.buffer(ssbo.getIndex(), buf);
                boundCount[0]++;
                if (ssboVkHandles != null) {
                    ssboVkHandles.append(ssbo.getIndex()).append("=0x")
                            .append(Long.toHexString(buf.buffer())).append(" ");
                }
            }, () -> {
                if (!loggedSsboBindingOnce) {
                    LOGGER.warn("SSBO index={} has no Vulkan-shared buffer, RT writes to this binding are lost",
                            ssbo.getIndex());
                }
            });
        }
        if (!loggedSsboBindingOnce) {
            LOGGER.debug("renderPostShadows SSBO binding: {}/{} buffers bound to Vulkan descriptor set", boundCount[0],
                    ssbos.length);
            for (ShaderStorageBuffer ssbo : ssbos) {
                LOGGER.info("  ssbo index={} glId={} vkShared={}", ssbo.getIndex(), ssbo.getId(),
                        ((IVGBuffer) ssbo).getBuffer() != null);
            }
            loggedSsboBindingOnce = true;
        }
        if (ssboVkHandles != null) {
            LOGGER.debug("renderPostShadows frame={} ssbo VkBuffer handles: {}", frameCounter,
                    ssboVkHandles.toString());
        }
        ssboUpdater.apply();
    }

    private void recordPipelineBarriers(VCmdBuff cmd) {
        try (var stack = stackPush()) {
            int entityTextureCount = EntityTextureRegistry.INSTANCE.size();
            var barriers = VkImageMemoryBarrier.calloc(3 + customTextureViews.length + entityTextureCount, stack);
            applySharedImageBarrier(barriers, blockAtlasView.getImage());
            var image = blockAtlasNormalView.getImage();
            applySharedImageBarrier(barriers, image);
            image = blockAtlasSpecularView.getImage();
            applySharedImageBarrier(barriers, image);
            for (SharedImageViewTracker customtexView : customTextureViews) {
                applySharedImageBarrier(barriers, customtexView.getImage());
            }
            for (int i = 0; i < entityTextureCount; i++) {
                applySharedImageBarrier(barriers, entityTextureViews[i].getImage());
            }
            barriers.limit(barriers.position());
            barriers.rewind();
            vkCmdPipelineBarrier(cmd.buffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR, 0, null, null, barriers);
        }
    }

    private static void applySharedImageBarrier(VkImageMemoryBarrier.Buffer barriers, VImage image) {
        if (image instanceof VGImage) {
            applyImageBarrier(barriers.get(), image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_SHADER_READ_BIT);
        }
    }

    private record SharedTextures(int[] ids, int[] layouts) {
    }

    private void recordTraceRays(VCmdBuff cmd, long commonSet, long ctexSet, long ssboSet, int width, int height) {
        int prevGroup = Integer.MIN_VALUE;
        try (var stack = stackPush()) {
            var memBarrier = VkMemoryBarrier.calloc(1, stack)
                    .sType$Default()
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);

            for (int i = 0; i < raytracePipelines.length; i++) {
                var pass = rtPasses[i];
                if (i > 0 && pass.group != prevGroup) {
                    vkCmdPipelineBarrier(cmd.buffer,
                            VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                            VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR,
                            0, memBarrier, null, null);
                }
                prevGroup = pass.group;

                int dw = dispatchDim(pass.dispatchW, width);
                int dh = dispatchDim(pass.dispatchH, height);
                int dd = Math.max(1, (int) pass.dispatchD);
                raytracePipelines[i].bind(cmd);
                raytracePipelines[i].bindDSet(cmd, commonSet, accelerationManager.getGeometrySet(), ctexSet, ssboSet);
                raytracePipelines[i].trace(cmd, dw, dh, dd);
            }
        }
    }

    private static int dispatchDim(float val, int screenSize) {
        return val > 1.0f ? (int) val : Math.max(1, (int) (screenSize * val));
    }

    private static class FrameUniforms {
        // corners[4](4×16B) + viewInverse(64B) + frameId(4B) + flags(4B) +
        // world_type(4B)
        private static final int UBO_SIZE = 256;
        private final VBuffer[] buffers = new VBuffer[10];

        public FrameUniforms(VContext ctx) {
            for (int i = 0; i < 10; i++) {
                buffers[i] = ctx.memory.createBuffer(UBO_SIZE,
                        VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                        0, VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
            }
        }

        public VBuffer update(int fidx, Camera camera, int frameId) {
            VBuffer buf = buffers[fidx];
            long ptr = buf.map();
            MemoryUtil.memSet(ptr, 0, UBO_SIZE);

            ByteBuffer bb = MemoryUtil.memByteBuffer(ptr, UBO_SIZE);
            Vector3f tmpv3 = new Vector3f();
            Matrix4f invProjMatrix = new Matrix4f();
            Matrix4f invViewMatrix = new Matrix4f();

            CapturedRenderingState.INSTANCE.getGbufferProjection().invert(invProjMatrix);
            new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView())
                    .translate(camera.position().toVector3f().negate()).invert(invViewMatrix);

            invProjMatrix.transformProject(-1, -1, 0, 1, tmpv3).get(bb);
            invProjMatrix.transformProject(+1, -1, 0, 1, tmpv3).get(4 * Float.BYTES, bb);
            invProjMatrix.transformProject(-1, +1, 0, 1, tmpv3).get(8 * Float.BYTES, bb);
            invProjMatrix.transformProject(+1, +1, 0, 1, tmpv3).get(12 * Float.BYTES, bb);
            invViewMatrix.get(Float.BYTES * 16, bb);

            // Sun/moon position removed — obtained from Iris FrameData SSBO
            // (lightDir_global)
            bb.putInt(Float.BYTES * 32, frameId);

            int flags = Uniforms.isEyeInWater() & 3;
            flags += Uniforms.getWorld().dimensionType().hasSkyLight() ? 4 : 0;

            bb.putInt(Float.BYTES * 33, flags);
            bb.putInt(Float.BYTES * 34, switch (net.irisshaders.iris.Iris.getCurrentDimension().getName()) {
                case "the_end" -> 2;
                case "the_nether" -> 1;
                default -> 0; // overworld (and custom dims default to overworld)
            });

            buf.unmap();
            buf.flush();
            return buf;
        }

        public void free() {
            for (VBuffer buf : buffers) {
                if (buf != null)
                    buf.free();
            }
        }
    }
}
