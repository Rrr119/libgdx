
package com.badlogic.gdx.graphics.g3d.terrain;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.RenderableProvider;
import com.badlogic.gdx.graphics.g3d.model.MeshPart;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.Pool;
import com.badlogic.gdx.utils.TimeUtils;

/** Renders a height field using a quadtree of chunks and multiple levels of detail.
 * <p>
 * Each quadtree node has its own mesh. Nearby nodes are replaced by their four children, while distant nodes are rendered at a
 * lower resolution. The selection uses {@link Camera#frustum} and {@link Camera#position}, so it works with perspective,
 * orthographic and custom cameras. Call {@link #update(Camera)} once per frame before the {@link ModelBatch} is flushed, or use
 * {@link #render(ModelBatch, Environment, Camera)}.
 * <p>
 * Adjacent chunks with different levels cannot sample exactly the same boundary vertices. A vertical skirt is therefore added
 * around every chunk and hidden below the surface, closing those T-junction cracks. Skirts are enabled by default and can be
 * tuned through {@link Config#skirtDepth}.
 * <p>
 * This class creates OpenGL meshes, so construct and dispose it on the rendering thread.
 * <p>
 * Example:
 *
 * <pre>
 * TerrainLod.Config config = new TerrainLod.Config();
 * config.maxDepth = 4;
 * config.size.set(512f, 64f, 512f);
 * config.material.set(TextureAttribute.createDiffuse(texture));
 * TerrainLod terrain = new TerrainLod(heights, 257, 257, config);
 *
 * // In render():
 * terrain.update(camera);
 * batch.begin(camera);
 * batch.render(terrain, environment);
 * batch.end();
 * </pre>
 */
public class TerrainLod implements RenderableProvider, Disposable {
	public static class Config {
		/** World position of height sample (0, 0). */
		public final Vector3 position = new Vector3();
		/** World size of the height field. The y component scales the normalized height samples. */
		public final Vector3 size = new Vector3(1f, 1f, 1f);
		/** Maximum quadtree depth. The root is level 0. The height map dimensions must support this depth. */
		public int maxDepth = 4;
		/** A node is subdivided while the camera is within this factor times the node's world-space size. */
		public float lodSplitFactor = 1f;
		public boolean frustumCulling = true;
		public boolean skirts = true;
		/** Vertical world-space size of the crack-hiding skirts. A negative value calculates a safe default. */
		public float skirtDepth = -1f;
		public VertexAttributes attributes = MeshBuilder.createAttributes(Usage.Position | Usage.Normal | Usage.TextureCoordinates);
		public Material material = new Material();
	}

	private static final Vector3 WORLD_UP = new Vector3(0f, 1f, 0f);

	private final Config config;
	private final TerrainQuadTree tree;
	private final float[] lodDistances;
	private final ObjectMap<TerrainQuadTree.Node, TerrainChunk> chunksByNode = new ObjectMap<>();
	private final Array<TerrainChunk> chunks = new Array<>();
	private final Array<TerrainQuadTree.Node> visibleNodes = new Array<>();
	private final Array<TerrainChunk> visibleChunks = new Array<>();
	private final int fullDetailTriangleCount;
	private int visibleTriangleCount;
	private long lastSelectionNanos;
	private boolean disposed;

	public TerrainLod (float[] heights, int width, int height) {
		this(heights, width, height, new Config());
	}

