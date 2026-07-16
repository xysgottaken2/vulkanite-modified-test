package me.cortex.vulkanite.acceleration;

//Multithreaded acceleration manager, builds blas's in a separate queue,
// then memory copies over to main, while doing compaction

import me.cortex.vulkanite.compat.IAccelerationBuildResult;
import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.lib.cmd.VCmdBuff;
import me.cortex.vulkanite.lib.cmd.VCommandPool;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.memory.VBuffer;
// import me.cortex.vulkanite.lib.other.VQueryPool;
import me.cortex.vulkanite.lib.other.sync.VFence;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRBufferDeviceAddress.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

public class AccelerationBlasBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanite/BlasBuilder");
    private final VContext context;

    private record BLASTriangleData(int quadCount, NativeBuffer geometry, int geometryFlags) {
    }

    // Raw vertex data (CPU NativeBuffer copy) — uploaded to GPU inside background
    // worker thread only
    private record BLASBuildJob(List<BLASTriangleData> geometries, List<NativeBuffer> rawVertexData,
            RenderSection section, long submitTime) {
    }

    public record BLASBuildResult(VAccelerationStructure structure, JobPassThroughData data) {
    }

    public record BLASBatchResult(List<BLASBuildResult> results, VSemaphore semaphore) {
    }

    private final Thread worker;
    private final int asyncQueue;
    private final Consumer<BLASBatchResult> resultConsumer;
    private final VCommandPool sinlgeUsePool;

    // private final VQueryPool queryPool;

    private final Semaphore awaitingJobBatchess = new Semaphore(0);
    private final ConcurrentLinkedDeque<List<BLASBuildJob>> batchedJobs = new ConcurrentLinkedDeque<>();
    private volatile boolean running = true;

    public AccelerationBlasBuilder(VContext context, int asyncQueue, Consumer<BLASBatchResult> resultConsumer) {
        this.sinlgeUsePool = context.cmd.createSingleUsePool();
        // this.queryPool = new VQueryPool(context.device, 10000,
        // VK_QUERY_TYPE_ACCELERATION_STRUCTURE_COMPACTED_SIZE_KHR);
        this.context = context;
        this.asyncQueue = asyncQueue;
        this.resultConsumer = resultConsumer;
        worker = new Thread(this::run);
        worker.setName("Acceleration blas worker");
        worker.start();
    }

    public void shutdown() {
        running = false;
        worker.interrupt();
    }

    private void run() {
        MemoryStack bigStack = MemoryStack.create(20_000_000);

        List<BLASBuildJob> jobs = new ArrayList<>();
        while (running) {
            {
                jobs.clear();
                try {
                    awaitingJobBatchess.acquire();
                } catch (InterruptedException e) {
                    break;
                }
                int i = -1;
                while (!this.batchedJobs.isEmpty()) {
                    i++;
                    jobs.addAll(this.batchedJobs.poll());
                }
                if (i > 0) {
                    try {
                        awaitingJobBatchess.acquire(i);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            if (!running)
                break;
            sinlgeUsePool.doReleases();
            if (jobs.size() > 100) {
                LOGGER.warn("BLAS build backlog: {} jobs queued in a single batch", jobs.size());
            }

            try (var stack = bigStack.push()) {
                var buildInfos = VkAccelerationStructureBuildGeometryInfoKHR.calloc(jobs.size(), stack);
                PointerBuffer buildRanges = stack.mallocPointer(jobs.size());
                LongBuffer pAccelerationStructures = stack.mallocLong(jobs.size());

                List<VBuffer> buffersToFree = new ArrayList<>(jobs.size() * 2);
                var scratchBuffers = new VBuffer[jobs.size()];
                var accelerationStructures = new VAccelerationStructure[jobs.size()];
                // Per-job GPU vertex buffers for shader access, uploaded alongside geometry
                List<List<VBuffer>> jobVertexBuffers = new ArrayList<>(jobs.size());

                var uploadBuildCmd = sinlgeUsePool.createCommandBuffer();
                uploadBuildCmd.begin(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

                int i = -1;
                for (var job : jobs) {
                    i++;
                    var brs = VkAccelerationStructureBuildRangeInfoKHR.calloc(job.geometries().size(), stack);
                    var geometryInfos = VkAccelerationStructureGeometryKHR.calloc(job.geometries().size(), stack);
                    var maxPrims = stack.callocInt(job.geometries().size());
                    buildRanges.put(brs);

                    // Upload raw vertex data for shader access (geometry lookup in RT shaders)
                    List<VBuffer> vertexBuffers = new ArrayList<>(job.rawVertexData().size());
                    for (NativeBuffer raw : job.rawVertexData()) {
                        var vbuf = context.memory.createBuffer(raw.getLength(),
                                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                        | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                        uploadBuildCmd.encodeDataUpload(context.memory,
                                MemoryUtil.memAddress(raw.getDirectBuffer()), vbuf, 0, raw.getLength());
                        vertexBuffers.add(vbuf);
                        raw.free();
                    }
                    jobVertexBuffers.add(vertexBuffers);

                    long buildBufferSize = 0;
                    for (var geometry : job.geometries()) {
                        if (geometry.geometry().getLength() <= 0) {
                            throw new IllegalStateException("Geometry size <= 0");
                        }
                        buildBufferSize += geometry.geometry().getLength();
                    }

                    if (buildBufferSize <= 0) {
                        throw new IllegalStateException("Build buffer size <= 0");
                    }

                    var buildBuffer = context.memory.createBuffer(buildBufferSize,
                            VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                    | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                                    | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                            0, 0);
                    buffersToFree.add(buildBuffer);
                    var buildBufferAddr = buildBuffer.deviceAddress();
                    long buildBufferOffset = 0;

                    for (int geoIdx = 0; geoIdx < job.geometries().size(); geoIdx++) {
                        var geometry = job.geometries().get(geoIdx);
                        var geometryInfo = geometryInfos.get().sType$Default();
                        var br = brs.get();

                        uploadBuildCmd.encodeDataUpload(context.memory,
                                MemoryUtil.memAddress(geometry.geometry().getDirectBuffer()), buildBuffer,
                                buildBufferOffset, geometry.geometry().getLength());

                        VkDeviceOrHostAddressConstKHR indexData = SharedQuadVkIndexBuffer.getIndexBuffer(context,
                                uploadBuildCmd,
                                Integer.max(geometry.quadCount(), 120000));
                        int indexType = SharedQuadVkIndexBuffer.TYPE;

                        VkDeviceOrHostAddressConstKHR vertexData = VkDeviceOrHostAddressConstKHR.calloc(stack)
                                .deviceAddress(buildBufferAddr + buildBufferOffset);
                        // R16G16B16_SFLOAT (3-component) is not in Vulkan's guaranteed AS-vertex-format
                        // support set and caused intermittent BVH builder corruption -> device lost.
                        // R16G16B16A16_SFLOAT is spec-mandatory for AS builds; W is unused padding.
                        int vertexFormat = VK_FORMAT_R16G16B16A16_SFLOAT;
                        int vertexStride = 2 * 4;

                        geometryInfo.geometry(VkAccelerationStructureGeometryDataKHR.calloc(stack)
                                .triangles(VkAccelerationStructureGeometryTrianglesDataKHR.calloc(stack)
                                        .sType$Default()
                                        .vertexData(vertexData)
                                        .vertexFormat(vertexFormat)
                                        .vertexStride(vertexStride)
                                        .maxVertex(geometry.quadCount() * 4)
                                        .indexData(indexData)
                                        .indexType(indexType)))
                                .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                                .flags(geometry.geometryFlags());

                        maxPrims.put(geometry.quadCount() * 2);
                        br.primitiveCount(geometry.quadCount() * 2);

                        buildBufferOffset += geometry.geometry().getLength();
                        geometry.geometry().free();
                    }

                    uploadBuildCmd.encodeBufferBarrier(buildBuffer, 0, VK_WHOLE_SIZE, VK_PIPELINE_STAGE_TRANSFER_BIT,
                            VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR);

                    geometryInfos.rewind();
                    maxPrims.rewind();

                    var bi = buildInfos.get()
                            .sType$Default()
                            .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                            .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                                    | VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_COMPACTION_BIT_KHR)
                            .pGeometries(geometryInfos)
                            .geometryCount(job.geometries().size());

                    VkAccelerationStructureBuildSizesInfoKHR buildSizesInfo = VkAccelerationStructureBuildSizesInfoKHR
                            .calloc(stack)
                            .sType$Default();

                    vkGetAccelerationStructureBuildSizesKHR(
                            context.device,
                            VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                            bi,
                            maxPrims,
                            buildSizesInfo);

                    var structure = context.memory.createAcceleration(buildSizesInfo.accelerationStructureSize(), 256,
                            VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR,
                            VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);

                    var scratch = context.memory.createBuffer(buildSizesInfo.buildScratchSize(),
                            VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 256, 0);

                    bi.scratchData(VkDeviceOrHostAddressKHR.calloc(stack).deviceAddress(scratch.deviceAddress()));
                    bi.dstAccelerationStructure(structure.structure);

                    pAccelerationStructures.put(structure.structure);

                    accelerationStructures[i] = structure;
                    scratchBuffers[i] = scratch;
                }

                buildInfos.rewind();
                buildRanges.rewind();
                pAccelerationStructures.rewind();

                VSemaphore link = context.sync.createBinarySemaphore();
                {
                    vkCmdBuildAccelerationStructuresKHR(uploadBuildCmd.buffer, buildInfos, buildRanges);

                    uploadBuildCmd.encodeMemoryBarrier();
                    uploadBuildCmd.end();

                    VFence buildFence = context.sync.createFence();
                    context.cmd.submit(asyncQueue, new VCmdBuff[] { uploadBuildCmd }, new VSemaphore[0], new int[0],
                            new VSemaphore[] { link },
                            buildFence);

                    {
                        vkWaitForFences(context.device, buildFence.address(), true, -1);

                        for (var sb : scratchBuffers) {
                            sb.free();
                        }
                        for (var b : buffersToFree) {
                            b.free();
                        }

                        sinlgeUsePool.releaseNow(uploadBuildCmd);
                        buildFence.free();
                    }
                }

                VSemaphore awaitSemaphore = context.sync.createBinarySemaphore();

                List<BLASBuildResult> results = new ArrayList<>();
                for (int idx = 0; idx < jobs.size(); idx++) {
                    var job = jobs.get(idx);
                    results.add(new BLASBuildResult(accelerationStructures[idx],
                            new JobPassThroughData(job.section(), job.submitTime(), jobVertexBuffers.get(idx))));
                }

                var cmd = sinlgeUsePool.createCommandBuffer();
                cmd.begin(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                cmd.encodeMemoryBarrier();
                VFence fence = context.sync.createFence();
                cmd.end();
                context.cmd.submit(asyncQueue, new VCmdBuff[] { cmd },
                        new VSemaphore[] { link },
                        new int[] { VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR },
                        new VSemaphore[] { awaitSemaphore },
                        fence);

                context.sync.addCallback(fence, () -> {
                    cmd.enqueueFree();
                    fence.free();
                    link.free();
                });

                resultConsumer.accept(new BLASBatchResult(results, awaitSemaphore));
            }
        }
    }

    // Enqueues a batch of chunk builds. CPU-only: copies raw vertex data, no Vulkan
    // calls.
    // All Vulkan work happens in the background worker thread (run()).
    public void enqueue(List<ChunkBuildOutput> batch) {
        List<BLASBuildJob> jobs = new ArrayList<>(batch.size());
        for (ChunkBuildOutput cbr : batch) {
            var acbr = ((IAccelerationBuildResult) cbr).getAccelerationGeometryData();
            if (acbr == null)
                continue;

            // 40-byte per-vertex geometry buffer data (shaderpack data.glsl Vertex struct
            // format)
            // pre-encoded by SodiumResultAdapter from Iris 1.11.2 extended vertex data
            var geomBufsMap = ((IAccelerationBuildResult) cbr).getGeometryBuffersData();
            if (geomBufsMap == null)
                continue;

            List<BLASTriangleData> buildData = new ArrayList<>();
            List<NativeBuffer> rawVertexData = new ArrayList<>();

            for (var entry : acbr.entrySet()) {
                int flag = entry.getKey() == DefaultTerrainRenderPasses.SOLID ? VK_GEOMETRY_OPAQUE_BIT_KHR : 0;
                buildData.add(new BLASTriangleData(entry.getValue().quadCount(), entry.getValue().data(), flag));

                var geomBuf = geomBufsMap.get(entry.getKey());
                if (geomBuf == null || geomBuf.getLength() == 0) {
                    throw new IllegalStateException("Missing geometry buffer data for pass " + entry.getKey());
                }
                rawVertexData.add(geomBuf);
            }

            if (!buildData.isEmpty()) {
                jobs.add(new BLASBuildJob(buildData, rawVertexData, cbr.section, cbr.submitTime));
            }
        }

        if (jobs.isEmpty())
            return;
        batchedJobs.add(jobs);
        awaitingJobBatchess.release();
    }
}
