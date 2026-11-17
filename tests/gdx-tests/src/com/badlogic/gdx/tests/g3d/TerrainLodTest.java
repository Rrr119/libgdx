
package com.badlogic.gdx.tests.g3d;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.terrain.TerrainLod;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Array;

/** Demonstrates quadtree terrain LOD. Drag to orbit, scroll to zoom and press L to compare automatic LOD, full detail and one
 * coarse chunk. The HUD reports visible chunks, submitted triangles and CPU time spent selecting LOD chunks. */
public class TerrainLodTest extends BaseG3dTest {
	private static final int SAMPLES = 257;
	private static final int MAX_DEPTH = 4;
	private static final int AUTO = 0;
	private static final int FULL_DETAIL = 1;
	private static final int COARSE = 2;

	private Texture texture;
	private TerrainLod terrain;
	private Environment environment;
	private SpriteBatch hudBatch;
	private BitmapFont font;
	private float[] automaticDistances;
	private int mode = AUTO;

	@Override
	public void create () {
		super.create();
		showAxes = false;

		texture = new Texture(Gdx.files.internal("data/badlogic.jpg"));
		Material material = new Material(TextureAttribute.createDiffuse(texture));

		TerrainLod.Config config = new TerrainLod.Config();
		config.position.set(-256f, 0f, -256f);
		config.size.set(512f, 55f, 512f);
		config.maxDepth = MAX_DEPTH;
		config.attributes = MeshBuilder.createAttributes(Usage.Position | Usage.Normal | Usage.TextureCoordinates);
		config.material = material;
		terrain = new TerrainLod(createHeights(), SAMPLES, SAMPLES, config);
		automaticDistances = terrain.getLodDistances().clone();

		environment = new Environment();
		environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.45f, 0.45f, 0.45f, 1f));
		environment.add(new DirectionalLight().set(Color.WHITE, -0.5f, -1f, -0.7f));

		cam.position.set(190f, 110f, 190f);
		cam.lookAt(0f, 0f, 0f);
		cam.near = 0.5f;
		cam.far = 1200f;
		cam.update();
		inputController.target.set(0f, 0f, 0f);

		hudBatch = new SpriteBatch();
		font = new BitmapFont();
		font.getData().setScale(0.9f);

		Gdx.input.setInputProcessor(new InputMultiplexer(new InputAdapter() {
			@Override
			public boolean keyDown (int keycode) {
				if (keycode == Input.Keys.L) {
					mode = (mode + 1) % 3;
					applyMode();
					return true;
				}
				if (keycode == Input.Keys.R) {
					cam.position.set(190f, 110f, 190f);
					cam.lookAt(0f, 0f, 0f);
					cam.update();
					return true;
				}
				return false;
			}
		}, inputController));
	}

	private float[] createHeights () {
		float[] heights = new float[SAMPLES * SAMPLES];
		for (int y = 0; y < SAMPLES; y++) {
			for (int x = 0; x < SAMPLES; x++) {
				final float hills = MathUtils.sin(x * 0.055f) * 0.35f + MathUtils.cos(y * 0.047f) * 0.3f;
				final float detail = MathUtils.sin((x + y) * 0.11f) * 0.12f;
				heights[y * SAMPLES + x] = 0.55f + hills + detail;
			}
		}
		return heights;
	}

	private void applyMode () {
		if (mode == AUTO) {
			terrain.setLodDistances(automaticDistances);
			return;
		}
		float[] distances = new float[MAX_DEPTH];
		if (mode == FULL_DETAIL) {
			for (int i = 0; i < distances.length; i++)
				distances[i] = Float.MAX_VALUE;
		}
		terrain.setLodDistances(distances);
	}

	@Override
	protected void render (ModelBatch batch, Array<ModelInstance> instances) {
		terrain.update(cam);
		batch.render(terrain, environment);
	}

	@Override
	public void render () {
		super.render();

		final int fullTriangles = terrain.getFullDetailTriangleCount();
		final int visibleTriangles = terrain.getVisibleTriangleCount();
		final int percent = fullTriangles == 0 ? 0 : visibleTriangles * 100 / fullTriangles;
		final String modeName = mode == AUTO ? "AUTO LOD" : (mode == FULL_DETAIL ? "FULL DETAIL" : "ONE COARSE CHUNK");

		hudBatch.begin();
		font.setColor(Color.WHITE);
		font.draw(hudBatch, "Terrain quadtree LOD: " + modeName, 12f, Gdx.graphics.getHeight() - 14f);
		font.draw(hudBatch, "Visible chunks: " + terrain.getVisibleChunkCount() + " / " + terrain.getChunks().size, 12f,
			Gdx.graphics.getHeight() - 36f);
		font.draw(hudBatch, "Triangles: " + visibleTriangles + " / full " + fullTriangles + " (" + percent + "%)", 12f,
			Gdx.graphics.getHeight() - 58f);
		font.draw(hudBatch, "LOD selection: " + terrain.getLastSelectionNanos() / 1000 + " us", 12f,
			Gdx.graphics.getHeight() - 80f);
		font.draw(hudBatch, "FPS: " + Gdx.graphics.getFramesPerSecond(), 12f, Gdx.graphics.getHeight() - 102f);
		font.draw(hudBatch, "L: compare modes   R: reset camera   Drag/scroll: move camera", 12f, Gdx.graphics.getHeight() - 124f);
		hudBatch.end();
	}

	@Override
	public void dispose () {
		hudBatch.dispose();
		font.dispose();
		terrain.dispose();
		texture.dispose();
		super.dispose();
	}
}
