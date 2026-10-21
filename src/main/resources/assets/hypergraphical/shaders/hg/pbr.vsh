#version 330 core

// Instanced vertex stage.
//
// Locations 0-3 come from the mesh's static vertex buffer and 11 from its baked occlusion
// buffer; 4-10 and 12 come from the per-frame instance buffer with an attribute divisor of 1. Everything that varies per instance lives here
// rather than in a uniform, which is what allows a thousand placements of one model to be a
// single draw call.

// -- per vertex --------------------------------------------------------------
layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aUv;
layout(location = 3) in vec4 aTangent;   // xyz unit, w handedness
layout(location = 11) in float aOcclusion; // baked ambient occlusion, 1 open .. 0 occluded

// -- per instance ------------------------------------------------------------
layout(location = 4) in mat4 iModel;     // consumes 4,5,6,7; camera-relative
layout(location = 8) in vec4 iTint;
layout(location = 9) in vec4 iParams;    // metallic, smoothness, emissive, probeBlend
layout(location = 10) in vec4 iLightUv;  // lightBlock 0..15, lightSky 0..15, uvOffset.xy
layout(location = 12) in vec4 iExtra;    // occlusion strength 0..2, glow, unused, unused

uniform mat4 uViewProjection;
uniform vec2 uTiling;
uniform vec2 uOffset;

out vec3 vWorldPos;
out vec3 vNormal;
out vec4 vTangent;
out vec2 vUv;
out vec4 vTint;
out vec4 vParams;
out vec2 vLightLevels;
out float vOcclusion;
out float vGlow;

void main() {
    vec4 world = iModel * vec4(aPosition, 1.0);
    vWorldPos = world.xyz;

    // Normal matrix from normalised model columns. This is exact for rotation plus uniform
    // scale, which covers essentially every instance in practice, and costs three
    // normalisations instead of a per-vertex 3x3 inverse-transpose. Non-uniform scale will
    // skew normals; see ModelInstance's docs.
    mat3 m = mat3(iModel);
    mat3 normalMatrix = mat3(normalize(m[0]), normalize(m[1]), normalize(m[2]));

    vNormal = normalMatrix * aNormal;
    vTangent = vec4(normalMatrix * aTangent.xyz, aTangent.w);

    // Material tiling/offset, then the per-instance offset on top so it can be animated
    // without touching the material.
    vUv = aUv * uTiling + uOffset + iLightUv.zw;

    vTint = iTint;
    vParams = iParams;
    vLightLevels = iLightUv.xy;

    // Per-instance occlusion strength as an exponent: 0 is off, 1 the baked value, 2 its
    // square. Evaluated per vertex because the bake is per vertex anyway, and pow(0, 0) is
    // undefined in GLSL, hence the explicit off case and the floor on the base.
    float strength = clamp(iExtra.x, 0.0, 2.0);
    float baked = clamp(aOcclusion, 0.0, 1.0);
    vOcclusion = strength <= 0.0 ? 1.0 : pow(max(baked, 1e-4), strength);
    vGlow = max(iExtra.y, 0.0);

    gl_Position = uViewProjection * world;
}
