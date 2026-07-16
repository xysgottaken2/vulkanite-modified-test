package me.cortex.vulkanite.mixin.minecraft;

import com.mojang.blaze3d.opengl.GlDebug;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GlDebug.class)
public class MixinGlDebug {

    @Inject(method = "enableDebugCallback", at = @At("HEAD"), cancellable = true)
    private static void onEnableDebugCallback(int verbosity, boolean debugSynchronousGlLogs,
            java.util.Set<String> enabledExtensions, CallbackInfoReturnable<GlDebug> cir) {
        cir.setReturnValue(null);
        cir.cancel();
    }
}
