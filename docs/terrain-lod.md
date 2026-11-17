# Quadtree terrain LOD

`TerrainLod` adds static height-field terrain with a camera-aware quadtree, per-chunk LOD and crack-hiding skirts to the core
3D API. The implementation is split into two classes:

- `TerrainQuadTree` builds the node hierarchy, culls with any libGDX `Camera`, and selects LOD nodes. It does not touch OpenGL.
- `TerrainLod` creates one `Mesh` and `MeshPart` per node, implements `RenderableProvider`, and exposes render statistics.

Source files:

- `gdx/src/com/badlogic/gdx/graphics/g3d/terrain/TerrainQuadTree.java`
- `gdx/src/com/badlogic/gdx/graphics/g3d/terrain/TerrainLod.java`

## Quick start

The root span must be evenly divisible by `2^maxDepth`. A 257 by 257 height map with `maxDepth = 4` has 16 by 16 full-detail
height cells per leaf chunk.

```java
int width = 257;
int height = 257;
float[] heights = loadNormalizedHeights(width, height);

TerrainLod.Config config = new TerrainLod.Config();
config.position.set(-256f, 0f, -256f);
config.size.set(512f, 64f, 512f);
config.maxDepth = 4;
config.material.set(TextureAttribute.createDiffuse(terrainTexture));

TerrainLod terrain = new TerrainLod(heights, width, height, config);
```

Call `update` with the camera used for rendering, then submit the terrain as a normal renderable provider:

```java
terrain.update(camera);
batch.begin(camera);
batch.render(terrain, environment);
batch.end();
```

There is also a convenience call that does both operations:

```java
batch.begin(camera);
terrain.render(batch, environment, camera);
batch.end();
```

Dispose `TerrainLod` on the rendering thread. The configured `Material` and any textures it references remain owned by the
caller.

## LOD selection

At level zero the whole terrain is one node. A visible node is replaced by its four children while the camera is closer than the
level's split distance. Distant nodes therefore stay as large, cheaper chunks and nearby nodes become progressively more detailed.

The default split distance for level `n` is:

```text
nodeWorldSize(n) * lodSplitFactor
```

`nodeWorldSize(n)` starts at the largest horizontal terrain dimension and halves at each level. The default
`lodSplitFactor` is `1`. Increase it for more detail, or decrease it to reduce draw calls.

You can also provide exact distances in world units:

```java
float[] distances = { 512f, 256f, 128f, 64f };
terrain.setLodDistances(distances);
```

Only the first `maxDepth` values are used. Level `0` controls whether the root is split; the last value controls the split before
the full-detail leaves.

Frustum culling uses `Camera.frustum`. Distance uses `Camera.position`, so both `PerspectiveCamera` and `OrthographicCamera` work,
as do custom `Camera` subclasses that maintain the standard frustum. Update the camera before selecting terrain:

```java
camera.update();
terrain.update(camera);
```

Chunk selection only uses CPU state. `TerrainQuadTree.select` can be called independently, which is useful for tests, streaming
systems or moving selection work off the render thread.

## Cracks between LOD levels

Neighbouring chunks at different levels cannot share identical boundary vertices. Without special handling this produces
T-junction cracks. Each `TerrainLod` chunk includes a vertical skirt around all four edges. Skirt vertices start at the chunk
boundary and extend down, covering the gap from either side of an LOD transition.

The automatic skirt depth is the full vertical range of the height field, which is sufficient for any boundary mismatch. Set it
explicitly when the terrain height scale is fixed and known:

```java
config.skirtDepth = 12f;
```

Set `config.skirts = false` only if chunks are guaranteed to use the same LOD, or if another crack-hiding method is used. Skirts
add 128 triangles per 16 by 16 leaf chunk, so disabling them is a useful diagnostic when measuring geometry cost.

## Performance comparison

The example `tests/gdx-tests/src/com/badlogic/gdx/tests/g3d/TerrainLodTest.java` uses a 257 by 257 terrain and `maxDepth = 4`.
Press `L` to compare automatic LOD, forced full detail and one coarse root chunk. The HUD reports visible chunks, submitted
triangles, full-detail triangles and CPU selection time.

For that example each leaf chunk has a 16 by 16 cell surface and four 16-segment skirts:

```text
surface triangles per leaf: 16 * 16 * 2 = 512
skirt triangles per leaf:   4 * 16 * 2 = 128
total per leaf:             640
full-detail leaves:         16 * 16 = 256
all leaves in view:          163,840 triangles and 256 draw calls
```

Automatic LOD submits only visible nodes. A typical ground-level view selects a small number of detailed leaves near the camera
and one or more coarse nodes in the distance, so submitted triangle count is usually a fraction of the forced full-detail count.
The exact reduction depends on camera height, field of view and `lodSplitFactor`; the HUD and
`getVisibleTriangleCount()` / `getFullDetailTriangleCount()` provide the current values on the target device.

With the default `lodSplitFactor` and frustum culling disabled, the example's selection result is:

| Camera position | Selected chunks | Submitted triangles | Compared with all leaves |
| --- | ---: | ---: | ---: |
| `(190, 110, 190)` | 25 | 16,000 | 9.8% |
| `(100, 50, 100)` | 64 | 40,960 | 25.0% |
| `(40, 20, 40)` | 70 | 44,800 | 27.3% |
| `(1000, 500, 1000)` | 1 | 640 | 0.4% |

These numbers isolate LOD selection from frustum culling and GPU timing. Enabling frustum culling normally reduces them further.
Use the visual example's FPS counter for device-specific render timings.

All levels are uploaded once when `TerrainLod` is constructed. For the example this is 341 nodes and about 218,240 stored
triangles across the complete LOD pyramid. The comparison is between stored geometry and geometry submitted per frame, not just
between two single meshes.

Useful measurements:

```java
int submitted = terrain.getVisibleTriangleCount();
int fullDetail = terrain.getFullDetailTriangleCount();
int drawCalls = terrain.getVisibleChunkCount();
long cpuNanos = terrain.getLastSelectionNanos();
```

## Constraints and tuning

- `maxDepth` is limited to 16. Keep the number of leaves reasonable for the target platform because each node owns a mesh.
- `width - 1` and `height - 1` must be divisible by `2^maxDepth`. Pad or resample the height map when necessary.
- Each indexed chunk is limited to 65,536 vertices. Deeper trees reduce the number of cells per leaf and avoid this limit.
- Terrain geometry is static. Changing the source height array after construction does not update the meshes.
- Set `config.frustumCulling = false` only for debugging or when the caller applies its own culling.
- `config.attributes` supports position, normal, one texture-coordinate set and unpacked or packed vertex color. Position is
  required.
- The material is shared by every chunk. The selected `TerrainChunk` is available through `Renderable.userData` for custom
  renderers or shaders that need per-chunk data.
