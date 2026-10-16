package dev.nectariich.hypergraphical.reflect;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

/**
 * A {@link Camera} that can be placed anywhere.
 *
 * <p>{@code Camera#setPosition} and {@code Camera#setRotation} are {@code protected}, which
 * normally means an access transformer or a mixin. Neither is needed: a subclass can call an
 * inherited protected member on itself, so thirty lines of Java replace a coordinate-mapped
 * AT entry that would have to be re-checked on every Forge bump.
 *
 * <p>{@link #place} runs the normal {@link #setup} first so the inherited private
 * {@code level} and {@code entity} fields are populated &mdash; {@code LevelRenderer} calls
 * {@code getBlockAtCamera()} and {@code getFluidInCamera()} during a render, and both
 * dereference them.
 */
public final class ProbeCamera extends Camera {

    /**
     * Points the camera at {@code position} with the given orientation.
     *
     * @param yaw   Minecraft yaw: 0 is +Z (south), 90 is -X (west)
     * @param pitch Minecraft pitch: positive looks down
     */
    public void place(BlockGetter level, Entity anchor, Vec3 position, float yaw, float pitch) {
        setup(level, anchor, false, false, 1.0F);
        setRotation(yaw, pitch);
        setPosition(position);
    }
}
