#version 330 core

// GGX prefilter: convolves the environment with the specular lobe for one roughness, written
// into one mip level of the destination cubemap. The shading pass then reads
// textureLod(cube, R, roughness * maxMip) and gets a correctly blurred reflection from a
// single fetch.
//
// This is the part a plain glGenerateMipmap cannot substitute for. A mip chain is a box
// filter over the cube face; the GGX lobe is anisotropic and stretched with view angle, and
// the difference is exactly what makes a rough metal read as metal rather than as a blurry
// photograph glued to the surface.

#hg_include "hypergraphical:shaders/hg/ibl_common.glsl"

#ifndef HG_SAMPLES
#define HG_SAMPLES 64
#endif

uniform samplerCube uEnvironment;
uniform int uFace;
uniform float uRoughness;
uniform float uEnvironmentSize;
uniform bool uDecodeSrgb;

in vec2 vUv;
out vec4 fragColor;

void main() {
    vec3 n = normalize(hgDirectionForFace(uFace, vUv));

    // Mip 0 is a mirror; skip the whole integral for it.
    if (uRoughness <= 0.0) {
        fragColor = vec4(hgDecodeCapture(textureLod(uEnvironment, n, 0.0).rgb, uDecodeSrgb), 1.0);
        return;
    }

    // Solid angle of one source texel, used to choose a source mip per sample.
    float texelSolidAngle = 4.0 * HG_PI / (6.0 * uEnvironmentSize * uEnvironmentSize);

    vec3 prefiltered = vec3(0.0);
    float totalWeight = 0.0;

    for (uint i = 0u; i < uint(HG_SAMPLES); ++i) {
        vec2 xi = hgHammersley(i, uint(HG_SAMPLES));
        vec3 h = hgImportanceSampleGGX(xi, n, uRoughness);
        vec3 l = 2.0 * dot(n, h) * h - n;

        float nDotL = dot(n, l);
        if (nDotL <= 0.0) {
            continue;
        }

        // Sampling a coarser mip where the lobe is wide keeps bright, small features from
        // turning into fireflies that never converge at 64 samples.
        float nDotH = max(dot(n, h), 0.0);
        float d = hgDistributionGGX(nDotH, uRoughness);
        float pdf = (d * nDotH / (4.0 * max(nDotH, 1e-4))) + 1e-4;
        float sampleSolidAngle = 1.0 / (float(HG_SAMPLES) * pdf);
        float mip = 0.5 * log2(sampleSolidAngle / texelSolidAngle);

        prefiltered += hgDecodeCapture(
                textureLod(uEnvironment, normalize(l), max(mip, 0.0)).rgb, uDecodeSrgb) * nDotL;
        totalWeight += nDotL;
    }

    fragColor = vec4(prefiltered / max(totalWeight, 1e-4), 1.0);
}
