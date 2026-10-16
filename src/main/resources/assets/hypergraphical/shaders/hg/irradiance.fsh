#version 330 core

// Cosine convolution of the captured environment, for the diffuse term.
//
// With cosine-weighted importance sampling the estimator collapses to a plain average of the
// sampled radiance, which is E/pi. That is the convention the shading pass expects, so the
// diffuse term there is albedo * irradiance with no extra 1/pi.
//
// Sampled from mip 2 of the source rather than mip 0: irradiance is an extremely
// low-frequency signal, and prefiltering the input this way removes most of the sampling
// noise for free.

#hg_include "hypergraphical:shaders/hg/ibl_common.glsl"

#ifndef HG_SAMPLES
#define HG_SAMPLES 128
#endif

uniform samplerCube uEnvironment;
uniform int uFace;
uniform bool uDecodeSrgb;

in vec2 vUv;
out vec4 fragColor;

void main() {
    vec3 n = normalize(hgDirectionForFace(uFace, vUv));

    vec3 irradiance = vec3(0.0);
    for (uint i = 0u; i < uint(HG_SAMPLES); ++i) {
        vec2 xi = hgHammersley(i, uint(HG_SAMPLES));
        vec3 l = hgCosineSample(xi, n);
        irradiance += hgDecodeCapture(textureLod(uEnvironment, l, 2.0).rgb, uDecodeSrgb);
    }
    irradiance /= float(HG_SAMPLES);

    fragColor = vec4(irradiance, 1.0);
}