	public TerrainLod (float[] heights, int width, int height, Config config) {
		if (config == null) throw new IllegalArgumentException("config cannot be null");
		if (config.material == null) throw new IllegalArgumentException("config.material cannot be null");
		if (config.attributes == null) throw new IllegalArgumentException("config.attributes cannot be null");
		if (config.attributes.getOffset(Usage.Position, -1) < 0)
			throw new IllegalArgumentException("config.attributes must contain a Position attribute");
		if (config.lodSplitFactor <= 0f) throw new IllegalArgumentException("lodSplitFactor must be positive");

		this.config = config;
		final float skirtDepth = config.skirts ? resolveSkirtDepth(heights, width, height, config) : 0f;
		tree = new TerrainQuadTree(heights, width, height, config.position, config.size, config.maxDepth, config.skirts,
			skirtDepth);
		lodDistances = tree.createDefaultLodDistances(config.lodSplitFactor);

		int fullDetailTriangles = 0;
		final Array<TerrainQuadTree.Node> nodes = tree.getNodes();
		for (int i = 0; i < nodes.size; i++) {
			final TerrainChunk chunk = createChunk(nodes.get(i), skirtDepth);
			chunks.add(chunk);
			chunksByNode.put(chunk.node, chunk);
			if (chunk.node.level == tree.getMaxDepth()) fullDetailTriangles += chunk.triangleCount;
		}
		fullDetailTriangleCount = fullDetailTriangles;

		final TerrainChunk rootChunk = chunksByNode.get(tree.root);
		if (rootChunk != null) {
			visibleNodes.add(tree.root);
			visibleChunks.add(rootChunk);
			visibleTriangleCount = rootChunk.triangleCount;
		}
	}

	private static float resolveSkirtDepth (float[] heights, int width, int height, Config config) {
		if (config.skirtDepth >= 0f) return config.skirtDepth;
		if (heights == null || heights.length < width * height || width < 2 || height < 2) return 1f;

		float min = Float.POSITIVE_INFINITY;
		float max = Float.NEGATIVE_INFINITY;
		final int sampleCount = width * height;
		for (int i = 0; i < sampleCount; i++) {
			final float value = heights[i];
			if (value < min) min = value;
			if (value > max) max = value;
		}
		final float heightRange = Math.abs(max - min) * Math.abs(config.size.y);
		final float fallback = Math.max(Math.abs(config.size.x), Math.abs(config.size.z)) * 0.005f;
		return Math.max(heightRange, fallback);
	}

	private TerrainChunk createChunk (TerrainQuadTree.Node node, float skirtDepth) {
		final VertexAttributes attributes = config.attributes;
		final int step = 1 << (tree.getMaxDepth() - node.level);
		final int gridX = node.sampleSpanX / step + 1;
		final int gridY = node.sampleSpanY / step + 1;
		final int vertexSize = attributes.vertexSize / 4;
		final int posOffset = attributes.getOffset(Usage.Position, -1) / 4;
		final int normalOffset = attributes.getOffset(Usage.Normal, -1) / 4;
		final int uvOffset = attributes.getOffset(Usage.TextureCoordinates, -1) / 4;
		final int colorOffset = attributes.getOffset(Usage.ColorUnpacked, -1) / 4;
		final int packedColorOffset = attributes.getOffset(Usage.ColorPacked, -1) / 4;

		final int gridVertexCount = gridX * gridY;
		final int skirtVertexCount = config.skirts ? 4 * (gridX + gridY) : 0;
		final int maxVertices = gridVertexCount + skirtVertexCount;
		final int maxIndices = (2 * (gridX - 1) * (gridY - 1) + (config.skirts ? 4 * ((gridX - 1) + (gridY - 1)) : 0)) * 3;
		if (maxVertices > 65536) throw new GdxRuntimeException(
			"Terrain chunk needs " + maxVertices + " vertices, but indexed meshes support at most 65536");

		final float[] vertices = new float[maxVertices * vertexSize];
		final short[] indices = new short[maxIndices];
		final Vector3 normal = new Vector3();
		final Vector3 tangentX = new Vector3();
		final Vector3 tangentZ = new Vector3();

		for (int y = 0; y < gridY; y++) {
			final int sampleY = node.sampleY + y * step;
			for (int x = 0; x < gridX; x++) {
				final int sampleX = node.sampleX + x * step;
				calculateNormal(sampleX, sampleY, step, normal, tangentX, tangentZ);
				writeVertex(vertices, vertexSize, posOffset, normalOffset, uvOffset, colorOffset, packedColorOffset, y * gridX + x,
					sampleX, sampleY, normal);
			}
		}

		int index = 0;
		for (int y = 0; y < gridY - 1; y++) {
			for (int x = 0; x < gridX - 1; x++) {
				final int c00 = y * gridX + x;
				final int c10 = c00 + 1;
				final int c01 = c00 + gridX;
				final int c11 = c01 + 1;
				indices[index++] = (short)c11;
				indices[index++] = (short)c10;
				indices[index++] = (short)c00;
				indices[index++] = (short)c00;
				indices[index++] = (short)c01;
				indices[index++] = (short)c11;
			}
		}

		int vertex = gridVertexCount;
		if (config.skirts) {
			vertex = addSkirt(vertices, indices, index, vertex, vertexSize, posOffset, normalOffset, uvOffset, colorOffset,
				packedColorOffset, gridX, gridY, step, node, skirtDepth, normal);
		}
		if (vertex != maxVertices) throw new GdxRuntimeException("Unexpected terrain chunk vertex count: " + vertex);
		if (index != maxIndices) throw new GdxRuntimeException("Unexpected terrain chunk index count: " + index);

		final Mesh mesh = new Mesh(true, maxVertices, maxIndices, attributes);
		mesh.setVertices(vertices);
		mesh.setIndices(indices);

		final MeshPart part = new MeshPart("terrain-" + node.level + "-" + node.sampleX + "-" + node.sampleY, mesh, 0,
			indices.length, GL20.GL_TRIANGLES);
		node.bounds.getCenter(part.center);
		node.bounds.getDimensions(part.halfExtents).scl(0.5f);
		part.radius = part.halfExtents.len();
		return new TerrainChunk(node, mesh, part, indices.length / 3);
	}

