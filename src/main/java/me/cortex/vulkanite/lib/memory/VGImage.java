package me.cortex.vulkanite.lib.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.GL11C.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11C.glDeleteTextures;
import static org.lwjgl.opengl.GL11C.glGetError;

public class VGImage extends VImage {
    private static final Logger LOG = LoggerFactory.getLogger("Vulkanite/VGImage");
    public final int glId;
    public final int glFormat;
    private final long vkMemory;
    private boolean ownsGlTexture = true;

    VGImage(VmaAllocator.ImageAllocation allocation, int width, int height, int depth, int mipLayers, int format, int glFormat, int glId) {
        super(allocation, width, height, depth, mipLayers, format);
        this.glId = glId;
        this.glFormat = glFormat;
        this.vkMemory = allocation.ai.deviceMemory();
    }

    VGImage(VmaAllocator.ImageAllocation allocation, int width, int height, int depth, int mipLayers, int format,
            int glFormat, int glId, int dimensions) {
        super(allocation, width, height, depth, mipLayers, format, dimensions);
        this.glId = glId;
        this.glFormat = glFormat;
        this.vkMemory = allocation.ai.deviceMemory();
    }

    /** The Minecraft GpuTexture wrapper will delete the OpenGL texture name. */
    public void transferGlTextureOwnership() {
        ownsGlTexture = false;
    }

    @Override
    protected void freeAllocation() {
        if (!ownsGlTexture) {
            MemoryManager.ExternalMemoryTracker.release(this.vkMemory);
            super.freeAllocation();
            return;
        }
        int errBefore = glGetError();
        if (errBefore != GL_NO_ERROR) {
            LOG.warn("VGImage.free: stale GL error 0x{} BEFORE glDeleteTextures(glId={})",
                    Integer.toHexString(errBefore), glId);
        }
        glDeleteTextures(glId);
        int errAfter = glGetError();
        if (errAfter != GL_NO_ERROR) {
            LOG.error("VGImage.free: glDeleteTextures(glId={}) produced GL error {:#x}", glId, errAfter);
        }
        MemoryManager.ExternalMemoryTracker.release(this.vkMemory);
        super.freeAllocation();
    }
}
