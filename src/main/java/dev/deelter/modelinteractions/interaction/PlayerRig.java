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
	private static final PhysicsProfile BODY_PROFILE = new PhysicsProfile(3.2f, 0.75f, (float) Math.PI * 0.98f, 0f, true);
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
	private final Vector3f prevWorldPos = new Vector3f(), prevWorldVel = new Vector3f(), accelModel = new Vector3f();
	private WeakReference<Object> definition = new WeakReference<>(null);
	@Getter
	private String modelKey = "";
	@Getter
	private Source source = Source.VANILLA;
	@Getter
	private long sourceSeenNanos;
	@Getter
	@Setter
	private int entityId = -1;
	@Getter
	private SpringJoint bodyJoint;
	@Getter
	private float totalMass = 1;
	/**
	 * Model-space point it hangs from while held by one point in the air, or null.
	 */
	@Setter
	private Vector3f hang;
	private boolean pivotInit;
	/**
	 * Last time someone touched this entity; mobs only get ambient physics for a while after.
	 */
	@Getter
	@Setter
	private long touchedNanos;
	/**
	 * Idle sway and body-motion swing this frame (players always, mobs shortly after a touch).
	 */
	@Getter
	@Setter
	private boolean ambient;
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
	private float prevBodyYaw, yawVel, yawAccel;
	private boolean hasMotionHistory;
	// pet reaction
	@Getter
	private float petTimer, wagTimer;
	private float wagPhase;
	private float idleTime;
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

	private static float wrapDeg(float d) {
		d %= 360f;
		if (d >= 180f) d -= 360f;
		if (d < -180f) d += 360f;
		return d;
	}

	public boolean isFor(Object def) {
		return definition.get() == def;
	}

	public void markSource(Source s) {
		source = s;
		sourceSeenNanos = System.nanoTime();
	}

	/**
	 * CPM drew this player within the last half second; the vanilla backend keeps out.
	 */
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
		Vector3f want = hang != null ? hang : grounded ? feetModel : bodyPivot;
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
	 * Track body motion; called once per frame after {@link #setBase}.
	 */
	public void updateMotion(float dt, float bodyYawDeg) {
		Vector3f pos = baseModelToWorld.getTranslation(new Vector3f());
		if (!hasMotionHistory || dt <= 0) {
			prevWorldPos.set(pos);
			prevWorldVel.zero();
			prevBodyYaw = bodyYawDeg;
			yawVel = 0;
			hasMotionHistory = true;
			return;
		}
		Vector3f vel = new Vector3f(pos).sub(prevWorldPos).div(dt);
		if (vel.lengthSquared() > 400) vel.zero(); // teleport
		Vector3f acc = new Vector3f(vel).sub(prevWorldVel).div(dt);
		SpringJoint.clamp(acc, 60f);
		// rotate world acceleration into model space (direction only)
		worldToModel.transformDirection(acc, accelModel).normalize(acc.length());
		if (!Float.isFinite(accelModel.x)) accelModel.zero();
		prevWorldPos.set(pos);
		prevWorldVel.set(vel);

		float dyaw = wrapDeg(bodyYawDeg - prevBodyYaw);
		prevBodyYaw = bodyYawDeg;
		float newYawVel = (float) Math.toRadians(dyaw) / dt;
		yawAccel = Math.clamp((newYawVel - yawVel) / dt, -200f, 200f);
		yawVel = newYawVel;
	}

	public void triggerPet(float seconds) {
		petTimer = Math.max(petTimer, seconds);
		wagTimer = Math.max(wagTimer, seconds + 1.5f);
	}

	/**
	 * Advances all springs. Grabs have already written their targets for this frame.
	 */
	public void step(float dt, MiConfig cfg, boolean ambient) {
		if (dt <= 0) return;
		petTimer = Math.max(0, petTimer - dt);
		wagTimer = Math.max(0, wagTimer - dt);
		wagPhase += dt * 9f;
		idleTime += dt;
		float sway = ambient ? cfg.getPhysics().getIdleSway() : 0;
		float smoothing = cfg.getPhysics().getReturnSmoothing();

		// released joints spring back to their saved shape
		for (SpringJoint j : joints.values()) if (!j.isHeld()) j.getTarget().zero();

		boolean motion = ambient && cfg.getPhysics().isBodyMotion();
		Vector3f tmp = new Vector3f();
		for (Bone b : bones.values()) {
			if (b.isRoot() || !b.getType().isGrabbable()) continue;
			PartType t = b.getType();
			SpringJoint j = joints.get(b.getId());
			if (!isSoft(t)) {
				// rigid parts only sway while they hold a pose someone left on them
				if (j != null && sway > 0 && !j.isHeld() && j.getPinned().lengthSquared() > 1e-6f)
					addSway(j, b.getId(), sway * 0.5f);
				if (j != null) j.setReturnSmoothing(smoothing);
				continue;
			}
			if (j == null && (motion || sway > 0 || petTimer > 0 || wagTimer > 0)) j = joint(b.getId());
			if (j == null) continue;
			j.setReturnSmoothing(smoothing);
			if (sway > 0 && !j.isHeld()) addSway(j, b.getId(), t == PartType.JELLY ? sway * 0.4f : sway);

			// inertia: body acceleration pushes the tip the other way
			if (motion && t.getInertia() > 0) {
				Vector3f accLocal = b.parentModel().invert(new Matrix4f()).transformDirection(accelModel, tmp);
				Vector3f levLocal = b.localRest().transformDirection(new Vector3f(b.getTip()));
				float inv = 1f / Math.max(levLocal.lengthSquared(), 1e-3f);
				Vector3f torque = levLocal.cross(accLocal.negate(), new Vector3f()).mul(inv * t.getInertia() * 0.6f);
				// yaw spin flings tips outwards / backwards
				torque.add(0, -yawAccel * t.getInertia() * 0.05f, 0);
				j.addAcceleration(SpringJoint.clamp(torque, 400f));
			}

			if (!j.isHeld()) {
				if (petTimer > 0 && t == PartType.EAR) {
					// fold ears back: rotate the tip towards +Z (behind) and down
					Vector3f dir = b.localRest().transformDirection(new Vector3f(b.getTip()));
					Vector3f goal = new Vector3f(dir).add(0, dir.length() * 0.3f, dir.length() * 0.9f);
					SpringJoint.rotationBetween(dir, goal, tmp).mul(Math.min(1f, petTimer * 2f) * 0.7f);
					j.getTarget().add(tmp);
				}
				if (wagTimer > 0 && t == PartType.TAIL && b.depthInChain() == 0) {
					float amp = (float) Math.toRadians(28) * Math.min(1f, wagTimer);
					j.getTarget().add(0, (float) Math.sin(wagPhase) * amp, 0);
				}
			}
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

		if (hang != null && (bodyJoint == null || !bodyJoint.isHeld())) {
			// hanging from one point: the body swings until its middle is straight below it
			Vector3f down = baseWorldToModel.transformDirection(new Vector3f(0, -1, 0), new Vector3f());
			Vector3f arm = new Vector3f(bodyCenter).sub(hang);
			if (arm.lengthSquared() > 1e-4f && down.lengthSquared() > 1e-6f) {
				bodyJoint().getTarget().set(SpringJoint.rotationBetween(arm, down.normalize(arm.length()), new Vector3f()));
			}
		}
		if (bodyJoint != null) {
			if (!bodyJoint.isHeld() && hang == null) bodyJoint.getTarget().zero();
			bodyJoint.setReturnSmoothing(smoothing);
			if (bodyJoint.isAtRest() && bodyJoint.getPinned().lengthSquared() == 0) bodyJoint = null;
			else if (!bodyJoint.isSleeping()) bodyJoint.step(dt);
		}
	}

	/**
	 * Slow, per-bone desynchronised wobble so idle and posed parts look alive.
	 */
	private void addSway(SpringJoint j, int id, float amp) {
		float seed = (id * 0.618034f) % 1f * 6.2832f;
		float t = idleTime;
		float x = (float) (Math.sin(t * 0.9f + seed) * 0.6 + Math.sin(t * 1.73f + seed * 2.3f) * 0.4);
		float z = (float) (Math.sin(t * 0.7f + seed * 1.7f) * 0.6 + Math.sin(t * 1.31f + seed * 0.5f) * 0.4);
		j.getTarget().add(x * amp, 0, z * amp);
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
