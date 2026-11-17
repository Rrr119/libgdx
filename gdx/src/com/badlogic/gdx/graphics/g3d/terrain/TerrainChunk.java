
package com.badlogic.gdx.graphics.g3d.terrain;

import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.g3d.model.MeshPart;

/** The GPU representation of one {@link TerrainQuadTree.Node}.
 * <p>
 * Every node has a mesh built at its own level of detail. This keeps all terrain meshes available up front, while only nodes
 * selected by {@link TerrainQuadTree#select} are submitted to the {@link com.badlogic.gdx.graphics.g3d.ModelBatch}. */
public class TerrainChunk {
	public final TerrainQuadTree.Node node;
	public final Mesh mesh;
	public final MeshPart meshPart;
	/** Number of triangles in this chunk, including skirts. */
	public final int triangleCount;

	TerrainChunk (TerrainQuadTree.Node node, Mesh mesh, MeshPart meshPart, int triangleCount) {
		this.node = node;
		this.mesh = mesh;
		this.meshPart = meshPart;
		this.triangleCount = triangleCount;
	}

	public int getLevel () {
		return node.level;
	}
}
