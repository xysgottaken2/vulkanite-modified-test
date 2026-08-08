package me.cortex.vulkanite.compat;

import me.cortex.vulkanite.mixin.iris.ShaderPrinterProgramPrintBuilderInvoker;
import net.irisshaders.iris.pipeline.transform.ShaderPrinter;

final class RaytracingShaderDebug {
    private RaytracingShaderDebug() {
    }

    static void print(RaytracingShaderSource source) {
        ShaderPrinter.ProgramPrintBuilder builder = ShaderPrinter.printProgram(source.name);
        add(builder, source.name, ".rgen", source.raygen);
        for (int i = 0; i < source.raymiss.length; i++) {
            add(builder, source.name + "_" + i, ".rmiss", source.raymiss[i]);
        }
        for (int i = 0; i < source.rayhit.length; i++) {
            RaytracingShaderSource.RayHitSource hit = source.rayhit[i];
            add(builder, source.name + "_" + i, ".rchit", hit.close());
            add(builder, source.name + "_" + i, ".rahit", hit.any());
            add(builder, source.name + "_" + i, ".rint", hit.intersection());
        }
        builder.print();
    }

    private static void add(ShaderPrinter.ProgramPrintBuilder builder, String name,
            String extension, String source) {
        if (source == null) {
            return;
        }
        builder.setName(name);
        ((ShaderPrinterProgramPrintBuilderInvoker) (Object) builder).vulkanite$addItem(extension, source);
    }
}
