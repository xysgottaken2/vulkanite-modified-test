package me.cortex.vulkanite.mixin.iris;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap.Entry;
import me.cortex.vulkanite.client.Vulkanite;
import me.cortex.vulkanite.client.rendering.VulkanPipeline;
import me.cortex.vulkanite.compat.IGetRaytracingSource;
import me.cortex.vulkanite.compat.IVGImage;
import me.cortex.vulkanite.compat.RaytracingShaderSet;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.memory.VGImage;
import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.pipeline.CustomTextureManager;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class MixinIrisRenderingPipeline {
    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanite/IrisPipeline");

    @Shadow
    @Final
    private RenderTargets renderTargets;
    @Shadow
    @Final
    private CustomTextureManager customTextureManager;
    @Shadow
    private ShaderStorageBufferHolder shaderStorageBufferHolder;

    @Unique
    private RaytracingShaderSet[] rtShaderPasses;
    @Unique
    private VContext ctx;
    @Unique
    private VulkanPipeline pipeline;
    // Skip the very first renderShadows invocation so that GL composite passes
    // run at least once before the RT shader reads FrameData (which contains
    // resolution_global written by auto_exposure.glsl). On first frame the SSBO
    // is zero-initialized: resolution_global=(0,0) causes getIndex() to map all
    // pixels to column-index only, producing garbage buffer reads -> black world.
    @Unique
    private boolean rtFirstFrameSkipped = false;

    @Unique
    private VGImage[] getCustomTextures() {
        Object2ObjectMap<String, TextureAccess> texturesBinary = customTextureManager.getIrisCustomTextures();
        Object2ObjectMap<String, TextureAccess> texturesPNGs = customTextureManager
                .getCustomTextureIdMap(TextureStage.GBUFFERS_AND_SHADOW);

        List<Entry<String, TextureAccess>> entryList = new ArrayList<>();
        entryList.addAll(texturesBinary.object2ObjectEntrySet());
        entryList.addAll(texturesPNGs.object2ObjectEntrySet());
        entryList.sort(Comparator.comparing(Entry::getKey));

        return entryList.stream()
                .map(entry -> ((IVGImage) entry.getValue()).getVGImage())
                .toArray(VGImage[]::new);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void injectRTShader(ProgramSet set, CallbackInfo ci) {
        try {
            ctx = Vulkanite.INSTANCE.getCtx();
            var passes = ((IGetRaytracingSource) set).getRaytracingSource();
            if (passes != null && passes.length > 0) {
                LOGGER.info("Found {} raytracing shader passes, initializing Vulkan pipeline...", passes.length);
                rtShaderPasses = new RaytracingShaderSet[passes.length];
                for (int i = 0; i < passes.length; i++) {
                    rtShaderPasses[i] = new RaytracingShaderSet(ctx, passes[i]);
                }
                // Must match the shaderpack's actual SSBO indices
                // (set.getPack().getBufferObjects().keySet()).
                // Passing new int[0] here means storageBufferLayout has zero bindings, so the
                // RT shaders'
                // writes to denoiseBuffer/diffuseIlluminationBuffer/frame_data SSBOs never
                // reach real
                // descriptor bindings -> downstream compute passes read stale/zeroed data ->
                // black world.
                int[] ssboIds = set.getPack().getBufferObjects().keySet().toIntArray();
                pipeline = new VulkanPipeline(ctx, Vulkanite.INSTANCE.getAccelerationManager(),
                        rtShaderPasses, ssboIds, getCustomTextures());
                LOGGER.info("Vulkan raytracing pipeline initialized with {} passes, {} SSBO bindings",
                        rtShaderPasses.length, ssboIds.length);
            } else {
                LOGGER.info("No raytracing shaders found in shaderpack, Vulkan pipeline not created");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to initialize Vulkan raytracing pipeline", e);
            pipeline = null;
            rtShaderPasses = null;
        }
    }

    @Inject(method = "beginLevelRendering", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/gl/buffer/ShaderStorageBufferHolder;hasResizedScreen(II)V", shift = At.Shift.AFTER))
    private void afterHasResizedScreen(CallbackInfo ci) {
        if (shaderStorageBufferHolder != null) {
            // hasResizedScreen only rebinds relative buffers (resizeIfRelative →
            // glBindBufferBase). Static buffers (1, 5) and buffers whose Vulkan-
            // shared GL name was replaced by Vulkanite's MixinShaderStorageBuffer
            // are NOT re-bound. Without setupBuffers(), the GL-side indexed SSBO
            // bindings for these buffers point to deleted GL names → composite
            // passes read dead buffers → black output.
            shaderStorageBufferHolder.setupBuffers();
        }
    }

    @Inject(method = "renderShadows", at = @At("TAIL"))
    private void afterRenderShadows(LevelRendererAccessor par1, Camera camera, CameraRenderState cameraRenderState,
            CallbackInfo ci) {
        if (pipeline == null)
            return;
        if (!rtFirstFrameSkipped) {
            rtFirstFrameSkipped = true;
            LOGGER.info("Skipping first RT frame to allow FrameData SSBO initialization");
            return;
        }
        ShaderStorageBuffer[] buffers = new ShaderStorageBuffer[0];
        if (shaderStorageBufferHolder != null) {
            buffers = ((ShaderStorageBufferHolderAccessor) shaderStorageBufferHolder).getBuffers();
        }
        var mainTarget = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        try {
            int blockAtlasGlId = Minecraft.getInstance().getTextureManager()
                    .getTexture(TextureAtlas.LOCATION_BLOCKS).getTexture().iris$getGlId();
            net.irisshaders.iris.pbr.texture.PBRTextureManager.INSTANCE.getOrLoadHolder(blockAtlasGlId);
        } catch (Exception e) {
            LOGGER.warn("Failed to trigger PBR texture load for block atlas", e);
        }
        pipeline.renderPostShadows(mainTarget.width, mainTarget.height, camera, buffers);
    }

    @Inject(method = "destroy", at = @At("TAIL"))
    private void onDestroy(CallbackInfo ci) {
        if (rtShaderPasses != null) {
            ctx.cmd.waitQueueIdle(0);
            for (var pass : rtShaderPasses) {
                pass.delete();
            }
            if (pipeline != null) {
                pipeline.destory();
            }
            rtShaderPasses = null;
            pipeline = null;
        }
    }
}
