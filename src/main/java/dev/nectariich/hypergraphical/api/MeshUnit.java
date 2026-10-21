package dev.nectariich.hypergraphical.api;

import dev.nectariich.hypergraphical.mesh.MeshBounds;

/**
 * The length unit a model was exported in, as blocks per model unit.
 *
 * <p>An {@code .obj} carries no unit. Blender writes meters, Blockbench writes pixels, and CAD
 * tools write millimeters or inches, so the same knob arrives as {@code 0.03}, {@code 4} or
 * {@code 30} units across. {@link #guess} picks the unit that brings a model closest to the
 * size it is expected to be; it is a starting point to offer, not something to trust blindly.
 */
public enum MeshUnit {
    METERS(1.0F),
    PIXELS(1.0F / 16.0F),
    CENTIMETERS(0.01F),
    MILLIMETERS(0.001F),
    INCHES(0.0254F);

    private final float blocksPerUnit;

    MeshUnit(float blocksPerUnit) {
        this.blocksPerUnit = blocksPerUnit;
    }

    /** One model unit, in blocks (one block is one meter). */
    public float blocksPerUnit() {
        return this.blocksPerUnit;
    }

    /**
     * The unit under which the model's largest extent lands nearest {@code expectedBlocks},
     * nearest by ratio rather than difference, so being twice too big and half too big count
     * the same. Inches are never guessed: they sit too close to centimeters to tell apart.
     */
    public static MeshUnit guess(MeshBounds bounds, float expectedBlocks) {
        float size = bounds.largestSize();
        if (size <= 0.0F || expectedBlocks <= 0.0F) {
            return METERS;
        }
        MeshUnit best = METERS;
        double bestDistance = Double.MAX_VALUE;
        for (MeshUnit unit : values()) {
            if (unit == INCHES) {
                continue;
            }
            double distance = Math.abs(Math.log(size * unit.blocksPerUnit / expectedBlocks));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = unit;
            }
        }
        return best;
    }
}
