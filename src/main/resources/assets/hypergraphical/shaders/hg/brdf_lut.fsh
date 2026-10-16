#version 330 core

// Split-sum environment BRDF: the (A, B) pair such that
//     integral(L * BRDF) ~= prefilteredRadiance * (F0 * A + B)
//
// Depends only on NdotV and roughness, so it is neither view- nor material-specific and one
// 128x128 RG16F texture serves every material and every probe in the world. Baked once.
//
//   x axis = NdotV in (0, 1]
//   y axis = roughness in [0, 1]

#hg_include "hypergraphical:shaders/hg/ibl_common.glsl"

const uint SAMPLE_COUNT = 512u;

in vec2 vUv;
out vec2 fragColor;

vec2 integrateBrdf(float nDotV, float roughness) {
    // Work in a canonical frame with N = +Z; the integral is rotationally symmetric.
    vec3 v = vec3(sqrt(max(0.0, 1.0 - nDotV * nDotV)), 0.0, nDotV);
    vec3 n = vec3(0.0, 0.0, 1.0);

    float a = 0.0;
    float b = 0.0;

    for (uint i = 0u; i < SAMPLE_COUNT; ++i) {
        vec2 xi = hgHammersley(i, SAMPLE_COUNT);
        vec3 h = hgImportanceSampleGGX(xi, n, roughness);
        vec3 l = normalize(2.0 * dot(v, h) * h - v);

        float nDotL = max(l.z, 0.0);
        float nDotH = max(h.z, 0.0);
        float vDotH = max(dot(v, h), 0.0);

        if (nDotL > 0.0) {
            float g = hgGeometrySmithIbl(nDotV, nDotL, roughness);
            // The GGX pdf cancels against D, leaving G * VdotH / (NdotH * NdotV).
            float gVis = (g * vDotH) / max(nDotH * nDotV, 1e-7);
            float fc = pow(1.0 - vDotH, 5.0);
            a += (1.0 - fc) * gVis;
            b += fc * gVis;
        }
    }
    return vec2(a, b) / float(SAMPLE_COUNT);
}

void main() {
    // Nudge NdotV off zero: at exactly grazing incidence the integral is singular.
    float nDotV = max(vUv.x, 1e-3);
    fragColor = integrateBrdf(nDotV, vUv.y);
}
