package me.cortex.vulkanite.lib.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VImage {
    protected VmaAllocator.ImageAllocation allocation;
    public final int width;
    public final int height;
    public final int depth;
    public final int mipLayers;
    public final int format;
    public final int dimensions;
    private int imageViewCount;
    private boolean freeRequested;
    private static Logger LOGGER = LoggerFactory.getLogger(VImage.class.getName());

    VImage(VmaAllocator.ImageAllocation allocation, int width, int height, int depth, int mipLayers, int format) {
        this(allocation, width, height, depth, mipLayers, format, inferDimensions(height, depth));
    }

    VImage(VmaAllocator.ImageAllocation allocation, int width, int height, int depth, int mipLayers, int format,
            int dimensions) {
        this.allocation = allocation;
        this.width = width;
        this.height = height;
        this.mipLayers = mipLayers;
        this.format = format;
        this.depth = depth;

        if (dimensions < 1 || dimensions > 3) {
            throw new IllegalArgumentException("Image dimensions must be between 1 and 3");
        }
        this.dimensions = dimensions;
    }

    private static int inferDimensions(int height, int depth) {
        if (depth != 1) {
            return 3;
        }
        return height != 1 ? 2 : 1;
    }

    public synchronized void retainImageView() {
        if (allocation == null || freeRequested) {
            throw new IllegalStateException("Cannot create a view for an image pending destruction");
        }
        imageViewCount++;
    }

    public synchronized void releaseImageView() {
        if (imageViewCount <= 0) {
            throw new IllegalStateException("Image view reference count underflow");
        }
        imageViewCount--;
        if (imageViewCount == 0 && freeRequested) {
            freeAllocation();
        }
    }

    public synchronized void free() {
        if (allocation == null || freeRequested) {
            LOGGER.warn("Attempted to free VImage that was already freed");
            return;
        }
        freeRequested = true;
        if (imageViewCount == 0) {
            freeAllocation();
        }
    }

    protected void freeAllocation() {
        allocation.free();
        allocation = null;
    }

    public long image() {
        return allocation.image;
    }
}
