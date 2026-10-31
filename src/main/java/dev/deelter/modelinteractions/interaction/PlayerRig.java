package dev.deelter.modelinteractions.interaction;

import dev.deelter.modelinteractions.config.MiConfig;
import dev.deelter.modelinteractions.model.Bone;
import dev.deelter.modelinteractions.physics.PartType;
import dev.deelter.modelinteractions.physics.PhysicsProfile;
import dev.deelter.modelinteractions.physics.SpringJoint;
import dev.deelter.modelinteractions.wire.Wire;
import lombok.Getter;
import lombok.Setter;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.ref.WeakReference;
import java.util.*;

/**
 * Interaction state of one entity (player or mob): the bone layout of the model it is drawn
 * with, the springs deforming it and its model->world transform.
 * <p>
 * Two backends fill it: a CPM model for players that wear one, or the vanilla entity model.
 */
public class PlayerRig {
	/**
	 * The torso: turns everything about {@link #bodyPivot}. Slow, heavy, can go all the way round.
	 */
	private static final PhysicsProfile BODY_PROFILE = new PhysicsProfile(3.2f, 0.75f, (float) Math.PI * 0.98f, true);
	@Getter
	private final UUID uuid;
	private final Map<Integer, Bone> bones = new LinkedHashMap<>();
	private final Map<Integer, SpringJoint> joints = new HashMap<>();
	/**
	 * Model space point the torso turns about (hips for humanoids, middle for mobs).
	 */
	@Getter
	private final Vector3f bodyPivot = new Vector3f();
	/**
	 * The pivot actually used: glides between the feet (standing) and {@link #bodyPivot} (in the air).
	 */
	@Getter
	private final Vector3f pivot = new Vector3f();
	/**
	 * Middle of the torso, model space; where arms go for a hug.
	 */
	@Getter
	private final Vector3f bodyCenter = new Vector3f();
	/**
	 * Model->world as captured from the last render, relative to the entity's position then.
	 */
	@Getter
	private final Matrix4f capturedRel = new Matrix4f();
	/**
	 * Model space (blocks, model axes) -> world, without the torso turn.
	 */
	@Getter
	private final Matrix4f baseModelToWorld = new Matrix4f();
	@Getter
	private final Matrix4f baseWorldToModel = new Matrix4f();
	/**
	 * Including the torso turn: what the bones are drawn with.
	 */
	@Getter
	private final Matrix4f modelToWorld = new Matrix4f();
	@Getter
	private final Matrix4f worldToModel = new Matrix4f();
	// body motion for inertia, model space
	private WeakReference<Object> definition = new WeakReference<>(null);
	@Getter
	private String modelKey = "";
	@Getter
	private Source source = Source.VANILLA;
	@Getter
	private long sourceSeenNanos;
	private long vanillaSince;
	@Getter
	@Setter
	private int entityId = -1;
	@Getter
	private SpringJoint bodyJoint;
	@Getter
	private float totalMass = 1;
	private boolean pivotInit;
	/**
	 * World point the head of the model turns to (a VR player's head someone holds), or null.
	 */
	private net.minecraft.world.phys.Vec3 lookPoint;
	private long lookNanos;
	@Getter
	@Setter
	private long capturedNanos;
	@Getter
	@Setter
	private boolean worldValid;
	@Getter
	@Setter
	private long lastSeenNanos;
	// pet reaction
	public PlayerRig(UUID uuid) {
		this.uuid = uuid;
	}

	public static PhysicsProfile profileFor(PartType type) {
		MiConfig c = MiConfig.get();
		return type.defaultProfile().scaled(c.getPhysics().getStiffness(), c.getPhysics().getDamping());
	}

	public static boolean isSoft(PartType t) {
		return t == PartType.EAR || t == PartType.TAIL || t == PartType.HAIR || t == PartType.WING || t == PartType.JELLY;
	}

	public boolean isFor(Object def) {
		return definition.get() == def;
	}

	public void markSource(Source s) {
		source = s;
		sourceSeenNanos = System.nanoTime();
		if (s == Source.CPM) vanillaSince = 0;
	}

	/**
	 * CPM drew this player within the last half second; the vanilla backend keeps out.
	 */
	/**
	 * The vanilla hook wants a rig CPM had. Coming back into view it can run just before CPM's
	 * hook; taking over then would flip the model key, and with it the bone numbers others
	 * send and the shape it has. So CPM must stay silent for a second first.
	 */
	public boolean vanillaTakesOver(long now) {
		if (source != Source.CPM) return true;
		if (vanillaSince == 0) vanillaSince = now;
		return now - vanillaSince >= 1_000_000_000L;
	}

