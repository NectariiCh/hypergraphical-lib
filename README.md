# Hypergraphical Lib

Unity-style runtime model rendering for Minecraft **Forge 1.20.1**.

Loads `.obj` files at runtime and draws them at arbitrary world coordinates with PBR
materials — tiling/offset UVs, emissive maps, metallic and smoothness — lit by real
reflection probes that capture the actual world into a cubemap.

- **Mod id:** `hypergraphical`
- **Author:** nectariich
- **Requires:** Forge 47+, **Embeddium** (hard dependency), Java 17
- **Optional:** Oculus (see [Compatibility](#compatibility))

---

## What this is, and what it is not

Hypergraphical does **not** use the vanilla block/item model pipeline. It runs its own
OpenGL pass inside `RenderLevelStageEvent` with its own shaders, VAOs and buffers, writing
into Minecraft's main render target and sharing its depth buffer.

That is not a stylistic choice. A vanilla `BakedModel` has nowhere to put a metallic value, a
smoothness value or a reflection probe; its normals are quantised to three signed bytes and it
has no tangents at all. Everything below follows from owning the pass.

| | Hypergraphical | Vanilla / `forge:obj` |
|---|---|---|
| Placement | any `double` coordinate, full quaternion rotation, non-uniform scale | block grid, or an entity/block-entity transform |
| Geometry | `.obj` loaded at runtime, float32 normals, generated tangents | `.obj` baked to quads, 8-bit normals, no tangents |
| Textures | any pack `.png`, `GL_REPEAT`, real mip chain, anisotropic | atlas only, clamped |
| Shading | metallic/smoothness PBR in linear space, split-sum IBL | diffuse only, per-face shading, gamma space |
| Reflections | true cubemap capture of the world, GGX-prefiltered, box-projected | none |

Both paths exist and both work. `forge:obj` is still the right answer for an `.obj`-shaped
*block* — see `assets/hypergraphical/models/block/forge_obj_example.json` for a template.
Hypergraphical is the answer when you want a model, not a block.

---

## Quick start

```java
// Client thread only. There is no server component; nothing is synchronised.
ModelInstance statue = HypergraphicalAPI.spawn(
        new ResourceLocation("mymod", "models/obj/statue.obj"),
        new ResourceLocation("mymod", "polished_brass"),
        104.5, 71.25, -338.75);

statue.setScale(2.0F)
      .setEulerDegrees(45.0F, 0.0F, 0.0F)
      .setSmoothness(0.9F);          // per-instance override, no material clone

// Give it something real to reflect.
HypergraphicalAPI.addProbe(new Vec3(104.5, 72.0, -338.75), 16.0);
```

Or without writing any code — the mod ships test assets and client commands:

```
/hg spawn hypergraphical:models/obj/test_sphere.obj hypergraphical:chrome ~ ~1 ~
/hg probe add ~ ~2 ~ 16
/hg stats
```

The sphere is deliberate: it samples every direction at once, so a mis-oriented cubemap face
or a broken box projection is obvious rather than merely looking a bit off.

---

## a) OBJ loading

Meshes are `.obj` resource paths, loaded and parsed on a background thread and uploaded on the
render thread. Nothing is converted to JSON, at build time or at runtime.

The importer handles the whole practical subset: `v` / `vt` / `vn`, all four face reference
forms (`v`, `v/vt`, `v//vn`, `v/vt/vn`), negative relative indices, n-gon faces, `usemtl`
grouping, `mtllib`, and `g`/`o` names. Faces are welded on the `(v, vt, vn)` triple, so a cube
exported with 36 loose corners uploads as 24 vertices plus an index buffer.

Three things happen at import that the vanilla pipeline cannot do:

- **Full-precision normals**, kept as float32. If `vn` is absent, smooth normals are generated
  by area-weighted accumulation *across shared positions*, so UV seams do not become creases.
- **Tangent generation** from UV derivatives, Gram-Schmidt orthogonalised, handedness in `w`.
  Nothing samples a normal map yet, but tangents cannot be recovered later.
- **`.mtl`-derived materials**, so an export renders correctly with no material JSON at all.

Optional per-mesh import settings live in a sibling file — for `lantern.obj` that is
`lantern.obj.json`. The option names mirror Forge's `forge:obj` loader (`flip_v`,
`mtl_override`, `emissive_ambient`) so one asset behaves the same through either path:

```json
{ "flip_v": true, "normals": "auto", "scale": 1.0, "auto_materials": true }
```

> **On using Forge's loader.** `ObjLoader.INSTANCE` is what backs the `forge:obj` model JSON
> path, and that works out of the box. It is not used for the PBR path, because its parsed
> geometry is only reachable by baking to `BakedQuad`s — which quantises normals to bytes and
> discards tangents, the two things this renderer most needs. `ObjTokenSource` therefore
> reimplements Forge's tokenisation rules (`#` comments, backslash line continuation,
> whitespace collapsing) rather than taking a hard dependency on internals that would break the
> mesh path on a Forge patch bump.

