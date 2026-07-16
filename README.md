# Vulkanite for 26.2

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-brightgreen.svg)](https://minecraft.net)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://adoptium.net)

A Minecraft mod that brings **hardware-accelerated ray tracing** to Minecraft Java Edition using **OpenGL–Vulkan interop**. This is a community fork originally created by **[mcrcortex](https://github.com/mcrcortex)** and maintained/updated by **[sjrsjz](https://github.com/sjrsjz)**, targeting modern Minecraft versions (26.x), Sodium 0.9, and Iris 1.11.2.

Enables extra passes that shader developers can leverage for real-time ray tracing effects.

> **This version can identify world type** and resolves numerous compatibility, memory, and stability issues from the original upstream.

---

## What's New in This Fork

### Platform Upgrade
- **Minecraft** 1.20.2 → **26.2**
- **Java** 17 → **25**
- **Fabric Loom** → 1.18.0-alpha.7
- **Fabric API** → 0.154.2+26.2 (full bundle)
- **LWJGL** 3.3.1 → **3.4.1** (BOM-managed)

### Dependency Migrations
| Component | Original (Upstream) | This Fork |
|---|---|---|
| **Sodium** | `mc1.20.2-0.5.3` (`me.jellysquid.mods.sodium`) | `mc26.2-0.9.1-fabric` (`net.caffeinemc.mods.sodium`) |
| **Iris** | `1.6.9+1.20.2` (`net.coderbot.iris`) | `1.11.2+26.2-fabric` (`net.irisshaders.iris`) |
| **Fabric Loader** | 0.14.x | 0.19.3 |

### Memory Management Overhaul
- **Removed incorrect** `VK_MEMORY_HEAP_DEVICE_LOCAL_BIT` usage across all buffer allocations
- **Dropped superfluous** `VMA_MEMORY_USAGE_AUTO` flags causing incorrect allocation paths
- **Stack → heap migration**: Moved large Vulkan enumeration `PointerBuffer` allocations from stack to heap via `MemoryUtil.memCallocPointer` / `memFree`, preventing stack overflow crashes
- **Proper device properties cleanup**: `VkPhysicalDeviceRayTracingPipelinePropertiesKHR` heap-allocated and explicitly freed during context destruction
- **Improved error diagnostics** in `VmaAllocator`: now captures and reports buffer size, usage flags, memory flags, and translated Vulkan result on allocation failure
- **Instances buffer leak fix**: `AccelerationTLASManager` preserves the `VkAccelerationStructureInstanceKHR` buffer across cleanup/re-init cycles to prevent premature freeing

### Device Loss Recovery
- **Graceful degradation**: volatile `deviceLost` flag on `VContext` and `SyncManager` — rendering is skipped when the Vulkan device is lost
- **Fence error tolerance**: `SyncManager.checkFences` logs and removes fences on error status instead of crashing, enabling recovery from transient GPU errors
- **Null-safety guards**: All `AccelerationManager` operations (`chunkBuilds`, `sectionRemove`, `updateTick`, `cleanup`) are null-guarded to prevent crashes during context invalidation

### Iris SSBO Vulkan Interop (Complete Rewrite)
- **`MixinShaderStorageBuffer`** fully rewritten with `@Overwrite` blocks:
  - Constructor deletes Iris's plain OpenGL buffer immediately and defers buffer creation
  - `resizeIfRelative`: Dynamically replaces GL buffers with **Vulkan shared buffers** on window resize, keeping the SSBO interop bridge alive across resolution changes
  - `createStatic`: Creates Vulkan shared buffers for fixed-size SSBOs with optional initial content upload
  - `destroy`: Safely frees Vulkan resources with synced callbacks
- Ensures all Iris shader storage buffers are backed by Vulkan-OpenGL shared memory, required for the hybrid rendering pipeline

### Iris 1.11.2 Compatibility
Rewrote and streamlined the Iris mixin layer to match the new internal architecture:

| New / Rewritten | Removed (no longer applicable) |
|---|---|
| `MixinProgramSet` — updated for new `ShaderProperties` / package structure | `MixinNewWorldRenderingPipeline` |
| `MixinPBRAtlasTexture` — updated for Iris 1.11.2 PBR | `MixinPackRenderTargetDirectives` |
| `MixinShaderPackSourceNames` — adapted for new Iris | `MixinRenderTarget` |
| `MixinStandardMacros` — updated for new internal paths | `MixinNativeImageBackedTexture` |
| `MixinShaderProperties` — new | `MixinGlResource` |
| `MixinIrisRenderingPipeline` — new | `MixinGlTexture` |
| `MixinIrisGLDebug` — new | `MixinShaderStorageBufferHolder` |

### Sodium 0.9 Compatibility
Removed the fine-grained GL-level mixins that are no longer viable in Sodium 0.9's architecture:

| Removed Mixins (GL-interception layer) |
|---|
| `MixinCommandList` |
| `MixinGLRenderDevice` |
| `MixinGlBufferArena` |
| `MixinMutableBuffer` |
| `MixinRenderRegionManager` |
| `MixinRenderSectionManager` |
| `PendingSectionUploadAccessor` |
| `MixinSpriteAtlasTexture` (Minecraft) |

Retained and updated the chunk-build pipeline mixins (`MixinChunkBuildResult`, `MixinChunkRenderRebuildTask`, `VertexFormatAccessor`) for the new Sodium 0.9 chunk build flow.

### Ray Tracing Pipeline Enhancements
- **Configurable dispatch dimensions**: `RaytracingShaderSource` now supports `[dispatchW, dispatchH, dispatchD]` — values > 1 are fixed pixel counts, values in (0, 1] are screen-relative multipliers
- **Execution groups**: Passes in the same group run without barriers; a pipeline barrier is inserted between different groups for fine-grained synchronization control
- **VulkanPipeline full rewrite**: Modernized with SLF4J logging, device-loss guards, proper error diagnostics, and cleaner resource lifecycle management
- **GL error tracking**: `MemoryManager` now flushes and checks GL errors before/after `glDeleteMemoryObjectsEXT` to diagnose OpenGL error 1282 sources
- **Fallback image**: `placeholderImage` renamed to `fallbackImage` with clearer semantics

### Geometry Data Pipeline: Iris XHFP Support
`SodiumResultAdapter` was **completely rewritten** to decode Iris 1.11.2's XHFP (eXtended Half-Float Precision) vertex format:

- **20-bit packed positions** (up from 16-bit) — properly decoded via `decodePosition20`
- **Block ID extraction**: Decodes Iris 1.11.2 packed `blockId` at offset 20 (R32_UINT)
- **Mid-texture coordinates**: RG16_UINT decode at offset 28
- **Biased texture coordinates**: Handles Iris's ±1 bias encoding at offset 12
- **Separate BLAS + geometry buffers**: Half-float vertex positions for acceleration structures (8 bytes/vertex) plus a 160-byte Quad struct for the geometry buffer matching `data.glsl` layouts

### Build & CI
- **GitHub Actions**: Added Gradle build workflow (`.github/workflows/new.yml`)
- **LWJGL**: Upgraded to BOM-managed 3.4.1, removed platform-specific native hack
- **Java toolchain**: Set to Java 25 with `--enable-native-access=ALL-UNNAMED`
- **Proxy support**: Configured for development environments

---

## Requirements
- **Minecraft** 26.2
- **Fabric Loader** ≥ 0.16.10
- **Sodium** ≥ 0.9.1
- **Iris** ≥ 1.11.2
- **Java** 25+
- A **Vulkan-capable GPU** with the following Vulkan extensions:
  - `VK_KHR_ray_tracing_pipeline`
  - `VK_KHR_acceleration_structure`
  - `VK_KHR_deferred_host_operations`
  - `VK_KHR_external_memory` (Win32 or FD)
  - `VK_KHR_external_semaphore` (Win32 or FD)
  - `VK_KHR_external_fence` (Win32 or FD)
  - `VK_EXT_descriptor_indexing`
  - `VK_KHR_spirv_1_4`
  - `VK_KHR_shader_draw_parameters`
  - `VK_KHR_get_memory_requirements_2`
  - `VK_KHR_get_physical_device_properties_2`

## Building
```bash
./gradlew build
```

The compiled mod JAR will be in `build/libs/`.

## Credits
- **Original author**: [mcrcortex](https://github.com/mcrcortex) — creator of Vulkanite, the first Minecraft mod to bring hardware ray tracing via OpenGL-Vulkan interop
- **Maintainer**: [sjrsjz](https://github.com/sjrsjz) — 26.2 port, Sodium 0.9 / Iris 1.11.2 compatibility, memory fixes, device loss recovery, SSBO interop rewrite, XHFP geometry pipeline

## License
This project is based on the original Vulkanite by mcrcortex. See [LICENSE](LICENSE) for details.