	private int addSkirt (float[] vertices, short[] indices, int index, int vertex, int vertexSize, int posOffset,
		int normalOffset, int uvOffset, int colorOffset, int packedColorOffset, int gridX, int gridY, int step,
		TerrainQuadTree.Node node, float skirtDepth, Vector3 normal) {
		final int[] edgeTop = new int[4];
		final int[] edgeBottom = new int[4];
		final int[] edgeCount = new int[4];

		final int maxX = node.sampleX + node.sampleSpanX;
		final int maxY = node.sampleY + node.sampleSpanY;
		edgeCount[0] = gridX;
		edgeCount[1] = gridY;
		edgeCount[2] = gridX;
		edgeCount[3] = gridY;

		for (int edge = 0; edge < 4; edge++) {
			final int count = edgeCount[edge];
			edgeTop[edge] = vertex;
			for (int i = 0; i < count; i++) {
				final int sampleX;
				final int sampleY;
				switch (edge) {
				case 0:
					sampleX = node.sampleX + i * step;
					sampleY = node.sampleY;
					normal.set(0f, 0f, -1f);
					break;
				case 1:
					sampleX = maxX;
					sampleY = node.sampleY + i * step;
					normal.set(1f, 0f, 0f);
					break;
				case 2:
					sampleX = maxX - i * step;
					sampleY = maxY;
					normal.set(0f, 0f, 1f);
					break;
				default:
					sampleX = node.sampleX;
					sampleY = maxY - i * step;
					normal.set(-1f, 0f, 0f);
					break;
				}
				writeVertex(vertices, vertexSize, posOffset, normalOffset, uvOffset, colorOffset, packedColorOffset, vertex++,
					sampleX, sampleY, normal);
			}
			edgeBottom[edge] = vertex;
			for (int i = 0; i < count; i++) {
				final int sampleX;
				final int sampleY;
				switch (edge) {
				case 0:
					sampleX = node.sampleX + i * step;
					sampleY = node.sampleY;
					normal.set(0f, 0f, -1f);
					break;
				case 1:
					sampleX = maxX;
					sampleY = node.sampleY + i * step;
					normal.set(1f, 0f, 0f);
					break;
				case 2:
					sampleX = maxX - i * step;
					sampleY = maxY;
					normal.set(0f, 0f, 1f);
					break;
				default:
					sampleX = node.sampleX;
					sampleY = maxY - i * step;
					normal.set(-1f, 0f, 0f);
					break;
				}
				final int bottomVertex = vertex++;
				writeVertex(vertices, vertexSize, posOffset, normalOffset, uvOffset, colorOffset, packedColorOffset, bottomVertex,
					sampleX, sampleY, normal);
				vertices[bottomVertex * vertexSize + posOffset + 1] -= skirtDepth;
			}

			for (int i = 0; i < count - 1; i++) {
				final short top0 = (short)(edgeTop[edge] + i);
				final short top1 = (short)(edgeTop[edge] + i + 1);
				final short bottom0 = (short)(edgeBottom[edge] + i);
				final short bottom1 = (short)(edgeBottom[edge] + i + 1);
				indices[index++] = top0;
				indices[index++] = top1;
				indices[index++] = bottom0;
				indices[index++] = top1;
				indices[index++] = bottom1;
				indices[index++] = bottom0;
			}
		}
		return vertex;
	}

