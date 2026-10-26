#version 330 core

// Forward PBR shading with image-based lighting from reflection probes.
//
// The lighting model is metallic/smoothness with a split-sum IBL specular term, sitting on
// top of Minecraft's lightmap rather than replacing it. That combination is deliberate: the
// lightmap is the only thing that knows about torches, caves and time of day, while the probe
// is the only thing that knows what the surroundings look like. Using one without the other
// gives either a correctly reflective object that ignores the room it is in, or a
// vanilla-looking object that cannot be metal.

#hg_include "hypergraphical:shaders/hg/ibl_common.glsl"
#hg_include "hypergraphical:shaders/hg/pbr_lib.glsl"

in vec3 vWorldPos;
in vec3 vNormal;
in vec4 vTangent;
in vec2 vUv;
in vec4 vTint;
in vec4 vParams;      // metallic, smoothness, emissiveStrength, probeBlend
in vec2 vLightLevels; // block 0..15, sky 0..15
in float vOcclusion;  // baked ambient occlusion with the instance strength applied, 1 = open
in float vGlow;       // per-sub-mesh glow strength, 0 = none

out vec4 fragColor;

// -- material ----------------------------------------------------------------
uniform sampler2D uAlbedoMap;
uniform sampler2D uEmissiveMap;
uniform sampler2D uMetallicMap;
uniform sampler2D uRoughnessMap;

uniform vec4 uMaterialTint;
uniform vec3 uEmissiveColor;
uniform float uAlphaCutoff;      // 0 disables the cutout test
uniform bool uHasEmissiveMap;
uniform bool uHasMetallicMap;
uniform bool uHasRoughnessMap;
uniform bool uPackedMetallicRoughness;
uniform float uReflectivity;
uniform float uSunSpecularScale;

// -- lighting ----------------------------------------------------------------
uniform sampler2D uLightMap;
uniform sampler2D uBrdfLut;
// The eye as a homogeneous point: (eye position, 1) for a perspective projection,
// (unit direction towards the camera, 0) for an orthographic one. See HgRenderer#setViewEye.
uniform vec4 uViewEye;
uniform vec3 uSunDirection;
uniform vec3 uSunColor;
uniform float uSunIntensity;
uniform float uIblDiffuse;
uniform float uIblSpecular;
uniform bool uBlendPass;

// -- probes ------------------------------------------------------------------
uniform samplerCube uProbeRadianceA;
uniform samplerCube uProbeRadianceB;
uniform samplerCube uProbeIrradianceA;
uniform samplerCube uProbeIrradianceB;
uniform bool uHasProbeA;
uniform bool uHasProbeB;
uniform vec3 uProbeCenterA;
uniform vec3 uProbeCenterB;
uniform vec3 uProbeExtentsA;
uniform vec3 uProbeExtentsB;
uniform float uProbeMaxMipA;
uniform float uProbeMaxMipB;
uniform float uProbeIrradianceMaxMip;
uniform bool uBoxProjection;

// -- fog ---------------------------------------------------------------------
uniform vec4 uFogColor;
uniform float uFogStart;
uniform float uFogEnd;
uniform bool uFogSpherical;