### MTL PBR extensions

Read alongside the classic fields, as Blender, Substance and Maya write them:

```
map_Kd  albedo      Kd  albedo tint       Pm  metallic
map_Ke  emissive    Ke  emissive colour   Pr  roughness
map_Pm  metallic    d / Tr  opacity       Ns  specular exponent (fallback for Pr)
map_Pr  roughness   norm / map_Bump  normal map (parsed, not yet sampled)
```

Without `Pr`, roughness comes from `Ns` via `sqrt(2 / (Ns + 2))`. Not rigorous, but it turns a
legacy Phong export into something that reads as shiny-versus-matte instead of mirror-smooth.

---

## b) Materials

JSON at `assets/<namespace>/hg_materials/<name>.json`, resolving to the id `namespace:name`.
Texture references are plain resource paths **including** `.png`, so materials can point
straight at pack files or reuse vanilla textures without an atlas round-trip.

```json
{
  "albedo": "mymod:textures/hg/brass_albedo.png",
  "emissive": "mymod:textures/hg/brass_emission.png",
  "emissive_color": [1.0, 0.85, 0.4],
  "emissive_strength": 2.0,

  "metallic": 1.0,
  "smoothness": 0.85,
  "metallic_map": "mymod:textures/hg/brass_mr.png",
  "packed_metallic_roughness": true,

  "tiling": [4.0, 4.0],
  "offset": [0.0, 0.0],

  "tint": [1.0, 1.0, 1.0, 1.0],
  "alpha_mode": "opaque",
  "alpha_cutoff": 0.5,
  "double_sided": false,
  "reflectivity": 1.0,
  "sun_specular": 1.0
}
```

