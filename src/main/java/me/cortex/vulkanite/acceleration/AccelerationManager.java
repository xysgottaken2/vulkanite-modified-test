package me.cortex.vulkanite.acceleration;

import me.cortex.vulkanite.lib.base.VContext;
import me.cortex.vulkanite.client.rendering.entity.EntityGeometryFrame;
import me.cortex.vulkanite.lib.descriptors.VDescriptorSetLayout;
import me.cortex.vulkanite.lib.memory.VAccelerationStructure;
import me.cortex.vulkanite.lib.other.sync.VSemaphore;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

public class AccelerationManager {
    private final VContext ctx;
    private final AccelerationTLASManager tlasManager;
    private final AccelerationBlasBuilder blasBuilder;
    private final List<VSemaphore> syncs = new LinkedList<>();
    // BLAS results queued by background worker thread, drained on render thread in updateTick()
    private final ConcurrentLinkedDeque<AccelerationBlasBuilder.BLASBatchResult> pendingResults = new ConcurrentLinkedDeque<>();
    private EntityGeometryFrame pendingEntityGeometry;

    public AccelerationManager(VContext context, int blasBuildQueue) {
        this.ctx = context;
        this.tlasManager = new AccelerationTLASManager(context, 0);
        this.blasBuilder = new AccelerationBlasBuilder(context, blasBuildQueue, pendingResults::add);
    }

    // Called from Sodium worker threads — safe because enqueue() does no Vulkan work
    public void chunkBuilds(List<ChunkBuildOutput> results) {
        blasBuilder.enqueue(results);
    }

    // Called on render thread — drains BLAS results and updates TLAS data structures
    public void updateTick() {
        while (!pendingResults.isEmpty()) {
            var batch = pendingResults.poll();
            tlasManager.updateSections(batch.results());
            syncs.add(batch.semaphore());
        }
    }

    public VAccelerationStructure buildTLAS(VSemaphore inLink, VSemaphore outLink) {
        EntityGeometryFrame entityGeometry;
        synchronized (this) {
            entityGeometry = pendingEntityGeometry;
            pendingEntityGeometry = null;
        }
        tlasManager.buildTLAS(inLink, outLink, syncs.toArray(new VSemaphore[0]), entityGeometry);
        syncs.clear();
        return tlasManager.getTlas();
    }

    public synchronized void setEntityGeometry(EntityGeometryFrame geometry) {
        if (pendingEntityGeometry != null) {
            pendingEntityGeometry.free();
        }
        pendingEntityGeometry = geometry;
    }

    public void sectionRemove(RenderSection section) {
        tlasManager.removeSection(section);
    }

    public void cleanup() {
        blasBuilder.shutdown();
        ctx.cmd.waitQueueIdle(0);
        ctx.cmd.waitQueueIdle(1);
        syncs.forEach(VSemaphore::free);
        syncs.clear();
        synchronized (this) {
            if (pendingEntityGeometry != null) {
                pendingEntityGeometry.free();
                pendingEntityGeometry = null;
            }
        }
        tlasManager.cleanupTick();
    }

    public long getGeometrySet() { return tlasManager.getGeometrySet(); }
    public VDescriptorSetLayout getGeometryLayout() { return tlasManager.getGeometryLayout(); }
}