void main() {
    // ---------------------------------------------------------------- surface
    vec4 albedoSample = texture(uAlbedoMap, vUv);

    vec3 albedo = hgSrgbToLinear(albedoSample.rgb)
                * hgSrgbToLinear(uMaterialTint.rgb)
                * hgSrgbToLinear(vTint.rgb);
    float alpha = albedoSample.a * uMaterialTint.a * vTint.a;

    // Cutout is resolved in the solid pass, so a leaf or a grate still writes depth.
    if (uAlphaCutoff > 0.0 && alpha < uAlphaCutoff) {
        discard;
    }
    if (!uBlendPass) {
        alpha = 1.0;
    }

    float metallic = clamp(vParams.x, 0.0, 1.0);
    float smoothness = clamp(vParams.y, 0.0, 1.0);

    if (uHasMetallicMap) {
        vec4 mr = texture(uMetallicMap, vUv);
        metallic *= mr.r;
        // glTF convention: metallic in R, roughness in G of a single map.
        if (uPackedMetallicRoughness) {
            smoothness *= 1.0 - mr.g;
        }
    }
    if (uHasRoughnessMap) {
        smoothness *= 1.0 - texture(uRoughnessMap, vUv).r;
    }

    // A perfect mirror makes the GGX denominator singular and the analytic sun highlight
    // infinite, so the floor is a hair above zero rather than at it.
    float roughness = clamp(1.0 - smoothness, 0.035, 1.0);

    vec3 n = normalize(vNormal);
    // Flip for the back side of a double-sided material; single-sided backfaces are culled
    // before they reach here, so this is free in that case.
    if (!gl_FrontFacing) {
        n = -n;
    }
    // Works for perspective and orthographic projections alike: an orthographic view has
    // parallel view rays, so "towards the camera" is a constant there, not -vWorldPos.
    vec3 v = normalize(uViewEye.xyz - vWorldPos * uViewEye.w);
    float nDotV = max(dot(n, v), 1e-4);
    vec3 reflection = reflect(-v, n);

    vec3 f0 = mix(vec3(0.04), albedo, metallic);
    vec3 diffuseColor = albedo * (1.0 - metallic);

    // ------------------------------------------------------------ vanilla light
    vec2 levels = clamp(vLightLevels, vec2(0.0), vec2(15.0));
    // Vanilla's lightmap is 16x16 indexed by light level; +0.5 samples texel centres and
    // lets the linear filter interpolate between levels. Vanilla multiplies it straight into
    // sRGB colour, so it is decoded here to light the linear albedo by the same amount;
    // used raw, the final encode would lift every dim level well above vanilla.
    vec3 lightmap = hgSrgbToLinear(texture(uLightMap, (levels + 0.5) / 16.0).rgb);
    float skyVisibility = levels.y / 15.0;
    float blockLight = levels.x / 15.0;

    // ---------------------------------------------------------- ambient occlusion
    // Baked per vertex (see OcclusionBaker). "Strong" on purpose: it scales every indirect
    // term in full, the lightmap ambient and probe diffuse below and the probe or fallback
    // specular through a specular-occlusion factor, and also the analytic sun term by half
    // as much, so crevices stay dark in daylight instead of being lit back up by the sun.
    // A glowing surface is lit from within, so glow lifts its occlusion away.
    float ao = mix(clamp(vOcclusion, 0.0, 1.0), 1.0, clamp(vGlow, 0.0, 1.0));
    float specularAo = hgSpecularOcclusion(nDotV, ao, roughness);
    float sunAo = mix(1.0, ao, 0.5);

    // ------------------------------------------------------------ diffuse (IBL)
    vec3 ambient = lightmap;
    if (uHasProbeA && uIblDiffuse > 0.0) {
        vec3 tint = hgIrradianceTint(uProbeIrradianceA, n, uProbeIrradianceMaxMip);
        if (uHasProbeB) {
            tint = mix(tint, hgIrradianceTint(uProbeIrradianceB, n, uProbeIrradianceMaxMip),
                       vParams.w);
        }
        // Only the sky-lit portion of the ambient term should be redirected by the probe:
        // torchlight comes from the torch, not from the environment.
        ambient *= mix(vec3(1.0), tint, uIblDiffuse * skyVisibility);
    }
    vec3 color = diffuseColor * ambient * ao;

    // ----------------------------------------------------------- specular (IBL)
    // The split-sum scale factor, independent of where the radiance comes from.
    vec2 ab = texture(uBrdfLut, vec2(nDotV, roughness)).rg;
    vec3 specularScale = (f0 * ab.x + ab.y) * uIblSpecular * uReflectivity;

    if (uHasProbeA) {
        vec3 ra = uBoxProjection
                ? hgBoxProject(reflection, vWorldPos, uProbeCenterA, uProbeExtentsA)
                : reflection;
        vec3 radiance = hgProbeRadiance(uProbeRadianceA, ra, roughness, uProbeMaxMipA);

        if (uHasProbeB) {
            vec3 rb = uBoxProjection
                    ? hgBoxProject(reflection, vWorldPos, uProbeCenterB, uProbeExtentsB)
                    : reflection;
            radiance = mix(radiance,
                           hgProbeRadiance(uProbeRadianceB, rb, roughness, uProbeMaxMipB),
                           vParams.w);
        }

        // A probe baked outdoors must not light a sealed room. Gating on local light keeps a
        // metal in a dark cave dark, which no amount of probe accuracy would do on its own.
        float gate = max(skyVisibility, blockLight * 0.6);
        color += radiance * specularScale * gate * specularAo;
    } else {
        // No probe: still baking, evicted by the active budget, or probes turned off.
        //
        // Fall back to the vanilla lightmap as a flat, directionless environment. It is a bad
        // reflection - no directionality at all, so a mirror looks like matte paint - but the
        // alternative is worse than wrong. A metal has no diffuse term (diffuseColor is
        // albedo * (1 - metallic)), so with no specular source at all it renders pure black,
        // which reads as a bug rather than as an unlit surface.
        color += lightmap * specularScale * specularAo;
    }

    // -------------------------------------------------------- specular (analytic)
    // Minecraft has no directional light, so without this a smooth surface only ever shows
    // its probe reflection and never catches the sun.
    if (uSunIntensity > 0.0 && uSunSpecularScale > 0.0) {
        vec3 l = normalize(uSunDirection);
        float nDotL = max(dot(n, l), 0.0);
        if (nDotL > 0.0) {
            vec3 h = normalize(v + l);
            float nDotH = max(dot(n, h), 0.0);
            float vDotH = max(dot(v, h), 0.0);

            float d = hgDistributionGGX(nDotH, roughness);
            float g = hgGeometrySmithDirect(nDotV, nDotL, roughness);
            vec3 f = hgFresnelSchlick(vDotH, f0);

            vec3 specular = (d * g * f) / max(4.0 * nDotV * nDotL, 1e-4) * nDotL;
            color += specular * uSunColor * uSunIntensity * uSunSpecularScale * skyVisibility
                   * sunAo;
        }
    }

    // ------------------------------------------------------------------ emissive
    vec3 emissive = hgSrgbToLinear(uEmissiveColor) * vParams.z;
    if (uHasEmissiveMap) {
        emissive *= hgSrgbToLinear(texture(uEmissiveMap, vUv).rgb);
    }
    color += emissive;

    // Glow: the surface's own colour as emission, so a lit lens shows its colour in the
    // dark. Like emissive it is never occluded.
    color += albedo * vGlow;

    // ------------------------------------------------------------------- output
    color = hgLinearToSrgb(color);

    // Vanilla fog, matched term for term so our geometry disappears into the same fog wall
    // as the terrain around it. Applied after the sRGB encode because Minecraft's fog colour
    // is authored in that space.
    float fogDistance = uFogSpherical
            ? length(vWorldPos)
            : max(length(vWorldPos.xz), abs(vWorldPos.y));
    float fogFade = clamp((fogDistance - uFogStart) / max(uFogEnd - uFogStart, 1e-4), 0.0, 1.0);
    color = mix(color, uFogColor.rgb, fogFade * uFogColor.a);

    fragColor = vec4(color, alpha);
}
