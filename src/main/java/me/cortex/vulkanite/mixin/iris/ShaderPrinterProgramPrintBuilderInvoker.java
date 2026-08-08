package me.cortex.vulkanite.mixin.iris;

import net.irisshaders.iris.pipeline.transform.ShaderPrinter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = ShaderPrinter.ProgramPrintBuilder.class, remap = false)
public interface ShaderPrinterProgramPrintBuilderInvoker {
    @Invoker("addItem")
    void vulkanite$addItem(String extension, String content);
}
