package me.cortex.vulkanite.dlss;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DLSS settings for Vulkanite, read from JVM system properties.
 *
 * <p>Vulkanite has no config file layer, so DLSS follows the same "system property with a
 * documented default" convention the rest of the mod uses for its tuning knobs. Every key is
 * resolved lazily on read, which means a value can be changed between runs with
 * {@code -Dvulkanite.dlss.sr.quality=2} without any config file round-trip.
 *
 * <p>Ported from Caustica's {@code CausticaConfig.Rt.DlssRr} / {@code CausticaConfig.Ngx}
 * (LGPL-3.0-or-later); see THIRD_PARTY_NOTICES.md.
 */
public final class DlssConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkanite/DlssConfig");

    /** {@code NVSDK_NGX_PerfQuality_Value} — 0 asks the DLL for its per-mode default. */
    public static final int QUALITY_AUTO = 0;
    public static final int QUALITY_MAX_QUALITY = 1;
    public static final int QUALITY_BALANCED = 2;
    public static final int QUALITY_MAX_PERFORMANCE = 3;
    public static final int QUALITY_ULTRA_PERFORMANCE = 4;
    public static final int QUALITY_DLAA = 5;

    /** {@code NVSDK_NGX_DLSS_Hint_Render_Preset} — 0 lets the DLL choose per quality mode, 11 is Preset K. */
    public static final int PRESET_DEFAULT = 0;
    public static final int PRESET_K = 11;

    private DlssConfig() {
    }

    /** DLSS Super Resolution (denoise + upscale). Off by default: it needs a caller to feed it. */
    public static boolean srEnabled() {
        return bool("vulkanite.dlss.sr", false);
    }

    public static int srQuality() {
        return clampedInt("vulkanite.dlss.sr.quality", QUALITY_AUTO, QUALITY_AUTO, QUALITY_DLAA);
    }

    public static int srPreset() {
        return clampedInt("vulkanite.dlss.sr.preset", PRESET_DEFAULT, 0, 12);
    }

    /** DLSS Ray Reconstruction (AI denoise). Off by default: it needs path-traced guide buffers. */
    public static boolean rrEnabled() {
        return bool("vulkanite.dlss.rr", false);
    }

    public static int rrQuality() {
        return clampedInt("vulkanite.dlss.rr.quality", QUALITY_AUTO, QUALITY_AUTO, QUALITY_DLAA);
    }

    public static int rrPreset() {
        return clampedInt("vulkanite.dlss.rr.preset", PRESET_DEFAULT, 0, 12);
    }

    /**
     * Override for the directory (or full path) holding {@code ngxshim.dll} / {@code libngxshim.so}
     * and the {@code nvngx_*} feature libraries. When unset, the shim is extracted from the mod jar.
     */
    public static String shimPath() {
        String value = System.getProperty("vulkanite.ngx.path");
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    /**
     * Whether to extract the bundled natives at all. Defaults to true; set to {@code false} to force
     * {@link #shimPath()} (useful when iterating on a locally built shim).
     */
    public static boolean extractBundledNatives() {
        return bool("vulkanite.ngx.extract", true);
    }

    private static boolean bool(String key, boolean fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.trim().toLowerCase()) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> {
                LOGGER.warn("Ignoring non-boolean value '{}' for {}", value, key);
                yield fallback;
            }
        };
    }

    private static int clampedInt(String key, int fallback, int min, int max) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < min || parsed > max) {
                LOGGER.warn("Clamping {} from {} into [{}, {}]", key, parsed, min, max);
                return Math.clamp(parsed, min, max);
            }
            return parsed;
        } catch (NumberFormatException e) {
            LOGGER.warn("Ignoring non-integer value '{}' for {}", value, key);
            return fallback;
        }
    }
}