	private void calculateNormal (int sampleX, int sampleY, int step, Vector3 out, Vector3 tangentX, Vector3 tangentZ) {
		final int leftX = Math.max(0, sampleX - step);
		final int rightX = Math.min(tree.getWidth() - 1, sampleX + step);
		final int downY = Math.max(0, sampleY - step);
		final int upY = Math.min(tree.getHeight() - 1, sampleY + step);

		tangentX.set(tree.sampleToWorldX(rightX), tree.sampleToWorldY(rightX, sampleY), tree.sampleToWorldZ(sampleY));
		tangentX.sub(tree.sampleToWorldX(leftX), tree.sampleToWorldY(leftX, sampleY), tree.sampleToWorldZ(sampleY));
		tangentZ.set(tree.sampleToWorldX(sampleX), tree.sampleToWorldY(sampleX, upY), tree.sampleToWorldZ(upY));
		tangentZ.sub(tree.sampleToWorldX(sampleX), tree.sampleToWorldY(sampleX, downY), tree.sampleToWorldZ(downY));
		out.set(tangentZ).crs(tangentX);
		if (out.isZero()) out.set(WORLD_UP);
		out.nor();
	}

	private void writeVertex (float[] vertices, int vertexSize, int posOffset, int normalOffset, int uvOffset, int colorOffset,
		int packedColorOffset, int vertex, int sampleX, int sampleY, Vector3 normal) {
		final int index = vertex * vertexSize;
		vertices[index + posOffset] = tree.sampleToWorldX(sampleX);
		vertices[index + posOffset + 1] = tree.sampleToWorldY(sampleX, sampleY);
		vertices[index + posOffset + 2] = tree.sampleToWorldZ(sampleY);
		if (normalOffset >= 0) {
			vertices[index + normalOffset] = normal.x;
			vertices[index + normalOffset + 1] = normal.y;
			vertices[index + normalOffset + 2] = normal.z;
		}
		if (uvOffset >= 0) {
			vertices[index + uvOffset] = sampleX / (tree.getWidth() - 1f);
			vertices[index + uvOffset + 1] = sampleY / (tree.getHeight() - 1f);
		}
		if (colorOffset >= 0) {
			vertices[index + colorOffset] = Color.WHITE.r;
			vertices[index + colorOffset + 1] = Color.WHITE.g;
			vertices[index + colorOffset + 2] = Color.WHITE.b;
			vertices[index + colorOffset + 3] = Color.WHITE.a;
		}
		if (packedColorOffset >= 0) vertices[index + packedColorOffset] = Color.WHITE.toFloatBits();
	}

