# Third-Party Notices

Vulkanite's project-owned code is licensed under `LGPL-3.0` (see [LICENSE.txt](LICENSE.txt)).
This file documents third-party components and license boundaries that are not
changed by that license.

## NVIDIA DLSS / NGX SDK

Vulkanite can build and distribute release artifacts that include NVIDIA DLSS/NGX
SDK runtime components, including DLSS Super Resolution and DLSS Ray
Reconstruction libraries. These NVIDIA components are proprietary third-party
software and are not licensed under the LGPL.

The NVIDIA SDK components remain subject to the NVIDIA RTX SDKs license:

<https://github.com/NVIDIA/DLSS/blob/main/LICENSE.txt>

The LGPL license grant for Vulkanite does not grant rights to NVIDIA SDK
components. Redistribution and use of those components must comply with
NVIDIA's license terms.

This software contains source code provided by NVIDIA Corporation.

Bundled NVIDIA SDK runtime libraries may include files matching:

- `vulkanite/natives/windows-x64/nvngx_dlss.dll`
- `vulkanite/natives/windows-x64/nvngx_dlssd.dll`
- `vulkanite/natives/windows-x64/nvngx_dlssg.dll`
- `vulkanite/natives/linux-x64/libnvidia-ngx-dlss.so*`
- `vulkanite/natives/linux-x64/libnvidia-ngx-dlssd.so*`
- `vulkanite/natives/linux-x64/libnvidia-ngx-dlssg.so*`

**No NVIDIA binary is checked into this repository.** They are only present in
jars produced with `-PbundleNgxNatives=true` (see [README.md](README.md#dlss) and
`.github/workflows/native-ngx.yml`), which requires a local checkout of
[NVIDIA/DLSS](https://github.com/NVIDIA/DLSS) pointed at by `DLSS_SDK`.

Vulkanite's `ngxshim` native library is project-owned glue code and follows
Vulkanite's project license unless otherwise noted.

## Caustica

The DLSS integration in `me.cortex.vulkanite.dlss` and `native/ngx_shim` is
ported from [Caustica](https://github.com/xysgottaken2/Caustica), copyright
ComfyFluffy and contributors, licensed `LGPL-3.0-or-later`:

<https://github.com/xysgottaken2/Caustica/blob/main/LICENSE.md>

Ported files retain that license; the port adapts them to Vulkanite's own
`VContext` and resource types rather than Minecraft's native Vulkan backend.