| Key | Meaning |
|---|---|
| `tiling`, `offset` | UV transform, `uv * tiling + offset`. Needs `GL_REPEAT`, which is why these textures are managed outside the vanilla atlas. |
| `metallic`, `smoothness` | Unity's parameterisation. `roughness` is accepted as an alternative and inverted. |
| `metallic_map`, `roughness_map` | Multiply the scalars. With `packed_metallic_roughness`, one map carries metallic in R and roughness in G (glTF convention). |
| `emissive`, `emissive_strength` | Added after lighting and not gated by the lightmap, so it stays lit in a dark cave. Above 1.0 it reaches the range a probe picks up when it re-bakes. |
| `alpha_mode` | `opaque`, `cutout` (tested and discarded, still writes depth, stays in the solid pass) or `blend` (drawn after translucent blocks, no depth write). |
| `reflectivity`, `sun_specular` | Per-material scales on the probe reflection and the analytic sun highlight. |
| `normal_map` | Parsed and carried; **not sampled yet** (see [Not done yet](#not-done-yet)). |

Materials also come from code via `HypergraphicalAPI.registerMaterial`, and from `.mtl` files
automatically. Resolution order is **pack JSON → code → `.mtl`-derived**, so you can drop in an
export, see it render, then override just the one material you want to tune.

Per-instance `setMetallic` / `setSmoothness` / `setEmissiveStrength` / `setTint` /
`setUvOffset` override a material without cloning it, the way a Unity `MaterialPropertyBlock`
does — and **without splitting the instancing batch**, because those values travel in the
per-instance vertex stream.

### Colour space

Albedo, emissive and tint are treated as sRGB, decoded to linear, lit in linear, and
re-encoded on output. Vanilla shades directly in gamma space; metallic/smoothness surfaces do
not survive that — a half-rough metal comes out chalky and highlight falloff is wrong. A flat,
fully-lit diffuse surface still matches an adjacent vanilla block; the difference appears in
the shading gradients, which is where it belongs.

---

## Reflection probes

Real cubemap capture, not screen-space. `LevelRenderer#renderLevel` is called again from the
probe's position with a 90° frustum, once per face, and the result is copied into a cubemap.
Terrain, entities, block entities, the sky and other Hypergraphical instances all appear,
because that method draws all of them. Nothing has to be on screen to be reflected, so a
mirror can show what is behind you or around a corner — exactly where SSR fails.

The capture is then processed into what a PBR shader actually needs (Karis' split-sum
approximation, as Unity and Unreal use):

- **Prefiltered radiance** — the environment convolved with the GGX lobe, one roughness per mip
  level, so `textureLod(cube, R, roughness * maxMip)` gives a correctly blurred reflection from
  one fetch. A plain mip chain is a box filter and loses the stretched highlight entirely.
- **Irradiance** — cosine-convolved, 16px faces, for the diffuse term.
- **BRDF LUT** — 128×128 RG16F, view- and material-independent, baked once and shared.

**Probes are placed automatically.** One per occupied cell of a coarse grid
(`probes.autoCellSize`, 24 blocks by default), positioned at the centroid of that cell's
instances and lifted clear of them - not at the geometric cell centre, which lands inside
terrain often enough to matter, and a probe buried in stone captures six faces of solid block.
A cell shares one probe between everything in it, because one probe per instance would mean
six extra level renders and ~2 MB of VRAM per model. Hand-placed probes via
`HypergraphicalAPI.addProbe` or `/hg probe add` still work alongside them; set
`probes.automatic = false` for manual-only.

**Box projection** re-intersects the reflection ray with the probe's box before sampling, as
Unity's does. Without it a cubemap is only correct at its exact capture point and a reflective
floor looks painted-on, sliding as you walk. Each instance blends the two nearest probes so
crossing a boundary does not snap.

### Why this is affordable

| | |
|---|---|
| One face per frame | A full bake is six extra level renders spread over six frames, then nothing for a `BAKE_ONCE` probe. |
| Far plane, not render distance | Writing to `options.renderDistance()` would rebuild the chunk graph per face; a tighter far plane culls the same chunks through the frustum, for free. |
| Active-probe cap | Only the nearest `probes.maxActive` keep cubemaps (~2 MB each at 128px); the rest are evicted and re-baked on approach. |
| Irradiance keeps brightness | Probe irradiance is normalised by its own average and applied as a directional *tint* on the vanilla lightmap. Multiplying by raw irradiance would double-count — the lightmap already carries sky and block light including the ambient floor. |

### Two implementation details worth knowing

**Capture renders into the main framebuffer**, not an FBO of ours. `renderLevel` rebinds
`Minecraft#getMainRenderTarget()` in four places, so our own FBO would be silently dropped
partway through. Instead we render into the bottom-left `res × res` corner of the real
framebuffer and copy out with `glCopyTexSubImage2D`; all four of those rebinds pass
`setViewport = false`, so the small viewport survives. Capture happens at the very start of a
frame, so the garbage in the corner is overwritten before anything reaches the screen.

**The view matrix carries a 180° roll.** Minecraft's camera basis and GL's per-face cubemap
basis (the `sc`/`tc`/`ma` table in the GL spec) differ by exactly that, on every face. With the
roll applied at capture, the copy into the face needs no flip and the sampling shaders need no
per-face fixups. Get it wrong and reflections look superficially right and are point-mirrored.

### Automatic fallback

When a real capture is not possible, probes fall back to a procedural sky/sun/ground cubemap
generated by a shader — correctly coloured and oriented for the time of day and biome, but
reflecting no actual geometry. This triggers on:

- **an active Oculus shader pack** — Iris' deferred pipeline is not re-entrant;
- **Fabulous graphics** — its post chain composites at window resolution and would stomp our
  viewport (switch to Fancy for real reflections);
- **a capture that threw** — the probe is marked and stops retrying.

`/hg stats` and the F3 overlay report which strategy is actually in effect.

---

## Rendering at arbitrary coordinates

A `ModelInstance` is a placement, closer to a particle than a block: `double` position,
quaternion rotation, non-uniform scale, no collision, no chunk association, not saved, not
synchronised. Instances are cheap when out of range — culling happens before any GPU work.

Positions reach the shader **camera-relative**: the translation is computed in `double` and
narrowed to `float` only after the subtraction. Building a world-space float matrix instead
would visibly jitter geometry a few thousand blocks out.

---

## Performance

The frame is: cull → batch → one buffer upload → draw.

The batch key is `(mesh, sub-mesh, material, probe pair)`. **A thousand copies of one model
with one material is a single draw call**, regardless of where they are, because position,
rotation, scale, tint, lightmap and the per-instance material overrides all live in the
instance stream. Splitting only happens on things that genuinely need different GPU state.

Also:

- **One streaming instance buffer** for the whole frame, orphaned before upload so the driver
  hands back fresh memory instead of stalling on last frame's reads.
- **16-bit indices** whenever a mesh fits in 65536 vertices, which is nearly always.
- **Static instances cache** their rotation/scale matrix; only the translation column is
  rewritten per frame.
- **Throttled light sampling** — at most 10 ticks stale, at most 512 resamples per frame, so a
  mass spawn cannot spike a frame.
- **Async load, sync upload** for both meshes and textures, with a per-frame upload budget.
- **Uniform value caching**, so re-setting an unchanged material value is a float compare
  rather than a driver call.

Culling is a flat scan with a squared-distance reject in front of the frustum test. That is
deliberate: the reject is a few arithmetic ops with no pointer chasing, so ~40k instances cost
well under a millisecond, and a uniform grid would need instances to notify the scene on every
move to stay correct — which is exactly what cannot be guaranteed when consumer code mutates
them directly. Draw submission, not culling, is the bottleneck at this scale. Past a few
hundred thousand instances the answer is GPU culling with an indirect draw buffer, not a grid.

---

## Compatibility

### Embeddium (required)

Declared `mandatory = true` in `mods.toml`. We rely on it for a GL 3.3+ core context — which
is what lets us use instanced arrays unconditionally, Minecraft's own baseline being 3.2 — and
we never touch its chunk meshing, vertex formats or shaders. The only things shared are the
framebuffer and the depth buffer, so terrain and our models occlude each other through
ordinary depth testing.

The actual compatibility requirement is that **every piece of GL state we touch is saved and
restored** (`GlStateGuard`), and that restoration goes through `GlStateManager`/`RenderSystem`
rather than raw GL. Blaze3D caches its own copy of the GL state and skips redundant calls;
setting state behind its back leaves that cache lying, and the corruption then shows up only
after an unrelated mod happens to set the same state.

### Oculus (optional)

Detected entirely through reflection on `IrisApi` — the one part Oculus declares stable.
Compiling against it would mean our jar refuses to load whenever it bumps, for the sake of a
single boolean. With a pack active, probe capture downgrades as described above, and the pass
can be moved to a later stage (`compat.oculusOpaqueStage` / `compat.oculusBlendStage`).

Our geometry is forward-shaded with its own program, so it cannot write into Iris' gbuffers.
It therefore does not receive a pack's shadows or sky lighting. That is inherent to a
mod-owned forward pass, not something a stage change can fix.

### No mixins

Zero mixins and zero access transformers — both are common sources of breakage with renderer
replacements. `Camera#setPosition`/`setRotation` are `protected`, and a subclass calling an
inherited protected member on itself is all that is needed (`ProbeCamera`).

---

## Configuration

`config/hypergraphical-client.toml`. Every value that can cost frames is exposed, because the
right number for integrated graphics and for a 4090 are not the same.

Sections: `general` (master switch, F3 overlay, `glGetError` checks), `render` (distance,
instance cap, culling, pass stages), `shading` (sun highlight, IBL strengths, fog),
`probes` (capture strategy, resolution, faces per frame, active cap, refresh interval, capture
far plane, box projection, blending), `compat` (shader-pack behaviour). Each option carries its
own comment explaining the trade-off.

---

## Commands

Client-side, always available:

```
/hg spawn <mesh> [material] [x y z] [scale]
/hg clear
/hg probe add [x y z] [radius]
/hg probe rebake | clear | list
/hg stats
/hg reload                 recompile our GLSL and invalidate probes
```

Shaders and materials also hot-reload on **F3 + T**.

---

## Building

```
gradlew build          # -> build/libs/hypergraphical-<version>+mc1.20.1.jar
gradlew runClient
gradlew checkAssets    # shader + OBJ validation, see below
```

`gradle.properties` pins `org.gradle.java.home` to a JDK 17 install — ForgeGradle 6 requires
the Gradle daemon itself to run on 17. Adjust that line for your machine.

### Dev-run mods

Embeddium is a hard dependency, so `runClient` would refuse to start without it. It is supplied
automatically: `gradle.properties` pins `embeddium_version`, and the jar is pulled from the
Modrinth maven, remapped to the dev mappings with `fg.deobf`, and put on the run classpath. No
manual copying into `run/mods`.

It goes through a `devRuntime` configuration that only extends `runtimeClasspath`, never
`runtimeElements`. `runtimeOnly` would have worked for the run too, but it would also land in
our published POM, and then anyone depending on this library inherits a production-obfuscated
mod jar they have to `fg.deobf` themselves. `mods.toml` already states the requirement, and
that is the mechanism the mod loader enforces.

Oculus is **off** by default — with no shader pack enabled it behaves identically to not being
installed, so loading it just doubles the third-party mixin surface in the dev environment. To
exercise the shader-pack path (probe downgrade, stage overrides):

```
gradlew runClient -Pdev_with_oculus=true
```

or flip `dev_with_oculus=true` in `gradle.properties`. Version pinned by `oculus_version`.

### Validation tools

Both live in `tools/`, in their own source set so they never reach the mod jar, and both run
against the real main classpath so they exercise the same code the game does.

- **`gradlew checkShaders`** compiles and links every GLSL program in a hidden GL 3.3 core
  context using the same LWJGL Minecraft ships, and lists each program's active uniforms —
  which is how a silently stripped uniform or a mismatched `set()` call gets noticed. Needs a
  GPU, so it is not wired into `check`.
- **`gradlew checkObj`** runs the real `ObjMeshReader` over the shipped test assets and asserts
  index ranges, sub-mesh contiguity, unit normals, orthogonal tangents, finite values, and
  **face winding against normals**. That last one matters most: geometry wound the wrong way is
  invisible under backface culling and reads as a loading bug rather than an asset bug.

---

## Not done yet

- **Normal maps.** Tangents are generated and carried through to the shader, `normal_map` is
  parsed from both JSON and MTL, and the flat-normal placeholder texture exists. Nothing
  samples it. This is the next thing to land and needs no asset migration.
- **Probe capture under Oculus shader packs**, for the re-entrancy reason above.
- **Instances do not light the world.** An emissive material glows but casts no light and does
  not affect the vanilla lightmap.
- **Non-uniform scale skews normals.** The normal matrix is built by normalising the model
  matrix columns, which is exact for rotation plus uniform scale and cheap enough to do
  per-vertex. A per-vertex inverse-transpose would fix it.
- **Cross-batch blend sorting is approximate.** Instances sort within a batch and batches sort
  by depth, but two interleaved blended batches can still resolve in the wrong order. Exact
  sorting means one draw call per instance, which defeats instancing.
- **No GPU culling.** See the culling note under Performance.
- **Metals without a probe fall back to a flat lightmap reflection.** While a probe bakes,
  or with probes off, a metallic surface has no diffuse term to fall back on, so it samples
  the vanilla lightmap as a directionless environment. It reads as lit metal rather than as a
  black hole, but it is not a reflection.

---

## Licence

MIT.
