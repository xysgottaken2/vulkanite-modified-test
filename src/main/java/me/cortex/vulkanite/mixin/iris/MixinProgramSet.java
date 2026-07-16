package me.cortex.vulkanite.mixin.iris;

import me.cortex.vulkanite.compat.IGetRaytracingSource;
import me.cortex.vulkanite.compat.RaytracingShaderSource;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

@Mixin(value = ProgramSet.class, remap = false)
public abstract class MixinProgramSet implements IGetRaytracingSource {
    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanite/ProgramSet");

    @Unique
    private static final RaytracingShaderSource[] EMPTY_SOURCES = new RaytracingShaderSource[0];

    @Unique
    private RaytracingShaderSource[] sources;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void injectRTShaders(AbsolutePackPath directory, Function<AbsolutePackPath, String> sourceProvider,
            ShaderProperties shaderProperties, ShaderPack pack, CallbackInfo ci) {

        Function<String, String> load = filename -> sourceProvider.apply(directory.resolve(filename));

        List<RaytracingShaderSource> sourceList = new ArrayList<>();

        for (int pass = 0;; pass++) {
            String gen = load.apply("ray" + pass + ".rgen");
            if (gen == null) break;

            List<String> missSources = new ArrayList<>();
            for (int missId = 0;; missId++) {
                String miss = load.apply("ray" + pass + "_" + missId + ".rmiss");
                if (miss == null) break;
                missSources.add(miss);
            }

            List<RaytracingShaderSource.RayHitSource> hitSources = new ArrayList<>();
            for (int hitId = 0;; hitId++) {
                String close = load.apply("ray" + pass + "_" + hitId + ".rchit");
                String any = load.apply("ray" + pass + "_" + hitId + ".rahit");
                String intersect = load.apply("ray" + pass + "_" + hitId + ".rint");
                if (close == null && any == null && intersect == null) break;
                hitSources.add(new RaytracingShaderSource.RayHitSource(close, any, intersect));
            }

            if (missSources.isEmpty()) throw new IllegalStateException("No miss shaders for pass " + pass);
            if (hitSources.isEmpty()) throw new IllegalStateException("No hit shaders for pass " + pass);

            sourceList.add(new RaytracingShaderSource(
                    "raypass_" + pass, gen,
                    missSources.toArray(String[]::new),
                    hitSources.toArray(RaytracingShaderSource.RayHitSource[]::new)));
        }

        if (!sourceList.isEmpty()) {
            Properties props = buildProperties(me.cortex.vulkanite.compat.ShaderPropertiesRaw.RAW);
            int[] groups = parseGroups(props, sourceList.size());
            for (int i = 0; i < sourceList.size(); i++) {
                float[] disp = parseDispatch(props, "rt." + i + ".dispatch");
                int grp = groups != null ? groups[i] : 0;
                LOGGER.info("RT pass {} dispatch={}x{}x{} group={}", i, disp[0], disp[1], disp[2], grp);
                sourceList.set(i, new RaytracingShaderSource(
                        sourceList.get(i).name, sourceList.get(i).raygen, sourceList.get(i).raymiss,
                        disp[0], disp[1], disp[2], grp,
                        sourceList.get(i).rayhit));
            }
            sources = sourceList.toArray(RaytracingShaderSource[]::new);
            LOGGER.info("Discovered {} raytracing shader source(s) in {}", sources.length, directory);
        }
    }

    @Override
    public RaytracingShaderSource[] getRaytracingSource() {
        return sources != null ? sources : EMPTY_SOURCES;
    }

    @Unique
    private static Properties buildProperties(String raw) {
        Properties p = new Properties();
        if (raw != null) {
            try { p.load(new StringReader(raw)); } catch (IOException ignored) {}
        }
        return p;
    }

    @Unique
    private static int[] parseGroups(Properties props, int count) {
        String v = props.getProperty("rt.groups");
        if (v == null) return null;
        String[] parts = v.trim().split("\\s+");
        int[] groups = new int[Math.min(parts.length, count)];
        for (int i = 0; i < groups.length; i++) {
            try { groups[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException e) { groups[i] = 0; }
        }
        return groups;
    }

    @Unique
    private static float[] parseDispatch(Properties props, String key) {
        float[] def = {1.0f, 1.0f, 1.0f};
        String v = props.getProperty(key);
        if (v == null) return def;
        String[] parts = v.trim().split("\\s+");
        try {
            if (parts.length >= 3) {
                def[0] = Float.parseFloat(parts[0]);
                def[1] = Float.parseFloat(parts[1]);
                def[2] = Float.parseFloat(parts[2]);
            } else if (parts.length == 2) {
                def[0] = Float.parseFloat(parts[0]);
                def[1] = Float.parseFloat(parts[1]);
            }
        } catch (NumberFormatException ignored) {}
        return def;
    }
}
