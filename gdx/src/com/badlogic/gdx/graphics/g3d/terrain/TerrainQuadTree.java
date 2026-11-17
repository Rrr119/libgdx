
package com.badlogic.gdx.graphics.g3d.terrain;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.GdxRuntimeException;

/** A camera-aware quadtree over a regular height field.
 * <p>
 * The tree stores the height samples and all spatial information needed to cull and select terrain chunks. It does not create any
 * GPU resources, which makes the selection logic usable on a worker thread and straightforward to unit test. Use
 * {@link TerrainLod} when OpenGL-backed meshes and {@link com.badlogic.gdx.graphics.g3d.Renderable}s are needed.
 * <p>
 * Each node covers a rectangular range of height samples. A node is subdivided while the camera is closer than the distance
 * configured for its level. The default distance for a level is the world-space size of a node at that level, multiplied by a
 * configurable factor. Nodes outside the camera frustum are skipped.
 * <p>
 * The sample ranges of the root are divided by {@code 2^maxDepth}, so {@code width - 1} and {@code height - 1} must be divisible
 * by it. For example a 257 by 257 height map with {@code maxDepth = 4} produces 16 by 16 full-detail chunks. */
public class TerrainQuadTree {
	private static final int MAX_DEPTH = 16;

	/** A quadtree terrain node. Positions are in world space and already include the height scale. */
	public static final class Node {
		public final Node parent;
		public final int level;
		/** First height sample and sample span covered by this node. */
		public final int sampleX, sampleY, sampleSpanX, sampleSpanY;
		/** World-space bounds, including the skirt if skirts are enabled. */
		public final BoundingBox bounds = new BoundingBox();
		/** Children in the order min-x/min-z, max-x/min-z, min-x/max-z, max-x/max-z. Empty for leaf nodes. */
		public final Node[] children = new Node[4];

		private Node (Node parent, int level, int sampleX, int sampleY, int sampleSpanX, int sampleSpanY) {
			this.parent = parent;
			this.level = level;
			this.sampleX = sampleX;
			this.sampleY = sampleY;
			this.sampleSpanX = sampleSpanX;
			this.sampleSpanY = sampleSpanY;
		}

		public boolean isLeaf () {
			return children[0] == null;
		}
	}

	private final float[] heights;
	private final int width, height;
	private final Vector3 position = new Vector3();
	private final Vector3 size = new Vector3();
	private final int maxDepth;
	private final boolean skirts;
	private final float skirtDepth;
	private final float minHeight, maxHeight;
	private final Array<Node> nodes = new Array<>();
	private final float[] levelSizes;
	private int nodeCount;

	public final Node root;

	/** Creates a height-field quadtree with a unit-high terrain. World x and z are in sample units. */
	public TerrainQuadTree (float[] heights, int width, int height, int maxDepth) {
		this(heights, width, height, new Vector3(), new Vector3(1f, 1f, 1f), maxDepth, false, 0f);
	}

	/** @param heights Row-major height samples.
	 * @param width Number of samples on x, at least 2.
	 * @param height Number of samples on z, at least 2.
	 * @param position World position of sample (0, 0).
	 * @param size World size of the height field. The y component scales height samples.
	 * @param maxDepth Maximum quadtree depth. The root is level 0.
	 * @param skirts Whether node bounds include space for a vertical skirt.
	 * @param skirtDepth Vertical skirt depth in world units. */
	public TerrainQuadTree (float[] heights, int width, int height, Vector3 position, Vector3 size, int maxDepth, boolean skirts,
		float skirtDepth) {
		if (heights == null) throw new IllegalArgumentException("heights cannot be null");
		if (position == null || size == null) throw new IllegalArgumentException("position and size cannot be null");
		if (width < 2 || height < 2) throw new IllegalArgumentException("width and height must be at least 2");
		if (heights.length < width * height) throw new IllegalArgumentException("Not enough height samples");
		if (maxDepth < 0 || maxDepth > MAX_DEPTH) throw new IllegalArgumentException("maxDepth must be between 0 and " + MAX_DEPTH);
		if (size.x <= 0f || size.z <= 0f) throw new IllegalArgumentException("Terrain width and depth must be positive");
		if (skirts && skirtDepth < 0f) throw new IllegalArgumentException("skirtDepth cannot be negative");

		final int divisions = 1 << maxDepth;
		if ((width - 1) % divisions != 0 || (height - 1) % divisions != 0)
			throw new GdxRuntimeException("width - 1 and height - 1 must be divisible by 2^maxDepth (" + divisions + ")");

		this.heights = heights;
		this.width = width;
		this.height = height;
		this.position.set(position);
		this.size.set(size);
		this.maxDepth = maxDepth;
		this.skirts = skirts;
		this.skirtDepth = skirtDepth;

		float min = Float.POSITIVE_INFINITY;
		float max = Float.NEGATIVE_INFINITY;
		final int sampleCount = width * height;
		for (int i = 0; i < sampleCount; i++) {
			final float value = heights[i];
			if (value < min) min = value;
			if (value > max) max = value;
		}
		minHeight = min;
		maxHeight = max;

		levelSizes = new float[maxDepth + 1];
		for (int level = 0; level <= maxDepth; level++) {
			final float scale = 1f / (1 << level);
			levelSizes[level] = Math.max(size.x * scale, size.z * scale);
		}

		root = createNode(null, 0, 0, 0, width - 1, height - 1);
	}

