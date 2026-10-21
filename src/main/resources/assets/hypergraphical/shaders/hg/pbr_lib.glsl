// Shading helpers for the main PBR pass.
// Included through #hg_include, so this file must not declare a #version.

// ---------------------------------------------------------------------------
// Colour space
// ---------------------------------------------------------------------------
//
// Albedo, emissive and tint colours are authored in sRGB; lighting has to happen in linear
// or metallic/smoothness surfaces come out wrong. Half-rough metal reads chalky, highlight
// falloff is too abrupt, and blending two lit surfaces produces the wrong midpoint.
//
// Vanilla Minecraft does none of this and shades directly in gamma space. We re-encode on
// output so a flat, fully-lit diffuse surface still matches an adjacent vanilla block; the
// difference shows up in the shading gradients, which is where it should.
//
// Gamma 2.2 rather than the exact piecewise sRGB curve: one pow instead of a branch and a
// mix, and the two differ only in the bottom 3% of the range.

vec3 hgSrgbToLinear(vec3 c) {
    return pow(max(c, vec3(0.0)), vec3(2.2));
}

vec3 hgLinearToSrgb(vec3 c) {
    return pow(max(c, vec3(0.0)), vec3(1.0 / 2.2));
}

// ---------------------------------------------------------------------------
// Box projection
// ---------------------------------------------------------------------------
//
// Re-intersects the reflection ray with the probe's box and re-centres it on the probe
// origin, which is what Unity's box projection does.
//
// A cubemap is only strictly correct at its capture point. Without this correction a large
// probe makes the reflection look painted on: walk along a reflective floor and the reflected
// wall slides with you instead of staying put. With it, the reflection is parallax-correct
// anywhere the real geometry is close to the box.
//
// All arguments are camera-relative, matching vertex positions.
vec3 hgBoxProject(vec3 dir, vec3 fragPos, vec3 boxCenter, vec3 boxExtents) {
    if (boxExtents.x <= 0.0 || boxExtents.y <= 0.0 || boxExtents.z <= 0.0) {
        return dir;
    }

    // Keep the division finite for components of dir that are (near) zero. step() is used
    // instead of a bvec mix() because mix() with a boolean selector is not universally
    // available at GLSL 330.
    vec3 safeDir = dir + (1.0 - step(vec3(1e-5), abs(dir))) * 1e-5;

    vec3 toMax = (boxCenter + boxExtents - fragPos) / safeDir;
    vec3 toMin = (boxCenter - boxExtents - fragPos) / safeDir;

    // For each axis take the intersection in the direction the ray is actually travelling.
    vec3 positive = step(vec3(0.0), safeDir);
    vec3 candidates = mix(toMin, toMax, positive);

    float distanceToBox = min(min(candidates.x, candidates.y), candidates.z);
    vec3 hit = fragPos + safeDir * distanceToBox;
    return hit - boxCenter;
}

// ---------------------------------------------------------------------------
// Probe sampling
// ---------------------------------------------------------------------------

// Prefiltered radiance for a reflection vector at a given roughness.
vec3 hgProbeRadiance(samplerCube probe, vec3 reflection, float roughness, float maxMip) {
    return textureLod(probe, normalize(reflection), roughness * maxMip).rgb;
}

// Directional bounce tint from an irradiance cubemap, normalised by its own average so it
// changes the *direction* of the ambient light without changing its brightness.
//
// This is the piece that lets probe irradiance coexist with the vanilla lightmap instead of
// fighting it. Multiplying the lightmap by raw irradiance would double-count: the lightmap
// already carries sky and block light including the ambient floor, so the result would be
// far too dark in caves and slightly wrong everywhere. Sampling the top mip of the irradiance
// cube gives the per-face averages blended together, which is a good enough global average
// for one texture fetch.
vec3 hgIrradianceTint(samplerCube probe, vec3 normal, float maxMip) {
    vec3 local = texture(probe, normal).rgb;
    vec3 average = textureLod(probe, normal, maxMip).rgb;
    return local / max(average, vec3(1e-3));
}

// ---------------------------------------------------------------------------
// Specular occlusion
// ---------------------------------------------------------------------------
//
// Ambient occlusion is a cosine-weighted visibility, which is right for diffuse light but
// too blunt for a reflection: a smooth surface reflects one narrow lobe, and at grazing
// angles that lobe tends to escape a crevice even when most of the hemisphere is blocked.
// This is the approximation from Lagarde and de Rousiers, "Moving Frostbite to PBR" (2014):
// it equals ao for rough surfaces seen head on and opens up for smooth ones at grazing
// angles.
float hgSpecularOcclusion(float nDotV, float ao, float roughness) {
    return clamp(pow(nDotV + ao, exp2(-16.0 * roughness - 1.0)) - 1.0 + ao, 0.0, 1.0);
}
