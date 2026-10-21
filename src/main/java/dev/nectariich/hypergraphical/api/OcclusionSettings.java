package dev.nectariich.hypergraphical.api;

/**
 * How a mesh's ambient occlusion is baked; see
 * {@link HypergraphicalAPI#setOcclusionBaking(String, String, OcclusionSettings)}.
 *
 * <p>The bake casts {@code rays} cosine-weighted rays from every vertex into the hemisphere
 * around its normal and counts how many of them hit any triangle of the same mesh within
 * {@code radiusFraction} times the mesh's largest bounds dimension. The open fraction
 * {@code 1 - hits / rays} is then raised to {@code power}, so a power above 1 darkens partly
 * occluded corners further while leaving fully open surfaces at exactly 1.
 *
 * <p>The radius is what decides the look. A small radius only sees the geometry right next
 * to a vertex, so crevices, seams and the foot of a protrusion darken while a broad flat face
 * stays bright; a large radius lets distant parts shade each other and turns the whole model
 * murky. A quarter of the model's size is a good middle for small props.
 *
 * @param rays           rays per vertex, clamped to 8..256. More rays mean less noise between
 *                       neighbouring vertices; the sample set is deterministic either way
 * @param radiusFraction occluder search distance as a fraction of the mesh's largest bounds
 *                       dimension, clamped to 0.001..2
 * @param power          exponent applied to the open fraction, clamped to 0.1..8
 */
public record OcclusionSettings(int rays, float radiusFraction, float power) {

    /** Balanced settings: gentle contact shadows, no overall darkening. */
    public static final OcclusionSettings DEFAULT = new OcclusionSettings(64, 0.25F, 1.0F);

    /**
     * Strong settings for small, detailed props: more rays, a slightly longer reach and a
     * power curve that pushes crevices close to black.
     */
    public static final OcclusionSettings STRONG = new OcclusionSettings(96, 0.3F, 1.6F);

    public OcclusionSettings {
        rays = Math.max(8, Math.min(256, rays));
        radiusFraction = Float.isFinite(radiusFraction)
                ? Math.max(0.001F, Math.min(2.0F, radiusFraction)) : 0.25F;
        power = Float.isFinite(power) ? Math.max(0.1F, Math.min(8.0F, power)) : 1.0F;
    }
}
