
package com.badlogic.gdx.graphics.g3d.terrain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Array;
import org.junit.Test;

public class TerrainQuadTreeTest {

	@Test
	public void testPartitionsHeightField () {
		TerrainQuadTree tree = createTree(65, 65, 2);

		assertEquals(21, tree.getNodeCount());
		assertEquals(64, tree.root.sampleSpanX);
		assertEquals(64, tree.root.sampleSpanY);
		assertFalse(tree.root.isLeaf());
		assertEquals(32, tree.root.children[0].sampleSpanX);
		assertTrue(tree.root.children[0].children[0].isLeaf());
		assertEquals(16, tree.root.children[0].children[0].sampleSpanX);
	}

	@Test
	public void testSelectsRootWhenCameraIsFarAway () {
		TerrainQuadTree tree = createTree(65, 65, 2);

		TestCamera camera = new TestCamera(1000f, 1000f, 1000f);
		camera.includeAll();

		Array<TerrainQuadTree.Node> selected = new Array<>();
		tree.select(camera, tree.createDefaultLodDistances(1f), false, selected);

		assertEquals(1, selected.size);
		assertEquals(tree.root, selected.first());
	}

	@Test
	public void testRectangularTreeAndDefaultDistances () {
		TerrainQuadTree tree = createTree(33, 65, 2);

		assertEquals(32, tree.root.sampleSpanX);
		assertEquals(64, tree.root.sampleSpanY);
		assertEquals(16, tree.root.children[0].sampleSpanX);
		assertEquals(32, tree.root.children[0].sampleSpanY);
		float[] distances = tree.createDefaultLodDistances(1f);
		assertEquals(64f, distances[0], 0f);
		assertEquals(32f, distances[1], 0f);
	}

	@Test
	public void testNegativeHeightScaleProducesValidBounds () {
		float[] heights = new float[17 * 17];
		for (int i = 0; i < heights.length; i++)
			heights[i] = i % 2;
		TerrainQuadTree tree = new TerrainQuadTree(heights, 17, 17, new Vector3(), new Vector3(16f, -10f, 16f), 2, true, 5f);

		assertTrue(tree.root.bounds.isValid());
		assertTrue(tree.root.bounds.min.y <= tree.root.bounds.max.y);
	}

	@Test
	public void testDefaultLodSelectionCounts () {
		final int samples = 257;
		float[] heights = new float[samples * samples];
		for (int y = 0; y < samples; y++) {
			for (int x = 0; x < samples; x++) {
				heights[y * samples
					+ x] = (float)(Math.sin(x * 0.055f) * 0.35f + Math.cos(y * 0.047f) * 0.3f + Math.sin((x + y) * 0.11f) * 0.12f);
			}
		}
		TerrainQuadTree tree = new TerrainQuadTree(heights, samples, samples, new Vector3(-256f, 0f, -256f),
			new Vector3(512f, 55f, 512f), 4, true, 55f);
		float[] distances = tree.createDefaultLodDistances(1f);
		Array<TerrainQuadTree.Node> selected = new Array<>();

		float[][] positions = {{190f, 110f, 190f}, {100f, 50f, 100f}, {40f, 20f, 40f}, {1000f, 500f, 1000f}};
		int[] expectedCounts = {25, 64, 70, 1};
		for (int i = 0; i < positions.length; i++) {
			TestCamera camera = new TestCamera(positions[i][0], positions[i][1], positions[i][2]);
			camera.includeAll();
			tree.select(camera, distances, false, selected);
			assertEquals(expectedCounts[i], selected.size);
		}
	}

	@Test
	public void testSelectsAllLeavesNearCamera () {
		TerrainQuadTree tree = createTree(65, 65, 2);

		TestCamera camera = new TestCamera(32f, 10f, 32f);
		camera.includeAll();

		Array<TerrainQuadTree.Node> selected = new Array<>();
		float[] distances = tree.createDefaultLodDistances(1f);
		for (int i = 0; i < distances.length; i++)
			distances[i] = Float.MAX_VALUE;
		tree.select(camera, distances, false, selected);

		assertEquals(16, selected.size);
		for (int i = 0; i < selected.size; i++)
			assertEquals(tree.getMaxDepth(), selected.get(i).level);
	}

	@Test
	public void testFrustumCulling () {
		TerrainQuadTree tree = createTree(65, 65, 2);
		TestCamera camera = new TestCamera(32f, 20f, 32f);
		camera.includeAll();
		camera.setPlane(0, 1f, 0f, 0f, -32f);

		Array<TerrainQuadTree.Node> selected = new Array<>();
		float[] distances = tree.createDefaultLodDistances(1f);
		for (int i = 0; i < distances.length; i++)
			distances[i] = Float.MAX_VALUE;
		tree.select(camera, distances, true, selected);
		assertTrue(selected.size > 0);
		assertTrue(selected.size < 16);

		camera.excludeAll();
		tree.select(camera, distances, true, selected);
		assertEquals(0, selected.size);
	}

	@Test
	public void testCustomCamera () {
		TerrainQuadTree tree = createTree(65, 65, 2);
		TestCamera camera = new TestCamera(32f, 20f, 32f);
		camera.includeAll();

		Array<TerrainQuadTree.Node> selected = new Array<>();
		float[] distances = tree.createDefaultLodDistances(1f);
		for (int i = 0; i < distances.length; i++)
			distances[i] = Float.MAX_VALUE;
		tree.select(camera, distances, true, selected);
		assertTrue(selected.size > 0);
	}

	@Test
	public void testDistanceToBoundsSquared () {
		BoundingBox bounds = new BoundingBox(new Vector3(0f, 0f, 0f), new Vector3(10f, 10f, 10f));
		assertEquals(0f, TerrainQuadTree.distanceToBoundsSquared(new Vector3(5f, 5f, 5f), bounds), 0f);
		assertEquals(25f, TerrainQuadTree.distanceToBoundsSquared(new Vector3(15f, 5f, 5f), bounds), 0f);
		assertEquals(75f, TerrainQuadTree.distanceToBoundsSquared(new Vector3(15f, 15f, 15f), bounds), 0f);
	}

	private TerrainQuadTree createTree (int width, int height, int maxDepth) {
		float[] heights = new float[width * height];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++)
				heights[y * width + x] = (float)Math.sin(x * 0.1f) * 0.5f + (float)Math.cos(y * 0.1f) * 0.5f;
		}
		return new TerrainQuadTree(heights, width, height, new Vector3(), new Vector3(64f, 10f, 64f), maxDepth, true, 10f);
	}

	private static class TestCamera extends Camera {
		TestCamera (float x, float y, float z) {
			position.set(x, y, z);
		}

		void includeAll () {
			for (int i = 0; i < frustum.planes.length; i++)
				frustum.planes[i].set(0f, 0f, 0f, 0f);
		}

		void excludeAll () {
			includeAll();
			frustum.planes[0].set(0f, 1f, 0f, -1000f);
		}

		void setPlane (int index, float x, float y, float z, float d) {
			frustum.planes[index].set(x, y, z, d);
		}

		@Override
		public void update () {
		}

		@Override
		public void update (boolean updateFrustum) {
		}
	}
}
