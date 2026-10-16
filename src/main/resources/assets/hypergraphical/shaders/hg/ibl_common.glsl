// Shared helpers for the IBL bake passes and the main PBR shader.
// Included through #hg_include, so this file must not declare a #version.

const float HG_PI = 3.14159265359;

// ---------------------------------------------------------------------------
// Cubemap face geometry
// ---------------------------------------------------------------------------

// Direction for a point on a cubemap face, the exact inverse of the sc/tc/ma table in the
// OpenGL spec's cubemap section. `uv` is (s, t) in [0,1] and `face` follows
// GL_TEXTURE_CUBE_MAP_POSITIVE_X + face.
//
// Get this wrong and reflections still look like reflections; they are just mirrored or
// rotated per face, which only becomes obvious once you orbit the object.
vec3 hgDirectionForFace(int face, vec2 uv) {
    vec2 c = uv * 2.0 - 1.0;
    if (face == 0) return vec3( 1.0, -c.y, -c.x);   // +X
    if (face == 1) return vec3(-1.0, -c.y,  c.x);   // -X
    if (face == 2) return vec3( c.x,  1.0,  c.y);   // +Y
    if (face == 3) return vec3( c.x, -1.0, -c.y);   // -Y
    if (face == 4) return vec3( c.x, -c.y,  1.0);   // +Z
    return                     vec3(-c.x, -c.y, -1.0);   // -Z
}

// ---------------------------------------------------------------------------
// Capture decode
// ---------------------------------------------------------------------------

// A LEVEL capture is a straight copy of Minecraft's framebuffer, which holds gamma-encoded
// screen colour rather than linear radiance. Convolving that directly is wrong twice over:
// averaging in gamma space crushes a bright sky and a dark wall toward the same mid grey, and
// the shading pass then applies its own linear-to-sRGB encode on top, which lifts dark
// reflections into a flat wash. Decode once, here, and every map downstream is linear.
//
// The procedural ENVIRONMENT capture already writes linear values (it has to: its sun disc is
// far brighter than 1.0 and a gamma encode would clip it), so it passes false.
vec3 hgDecodeCapture(vec3 c, bool srgb) {
    vec3 clamped = max(c, vec3(0.0));
    return srgb ? pow(clamped, vec3(2.2)) : clamped;
}

// ---------------------------------------------------------------------------
// Quasi-random sampling
// ---------------------------------------------------------------------------

// Van der Corput radical inverse in base 2, via bit reversal.
float hgRadicalInverse(uint bits) {
    bits = (bits << 16u) | (bits >> 16u);
    bits = ((bits & 0x55555555u) << 1u) | ((bits & 0xAAAAAAAAu) >> 1u);
    bits = ((bits & 0x33333333u) << 2u) | ((bits & 0xCCCCCCCCu) >> 2u);
    bits = ((bits & 0x0F0F0F0Fu) << 4u) | ((bits & 0xF0F0F0F0u) >> 4u);
    bits = ((bits & 0x00FF00FFu) << 8u) | ((bits & 0xFF00FF00u) >> 8u);
    return float(bits) * 2.3283064365386963e-10; // / 0x100000000
}

vec2 hgHammersley(uint i, uint count) {
    return vec2(float(i) / float(count), hgRadicalInverse(i));
}

// Orthonormal basis around `n`. The 0.999 threshold picks the up vector that is least
// parallel to n, keeping the cross product well conditioned at the poles.
mat3 hgBasis(vec3 n) {
    vec3 up = abs(n.z) < 0.999 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
    vec3 tangent = normalize(cross(up, n));
    vec3 bitangent = cross(n, tangent);
    return mat3(tangent, bitangent, n);
}

// GGX/Trowbridge-Reitz importance sample, returning a world-space half-vector around `n`.
vec3 hgImportanceSampleGGX(vec2 xi, vec3 n, float roughness) {
    float a = roughness * roughness;
    float phi = 2.0 * HG_PI * xi.x;
    float cosTheta = sqrt((1.0 - xi.y) / (1.0 + (a * a - 1.0) * xi.y));
    float sinTheta = sqrt(max(0.0, 1.0 - cosTheta * cosTheta));

    vec3 h = vec3(sinTheta * cos(phi), sinTheta * sin(phi), cosTheta);
    return normalize(hgBasis(n) * h);
}

// Cosine-weighted hemisphere sample around `n`.
vec3 hgCosineSample(vec2 xi, vec3 n) {
    float phi = 2.0 * HG_PI * xi.x;
    float cosTheta = sqrt(1.0 - xi.y);
    float sinTheta = sqrt(xi.y);
    vec3 h = vec3(sinTheta * cos(phi), sinTheta * sin(phi), cosTheta);
    return normalize(hgBasis(n) * h);
}

// ---------------------------------------------------------------------------
// Microfacet terms
// ---------------------------------------------------------------------------

float hgDistributionGGX(float nDotH, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float d = nDotH * nDotH * (a2 - 1.0) + 1.0;
    return a2 / max(HG_PI * d * d, 1e-7);
}

// Smith geometry term, Schlick-GGX form. `k` differs between direct and IBL use: the IBL
// variant drops the (r+1)/2 remapping because the split-sum integral already accounts for it.
float hgGeometrySchlick(float nDotX, float k) {
    return nDotX / (nDotX * (1.0 - k) + k);
}

float hgGeometrySmithDirect(float nDotV, float nDotL, float roughness) {
    float r = roughness + 1.0;
    float k = (r * r) / 8.0;
    return hgGeometrySchlick(nDotV, k) * hgGeometrySchlick(nDotL, k);
}

float hgGeometrySmithIbl(float nDotV, float nDotL, float roughness) {
    float k = (roughness * roughness) / 2.0;
    return hgGeometrySchlick(nDotV, k) * hgGeometrySchlick(nDotL, k);
}

vec3 hgFresnelSchlick(float vDotH, vec3 f0) {
    return f0 + (1.0 - f0) * pow(clamp(1.0 - vDotH, 0.0, 1.0), 5.0);
}