	public boolean cpmActive(long now) {
		return source == Source.CPM && now - sourceSeenNanos < 500_000_000L;
	}

	public void rebuild(Object def, Collection<Bone> newBones, String key) {
		Map<Integer, Vector3f> keptPins = new HashMap<>();
		Vector3f keptBody = null;
		if (key.equals(modelKey)) {
			joints.forEach((id, j) -> keptPins.put(id, new Vector3f(j.getPinned())));
			if (bodyJoint != null) keptBody = new Vector3f(bodyJoint.getPinned());
		}
		definition = new WeakReference<>(def);
		modelKey = key;
		bones.clear();
		joints.clear();
		bodyJoint = null;
		for (Bone b : newBones) bones.put(b.getId(), b);
		keptPins.forEach((id, p) -> {
			SpringJoint j = joint(id);
			if (j != null) j.getPinned().set(p);
		});
		if (keptBody != null) bodyJoint().getPinned().set(keptBody);
	}

	/**
	 * Call after bone boxes are known.
	 */
	public void computeMasses() {
		for (Bone b : bones.values()) b.setSubtreeMass(b.getMass());
		// bones are parent-first, so walking backwards adds children before their parents
		Bone[] arr = bones.values().toArray(new Bone[0]);
		float total = 0;
		for (int i = arr.length - 1; i >= 0; i--) {
			Bone b = arr[i];
			if (b.getParent() != null)
				b.getParent().setSubtreeMass(b.getParent().getSubtreeMass() + b.getSubtreeMass());
			total += b.getMass();
		}
		totalMass = Math.max(total, 1);
	}

	public Bone bone(int id) {
		return bones.get(id);
	}

	public Collection<Bone> bones() {
		return Collections.unmodifiableCollection(bones.values());
	}

	public Map<Integer, SpringJoint> joints() {
		return joints;
	}

	/**
	 * Joint for a bone, created on demand. Null when the bone can't move. {@link Wire#BODY_BONE} is the torso.
	 */
	public SpringJoint joint(int id) {
		if (id == Wire.BODY_BONE) return bodyJoint();
		SpringJoint j = joints.get(id);
		if (j != null) return j;
		Bone b = bones.get(id);
		if (b == null || !b.getType().isGrabbable()) return null;
		j = new SpringJoint(profileFor(b.getType()), b.getLengthPx());
		joints.put(id, j);
		return j;
	}

	public SpringJoint bodyJoint() {
		if (bodyJoint == null) bodyJoint = new SpringJoint(BODY_PROFILE, 16f);
		return bodyJoint;
	}

	public void refreshProfiles() {
		joints.forEach((id, j) -> {
			Bone b = bones.get(id);
			if (b != null) j.setProfile(profileFor(b.getType()));
		});
	}

	/**
	 * Torso turn as a model-space matrix: T(pivot) R T(-pivot).
	 */
	public Matrix4f bodyTransform(Matrix4f dest) {
		dest.identity();
		if (bodyJoint == null || bodyJoint.getOffset().lengthSquared() < 1e-8f) return dest;
		Quaternionf q = bodyJoint.rotation(new Quaternionf());
		return dest.translate(pivot).rotate(q).translate(-pivot.x, -pivot.y, -pivot.z);
	}

	/**
	 * Standing on the ground the body tips over its feet, like a real one; lifted off it
	 * (carried, jumping, thrown) it turns about its middle. {@code feetModel}: the ground
	 * contact in model space for this frame.
	 */
	public void updatePivot(boolean grounded, Vector3f feetModel, float dt) {
		Vector3f want = grounded ? feetModel : bodyPivot;
		if (!pivotInit || dt <= 0) {
			pivot.set(want);
			pivotInit = true;
			return;
		}
		pivot.lerp(want, Math.min(1f, dt * 6f));
	}

	/**
	 * Sets the base transform for this frame and derives the turned one.
	 */
	public void setBase(Matrix4f base) {
		baseModelToWorld.set(base);
		baseModelToWorld.invert(baseWorldToModel);
		baseModelToWorld.mul(bodyTransform(new Matrix4f()), modelToWorld);
		modelToWorld.invert(worldToModel);
	}