	/** Recalculates the visible chunks for the camera. Call this before submitting the terrain to a {@link ModelBatch}. */
	public void update (Camera camera) {
		if (disposed) throw new IllegalStateException("TerrainLod has been disposed");
		final long start = TimeUtils.nanoTime();
		tree.select(camera, lodDistances, config.frustumCulling, visibleNodes);
		visibleChunks.clear();
		visibleTriangleCount = 0;
		for (int i = 0; i < visibleNodes.size; i++) {
			final TerrainChunk chunk = chunksByNode.get(visibleNodes.get(i));
			if (chunk == null) continue;
			visibleChunks.add(chunk);
			visibleTriangleCount += chunk.triangleCount;
		}
		lastSelectionNanos = TimeUtils.nanoTime() - start;
	}

	/** Convenience method that updates the terrain and submits it to the batch with the given environment. */
	public void render (ModelBatch batch, Environment environment, Camera camera) {
		update(camera);
		batch.render(this, environment);
	}

	@Override
	public void getRenderables (Array<Renderable> renderables, Pool<Renderable> pool) {
		for (int i = 0; i < visibleChunks.size; i++) {
			final TerrainChunk chunk = visibleChunks.get(i);
			final Renderable renderable = pool.obtain();
			renderable.worldTransform.idt();
			renderable.meshPart.set(chunk.meshPart);
			renderable.material = config.material;
			renderable.environment = null;
			renderable.bones = null;
			renderable.shader = null;
			renderable.userData = chunk;
			renderables.add(renderable);
		}
	}

	public TerrainQuadTree getTree () {
		return tree;
	}

	public Config getConfig () {
		return config;
	}

	public Material getMaterial () {
		return config.material;
	}

	public void setMaterial (Material material) {
		if (material == null) throw new IllegalArgumentException("material cannot be null");
		config.material = material;
	}

	/** Replaces the per-level subdivision distances. The array length must be at least {@code maxDepth}. */
	public void setLodDistances (float[] distances) {
		if (distances == null || distances.length < tree.getMaxDepth())
			throw new IllegalArgumentException("distances must contain at least maxDepth entries");
		for (int i = 0; i < tree.getMaxDepth(); i++)
			if (!(distances[i] >= 0f)) throw new IllegalArgumentException("LOD distances must be zero or positive");
		System.arraycopy(distances, 0, lodDistances, 0, tree.getMaxDepth());
	}

	/** Replaces the distances with defaults based on the current tree size. */
	public void setLodSplitFactor (float splitFactor) {
		if (splitFactor <= 0f) throw new IllegalArgumentException("splitFactor must be positive");
		config.lodSplitFactor = splitFactor;
		System.arraycopy(tree.createDefaultLodDistances(splitFactor), 0, lodDistances, 0, lodDistances.length);
	}

	public float[] getLodDistances () {
		return lodDistances;
	}

	public Array<TerrainChunk> getChunks () {
		return chunks;
	}

	public Array<TerrainChunk> getVisibleChunks () {
		return visibleChunks;
	}

	public Array<TerrainQuadTree.Node> getVisibleNodes () {
		return visibleNodes;
	}

	public int getVisibleChunkCount () {
		return visibleChunks.size;
	}

	public int getVisibleTriangleCount () {
		return visibleTriangleCount;
	}

	/** Triangle count when every leaf is rendered at full resolution. */
	public int getFullDetailTriangleCount () {
		return fullDetailTriangleCount;
	}

	/** Triangle count of all chunks at all levels. This is the amount of geometry stored on the GPU. */
	public int getTotalTriangleCount () {
		int result = 0;
		for (int i = 0; i < chunks.size; i++)
			result += chunks.get(i).triangleCount;
		return result;
	}

	public long getLastSelectionNanos () {
		return lastSelectionNanos;
	}

	public int getVisibleChunkCount (int level) {
		int result = 0;
		for (int i = 0; i < visibleChunks.size; i++)
			if (visibleChunks.get(i).node.level == level) result++;
		return result;
	}

	@Override
	public void dispose () {
		if (disposed) return;
		disposed = true;
		for (int i = 0; i < chunks.size; i++)
			chunks.get(i).mesh.dispose();
		chunks.clear();
		visibleChunks.clear();
		visibleNodes.clear();
		chunksByNode.clear();
	}
}
