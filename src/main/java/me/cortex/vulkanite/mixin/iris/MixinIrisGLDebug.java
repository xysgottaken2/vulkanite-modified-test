package me.cortex.vulkanite.mixin.iris;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = net.irisshaders.iris.gl.GLDebug.class, remap = false)
public class MixinIrisGLDebug {

    @Inject(method = "setupDebugMessageCallback()I", at = @At("HEAD"), cancellable = true)
    private static void onSetupDebugMessageCallback(CallbackInfoReturnable<Integer> cir) {
        // Report success without installing Iris's synchronous, stacktrace-heavy
        // OpenGL callback. Vulkan validation and external NGFX capture are unaffected.
        cir.setReturnValue(1);
    }
}
