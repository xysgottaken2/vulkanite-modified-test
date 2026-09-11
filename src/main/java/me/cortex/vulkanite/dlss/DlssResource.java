package me.cortex.vulkanite.dlss;

import me.cortex.vulkanite.lib.memory.VImage;
import me.cortex.vulkanite.lib.other.VImageView;

/**
 * The (view, image, format, size) tuple every NGX resource argument is built from. The shim needs all
 * four: the view and image handles for the descriptor, the format to construct the
 * {@code NVSDK_NGX_Resource_VK}, and the extents because the shim sizes its resources from them
 * rather than querying the image.
 *
 * <p>Kept as raw handles rather than a {@link VImage} so DLSS can be handed images Vulkanite did not
 * allocate itself (GL-shared {@code VGImage}s from Iris render targets, for example).
 */
public record DlssResource(long view, long image, int format, int width, int height) {
    public static DlssResource of(VImageView view) {
        VImage image = view.image;
        return new DlssResource(view.view, image.image(), image.format, image.width, image.height);
    }

    public static DlssResource of(VImageView view, int format) {
        VImage image = view.image;
        return new DlssResource(view.view, image.image(), format, image.width, image.height);
    }

    /** A null resource, for NGX inputs the shim treats as optional (a 0 view handle means "absent"). */
    public static DlssResource none() {
        return new DlssResource(0L, 0L, 0, 0, 0);
    }

    public boolean isPresent() {
        return view != 0L && image != 0L;
    }
}
