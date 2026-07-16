package me.cortex.vulkanite.mixin.iris;

import me.cortex.vulkanite.compat.ShaderPropertiesRaw;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ShaderProperties.class, remap = false)
public class MixinShaderProperties {
    @Inject(method = "<init>(Ljava/lang/String;Lnet/irisshaders/iris/shaderpack/option/ShaderPackOptions;Ljava/lang/Iterable;)V", at = @At("HEAD"))
    private static void captureRawProperties(String contents, ShaderPackOptions shaderPackOptions, Iterable<?> environmentDefines, CallbackInfo ci) {
        ShaderPropertiesRaw.RAW = contents;
    }
}
