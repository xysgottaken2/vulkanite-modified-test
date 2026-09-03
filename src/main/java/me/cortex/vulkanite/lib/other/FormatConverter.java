package me.cortex.vulkanite.lib.other;

import net.irisshaders.iris.gl.texture.InternalTextureFormat;

import static org.lwjgl.opengl.GL11C.GL_RGB8;
import static org.lwjgl.opengl.GL11C.GL_RGBA16;
import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.vulkan.VK10.*;

public class FormatConverter {
    public static int getVkFormatFromGl(InternalTextureFormat format) {
        return switch (format.getGlFormat()) {
            case GL_R11F_G11F_B10F -> VK_FORMAT_B10G11R11_UFLOAT_PACK32;

            case GL_RGB32F, GL_RGBA32F -> VK_FORMAT_R32G32B32A32_SFLOAT;
            case GL_RG32F -> VK_FORMAT_R32G32_SFLOAT;
            case GL_R32F -> VK_FORMAT_R32_SFLOAT;
            case GL_RGB16F, GL_RGBA16F -> VK_FORMAT_R16G16B16A16_SFLOAT;
            case GL_RG16F -> VK_FORMAT_R16G16_SFLOAT;
            case GL_R16F -> VK_FORMAT_R16_SFLOAT;

            case GL_RGB16, GL_RGBA16 -> VK_FORMAT_R16G16B16A16_UNORM;
            case GL_RG16 -> VK_FORMAT_R16G16_UNORM;
            case GL_R16 -> VK_FORMAT_R16_UNORM;
            case GL_RGBA, GL_RGB8, GL_RGBA8 -> VK_FORMAT_R8G8B8A8_UNORM;
            case GL_RG8 -> VK_FORMAT_R8G8_UNORM;
            case GL_R8 -> VK_FORMAT_R8_UNORM;

            default -> {
                throw new IllegalArgumentException("No known conversion to VK type for GL type " + format + " ("
                        + format.getGlFormat() + ").");
            }
        };
    }

    /**
     * Returns the GL internal format backed by the Vulkan format above. Vulkan
     * portability for three-component formats is poor, so those are expanded to
     * four components while preserving sampled RGB values.
     */
    public static int getSharedGlFormat(InternalTextureFormat format) {
        return switch (format.getGlFormat()) {
            case GL_RGBA, GL_RGB8 -> GL_RGBA8;
            case GL_RGB16 -> GL_RGBA16;
            case GL_RGB16F -> GL_RGBA16F;
            case GL_RGB32F -> GL_RGBA32F;
            default -> format.getGlFormat();
        };
    }

    public static InternalTextureFormat findFormatFromGlFormat(int glFormat) {
        InternalTextureFormat[] availableFormats = InternalTextureFormat.values();

        for (InternalTextureFormat format : availableFormats) {
            if (format.getGlFormat() == glFormat) {
                return format;
            }
        }

        return null;
    }
}
