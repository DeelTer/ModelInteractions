package dev.deelter.modelinteractions.model;

import dev.deelter.modelinteractions.physics.PartType;
import lombok.Getter;
import lombok.Setter;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Static description of one model element plus its pose captured on the last frame.
 * All lengths are in blocks unless the name says px.
 */
@Getter
public class Bone {
	/**
	 * Model-space frame of the parent (identity for roots).
	 */
	final Matrix4f parentModel = new Matrix4f();
	/**
	 * Parent frame -> bone frame, animated rest pose without our deformation.
	 */
	final Matrix4f localRest = new Matrix4f();
	/**
	 * Model-space frame of the bone at rest.
	 */
	final Matrix4f model = new Matrix4f();
	/**
	 * Model-space frame as drawn this frame: the rest frame with every spring offset above it applied.
	 */
	final Matrix4f deformed = new Matrix4f();
	private final int id;
	private final Bone parent;
	private final BodyRegion region;
	private final boolean root;
	/**
	 * Box in the bone's own frame, blocks.
	 */
	private final Vector3f boxMin = new Vector3f(), boxMax = new Vector3f();
	/**
	 * Point of the box farthest from the pivot, bone frame.
	 */
	private final Vector3f tip = new Vector3f();
	@Setter
	boolean visible = true;
	private float lengthPx;
	@Setter
	private PartType type = PartType.STATIC;
	/**
	 * Part name from vanilla models ("left_ear"); null for CPM elements, which have none.
	 */
	@Setter
	private String name;
	/**
	 * Box volume in px^3, and of this bone plus everything attached below it. Used as weight for pushes.
	 */
	@Setter
	private float mass, subtreeMass;

	public Bone(int id, Bone parent, BodyRegion region, boolean root) {
		this.id = id;
		this.parent = parent;
		this.region = region;
		this.root = root;
	}

	public void setBox(Vector3f min, Vector3f max) {
		boxMin.set(min);
		boxMax.set(max);
		float best = -1;
		for (int i = 0; i < 8; i++) {
			float x = (i & 1) == 0 ? min.x : max.x;
			float y = (i & 2) == 0 ? min.y : max.y;
			float z = (i & 4) == 0 ? min.z : max.z;
			float d = x * x + y * y + z * z;
			if (d > best) {
				best = d;
				tip.set(x, y, z);
			}
		}
		// use face center rather than sharp corner for a more natural lever
		Vector3f c = center(new Vector3f());
		tip.lerp(c, 0.35f);
		lengthPx = tip.length() * 16f;
		Vector3f e = new Vector3f(max).sub(min).mul(16f);
		mass = Math.max(0, e.x) * Math.max(0, e.y) * Math.max(0, e.z);
	}

	public Vector3f center(Vector3f dest) {
		return dest.set(boxMin).add(boxMax).mul(0.5f);
	}

	public Matrix4f parentModel() {
		return parentModel;
	}

	public Matrix4f localRest() {
		return localRest;
	}

	public Matrix4f model() {
		return model;
	}

	public Matrix4f deformed() {
		return deformed;
	}

	public boolean visible() {
		return visible;
	}

	/**
	 * Can be hit at all. Hitting a rigid (STATIC) part grabs the nearest movable parent, or turns the torso.
	 */
	public boolean isInteractive() {
		return visible && boxMax.x > boxMin.x;
	}

	/**
	 * This bone if it can move, else its nearest movable ancestor; null means the torso.
	 */
	public Bone movable() {
		Bone b = this;
		while (b != null && !b.type.isGrabbable()) b = b.parent;
		return b;
	}

	public int depthInChain() {
		int d = 0;
		for (Bone b = parent; b != null && !b.root && b.type == type; b = b.parent) d++;
		return d;
	}
}
