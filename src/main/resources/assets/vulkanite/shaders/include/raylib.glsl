// Raytracing utility functions for Vulkanite shader pack integration
// This file is included by raytracing shaders to provide common utilities

#ifndef RAYLIB_GLSL
#define RAYLIB_GLSL

#extension GL_EXT_ray_tracing : require
#extension GL_EXT_buffer_reference : require
#extension GL_EXT_shader_explicit_arithmetic_types_int64 : require

// Hit group index constants
#define HIT_GROUP_SOLID 0
#define HIT_GROUP_CUTOUT 1

// Geometry flags
#define GEOMETRY_FLAG_OPAQUE      0x1
#define GEOMETRY_FLAG_NO_DUPLICATE_ANY_HIT_INVOCATION 0x2

// Vertex data layout (half-float xyz)
struct Vertex {
    float16_t x, y, z;
};

// Buffer reference for geometry data
layout(buffer_reference, scalar) buffer GeometryBuffer {
    Vertex vertices[];
};

// Instance data
struct InstanceData {
    uint64_t geometryBufferAddress;
    uint     materialIndex;
    uint     flags;
};

// Hit payload
struct HitPayload {
    vec3    hitPosition;
    vec3    hitNormal;
    float   hitDistance;
    uint    materialIndex;
    uint    instanceIndex;
    uint    primitiveIndex;
    vec2    barycentrics;
    bool    isMiss;
};

#endif // RAYLIB_GLSL
