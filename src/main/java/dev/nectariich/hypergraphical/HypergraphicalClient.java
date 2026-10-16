package dev.nectariich.hypergraphical;

import dev.nectariich.hypergraphical.compat.EmbeddiumCompat;
import dev.nectariich.hypergraphical.compat.OculusCompat;
import dev.nectariich.hypergraphical.material.MaterialManager;
import dev.nectariich.hypergraphical.mesh.MeshManager;
import dev.nectariich.hypergraphical.reflect.ProbeManager;
import dev.nectariich.hypergraphical.render.HgRenderer;
import dev.nectariich.hypergraphical.scene.HgScene;

/**
 * Client-side singletons and their wiring.
 *
 * <p>Held statically rather than injected, because there is exactly one client, one GL context
 * and one scene, and threading them through the Forge event handlers would buy nothing. The
 * split from {@link Hypergraphical} matters more: this class touches GL and is only ever
 * classloaded behind a {@code Dist.CLIENT} check, so a dedicated server never sees it.
 */
public final class HypergraphicalClient {

    private static final HgScene SCENE = new HgScene();
    private static final MaterialManager MATERIALS = new MaterialManager();
    private static final MeshManager MESHES = new MeshManager(MATERIALS);
    private static final ProbeManager PROBES = new ProbeManager();
    private static final HgRenderer RENDERER = new HgRenderer(SCENE, MESHES, MATERIALS, PROBES);

    private HypergraphicalClient() {}

    /** Called from the mod constructor on the client. Event registration happens in
     *  {@code HgClientEvents}, which is an annotated bus subscriber. */
    public static void bootstrap() {
        Hypergraphical.LOGGER.info("[hypergraphical] Client bootstrap");
        EmbeddiumCompat.logStatus();
        OculusCompat.logStatus();
    }

    public static HgScene scene() {
        return SCENE;
    }

    public static MaterialManager materials() {
        return MATERIALS;
    }

    public static MeshManager meshes() {
        return MESHES;
    }

    public static ProbeManager probes() {
        return PROBES;
    }

    public static HgRenderer renderer() {
        return RENDERER;
    }

    /** Drops every GPU resource. Used on level unload and at shutdown. */
    public static void releaseAll() {
        PROBES.clear();
        MESHES.clear();
        MATERIALS.textures().clear();
        SCENE.clear();
    }
}