	private Node createNode (Node parent, int level, int sampleX, int sampleY, int sampleSpanX, int sampleSpanY) {
		final Node node = new Node(parent, level, sampleX, sampleY, sampleSpanX, sampleSpanY);
		nodes.add(node);
		nodeCount++;

		float min = Float.POSITIVE_INFINITY;
		float max = Float.NEGATIVE_INFINITY;
		final int endX = sampleX + sampleSpanX;
		final int endY = sampleY + sampleSpanY;
		for (int y = sampleY; y <= endY; y++) {
			final int row = y * width;
			for (int x = sampleX; x <= endX; x++) {
				final float value = heights[row + x];
				if (value < min) min = value;
				if (value > max) max = value;
			}
		}

		float minWorldY = position.y + min * size.y;
		float maxWorldY = position.y + max * size.y;
		if (minWorldY > maxWorldY) {
			final float tmp = minWorldY;
			minWorldY = maxWorldY;
			maxWorldY = tmp;
		}
		node.bounds.min.set(sampleToWorldX(sampleX), minWorldY - (skirts ? skirtDepth : 0f), sampleToWorldZ(sampleY));
		node.bounds.max.set(sampleToWorldX(endX), maxWorldY, sampleToWorldZ(endY));
		node.bounds.update();

		if (level < maxDepth) {
			final int spanX = sampleSpanX >> 1;
			final int spanY = sampleSpanY >> 1;
			final int midX = sampleX + spanX;
			final int midY = sampleY + spanY;
			node.children[0] = createNode(node, level + 1, sampleX, sampleY, spanX, spanY);
			node.children[1] = createNode(node, level + 1, midX, sampleY, spanX, spanY);
			node.children[2] = createNode(node, level + 1, sampleX, midY, spanX, spanY);
			node.children[3] = createNode(node, level + 1, midX, midY, spanX, spanY);
		}
		return node;
	}

	/** Selects visible nodes for the camera.
	 * @param camera Camera whose frustum and position are used. Call {@link Camera#update()} first when needed.
	 * @param lodDistances Distance at which each level is subdivided, indexed by node level. Must contain at least
	 *           {@code maxDepth} entries.
	 * @param frustumCulling Whether nodes outside the camera frustum are skipped.
	 * @param out Receives the selected nodes. The array is cleared first. */
	public void select (Camera camera, float[] lodDistances, boolean frustumCulling, Array<Node> out) {
		if (camera == null) throw new IllegalArgumentException("camera cannot be null");
		if (out == null) throw new IllegalArgumentException("out cannot be null");
		if (lodDistances == null || lodDistances.length < maxDepth)
			throw new IllegalArgumentException("lodDistances must contain at least maxDepth entries");

		out.clear();
		select(camera, lodDistances, frustumCulling, root, out);
	}

	private void select (Camera camera, float[] lodDistances, boolean frustumCulling, Node node, Array<Node> out) {
		if (frustumCulling && !camera.frustum.boundsInFrustum(node.bounds)) return;

		if (node.level < maxDepth) {
			final float splitDistance = lodDistances[node.level];
			if (splitDistance >= 0f && distanceToBoundsSquared(camera.position, node.bounds) <= splitDistance * splitDistance) {
				for (int i = 0; i < node.children.length; i++)
					select(camera, lodDistances, frustumCulling, node.children[i], out);
				return;
			}
		}
		out.add(node);
	}

	/** Creates default split distances. A node is subdivided while the camera is within {@code splitFactor} times the node's
	 * largest world-space dimension. A value of 1 gives balanced detail around the camera; larger values favor detail, smaller
	 * values favor fewer draw calls. */
	public float[] createDefaultLodDistances (float splitFactor) {
		if (splitFactor <= 0f) throw new IllegalArgumentException("splitFactor must be positive");
		final float[] result = new float[maxDepth];
		for (int level = 0; level < maxDepth; level++)
			result[level] = levelSizes[level] * splitFactor;
		return result;
	}

	/** Returns the squared distance from a point to the closest point of a bounding box. */
	public static float distanceToBoundsSquared (Vector3 point, BoundingBox bounds) {
		final float dx = point.x < bounds.min.x ? bounds.min.x - point.x : (point.x > bounds.max.x ? point.x - bounds.max.x : 0f);
		final float dy = point.y < bounds.min.y ? bounds.min.y - point.y : (point.y > bounds.max.y ? point.y - bounds.max.y : 0f);
		final float dz = point.z < bounds.min.z ? bounds.min.z - point.z : (point.z > bounds.max.z ? point.z - bounds.max.z : 0f);
		return dx * dx + dy * dy + dz * dz;
	}

	public float sampleToWorldX (int sampleX) {
		return position.x + size.x * sampleX / (width - 1f);
	}

	public float sampleToWorldZ (int sampleY) {
		return position.z + size.z * sampleY / (height - 1f);
	}

	public float sampleToWorldY (int sampleX, int sampleY) {
		return position.y + heights[sampleY * width + sampleX] * size.y;
	}

	public float getHeight (int sampleX, int sampleY) {
		return heights[sampleY * width + sampleX];
	}

	public int getWidth () {
		return width;
	}

	public int getHeight () {
		return height;
	}

	public int getMaxDepth () {
		return maxDepth;
	}

	public int getNodeCount () {
		return nodeCount;
	}

	public float getMinHeight () {
		return minHeight;
	}

	public float getMaxHeight () {
		return maxHeight;
	}

	public boolean hasSkirts () {
		return skirts;
	}

	public float getSkirtDepth () {
		return skirtDepth;
	}

	public Vector3 getPosition () {
		return position;
	}

	public Vector3 getSize () {
		return size;
	}

	/** All nodes in the tree, in parent-before-children order. Do not modify this array. */
	public Array<Node> getNodes () {
		return nodes;
	}
}