	/**
	 * Advances all springs. Grabs have already written their targets for this frame.
	 */
	public void step(float dt, MiConfig cfg) {
		if (dt <= 0) return;
		float smoothing = cfg.getPhysics().getReturnSmoothing();

		// released joints spring back to their saved shape
		for (SpringJoint j : joints.values()) {
			if (!j.isHeld()) j.getTarget().zero();
			j.setReturnSmoothing(smoothing);
		}

		aimHead();
		var it = joints.values().iterator();
		while (it.hasNext()) {
			SpringJoint j = it.next();
			if (j.isSleeping()) {
				if (j.isAtRest() && j.getPinned().lengthSquared() == 0) it.remove();
				continue;
			}
			j.step(dt);
		}

		if (bodyJoint != null) {
			if (!bodyJoint.isHeld()) bodyJoint.getTarget().zero();
			bodyJoint.setReturnSmoothing(smoothing);
			if (bodyJoint.isAtRest() && bodyJoint.getPinned().lengthSquared() == 0) bodyJoint = null;
			else if (!bodyJoint.isSleeping()) bodyJoint.step(dt);
		}
	}

	public void lookAt(net.minecraft.world.phys.Vec3 world) {
		lookPoint = world;
		lookNanos = System.nanoTime();
	}

	/**
	 * Turns the head bone towards {@link #lookPoint}; the model faces -Z.
	 */
	private void aimHead() {
		if (lookPoint == null) return;
		if (System.nanoTime() - lookNanos > 500_000_000L) {
			lookPoint = null;
			return;
		}
		Vector3f pm = worldToModel.transformPosition(new Vector3f((float) lookPoint.x, (float) lookPoint.y, (float) lookPoint.z));
		for (Bone b : bones.values()) {
			if (b.getType() != PartType.HEAD || b.getParent() != null && b.getParent().getType() == PartType.HEAD)
				continue;
			SpringJoint j = joint(b.getId());
			if (j == null || j.isHeld()) return;
			Matrix4f inv = b.parentModel().invert(new Matrix4f());
			Vector3f to = inv.transformPosition(pm, new Vector3f()).sub(b.localRest().getTranslation(new Vector3f()));
			Vector3f fwd = b.localRest().transformDirection(new Vector3f(0, 0, -1));
			Vector3f rv = SpringJoint.rotationBetween(fwd, to, new Vector3f());
			j.getTarget().add(SpringJoint.clamp(rv, (float) Math.toRadians(70)));
			return;
		}
	}

	/**
	 * Frames as drawn, for hit tests: a bone bent by its spring, and everything attached to it,
	 * is hit where it is seen, not where it would rest. Same composition as the renderers use:
	 * the offset turns the bone about its own pivot, in its parent's frame.
	 */
	public void updateDeformed() {
		Matrix4f local = new Matrix4f();
		Quaternionf q = new Quaternionf();
		Vector3f p = new Vector3f();
		for (Bone b : bones.values()) {
			SpringJoint j = joints.get(b.getId());
			local.set(b.localRest());
			if (j != null && j.getOffset().lengthSquared() > 1e-8f) {
				local.getTranslation(p);
				local = new Matrix4f().translation(p).rotate(j.rotation(q)).translate(-p.x, -p.y, -p.z).mul(b.localRest());
			}
			Matrix4f parent = b.getParent() == null ? new Matrix4f() : b.getParent().deformed();
			parent.mul(local, b.deformed());
		}
		// details go where their host went; nothing is attached to them, so nothing else depends on this
		Matrix4f moved = new Matrix4f();
		for (Bone b : bones.values()) {
			if (b.getHost() == null) continue;
			b.getHost().deformed().mul(b.getHost().model().invert(moved), moved).mul(b.model(), b.deformed());
		}
	}

	/**
	 * Forces a rebuild (and reclassification) on the next render of this entity.
	 */
	public void invalidate() {
		definition = new WeakReference<>(null);
	}

	public boolean hasDeformation() {
		return !joints.isEmpty() || bodyJoint != null;
	}

	public void resetAll() {
		joints.values().forEach(SpringJoint::reset);
		joints.clear();
		bodyJoint = null;
	}

	public void pinAll() {
		joints.values().forEach(SpringJoint::pinCurrent);
		if (bodyJoint != null) bodyJoint.pinCurrent();
	}

	public enum Source {CPM, VANILLA}
}
