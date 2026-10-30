package dev.deelter.modelinteractions.physics;

import lombok.Getter;
import lombok.Setter;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Damped angular spring. State is a rotation vector (axis * angle) expressed in the
 * parent frame of the element, applied on top of the element's animated rest rotation.
 * <p>
 * The spring pulls towards {@code target + pinned}: {@code target} is the transient goal
 * (grab, pet reaction) and {@code pinned} is a saved deformation.
 */
public class SpringJoint {
	private static final float MAX_DT = 1f / 30f;
	private static final float SLEEP_EPS = 1e-4f;
	private static final float RELEASE_GLIDE = 1.5f;

	@Getter
	private final Vector3f offset = new Vector3f();
	@Getter
	private final Vector3f velocity = new Vector3f();
	@Getter
	private final Vector3f target = new Vector3f();
	@Getter
	private final Vector3f pinned = new Vector3f();
	private final Vector3f force = new Vector3f();
	private final Vector3f tmp = new Vector3f();

	@Getter
	@Setter
	private PhysicsProfile profile;
	@Getter
	@Setter
	private float lengthPx;
	/**
	 * When held the joint follows the target much more stiffly.
	 */
	@Getter
	@Setter
	private boolean held;
	/**
	 * 0..1: after release, glide back slower and with more damping for a moment.
	 */
	@Getter
	@Setter
	private float returnSmoothing;
	/**
	 * 0..1, how readily the joint gives in to a hand: 1 for light parts, less for heavy ones.
	 * Lower means it follows the hand slower and settles back heavier, without bouncing.
	 */
	@Getter
	@Setter
	private float compliance = 1f;
	private boolean wasHeld;
	private float releaseTimer;

	public SpringJoint(PhysicsProfile profile, float lengthPx) {
		this.profile = profile;
		this.lengthPx = lengthPx;
	}

	public static Vector3f clamp(Vector3f v, float max) {
		float l = v.length();
		if (l > max && l > 0) v.mul(max / l);
		return v;
	}

	/**
	 * Rotation vector (axis * angle) of the shortest rotation taking {@code from} to {@code to}.
	 */
	public static Vector3f rotationBetween(Vector3f from, Vector3f to, Vector3f dest) {
		float lf = from.length(), lt = to.length();
		if (lf < 1e-6f || lt < 1e-6f) return dest.zero();
		from.cross(to, dest);
		float s = dest.length();
		float c = from.dot(to);
		float angle = (float) Math.atan2(s, c);
		if (s < 1e-6f) return dest.zero();
		return dest.mul(angle / s);
	}

	/**
	 * Adds an angular acceleration (rad/s^2) for the next step.
	 */
	public void addAcceleration(Vector3f acc) {
		force.add(acc);
	}

	/**
	 * Adds an instantaneous angular velocity change (rad/s).
	 */
	public void addImpulse(Vector3f dv) {
		velocity.add(dv);
	}

	public boolean isSleeping() {
		return !held && force.lengthSquared() == 0 && velocity.lengthSquared() < SLEEP_EPS
				&& tmp.set(target).add(pinned).sub(offset).lengthSquared() < SLEEP_EPS;
	}

	public boolean isAtRest() {
		return isSleeping() && offset.lengthSquared() < SLEEP_EPS;
	}

	public void step(float dt) {
		if (wasHeld && !held) releaseTimer = RELEASE_GLIDE;
		wasHeld = held;
		// substep for stability with stiff held springs
		while (dt > 0) {
			float h = Math.min(dt, MAX_DT / 2f);
			step0(h);
			dt -= h;
		}
		force.zero();
	}

	private void step0(float h) {
		float w = profile.frequencyFor(lengthPx);
		float zeta = profile.dampingRatio();
		if (held) {
			// light parts snap to the hand, heavy ones lag behind it
			w *= 0.8f + 1.7f * compliance;
			zeta = Math.max(zeta, 0.9f);
		} else {
			// weight pulls heavy things back fast and without a bounce: they drop, not drift
			w *= 1f + 1.4f * (1f - compliance);
			zeta = Math.max(zeta, 1f - 0.4f * compliance);
		}
		if (!held && releaseTimer > 0) {
			// the gentle glide is for light parts; a heavy body skips most of it
			float s = returnSmoothing * compliance * Math.min(1f, releaseTimer / (RELEASE_GLIDE * 0.5f));
			w *= 1f - 0.5f * s;
			zeta += (1f - zeta) * s;
			releaseTimer -= h;
		}
		float k = w * w;
		float c = 2f * zeta * w;

		Vector3f goal = tmp.set(target).add(pinned);
		clamp(goal, profile.maxAngle());

		// implicit Euler on a = k*(goal - x) - c*v + f: unconditionally stable for stiff held springs
		float denom = 1f / (1f + h * c + h * h * k);
		velocity.x = (velocity.x + h * (k * (goal.x - offset.x) + force.x)) * denom;
		velocity.y = (velocity.y + h * (k * (goal.y - offset.y) + force.y)) * denom;
		velocity.z = (velocity.z + h * (k * (goal.z - offset.z) + force.z)) * denom;
		offset.fma(h, velocity);

		// soft limit: push back and kill outward velocity past the max angle
		float max = profile.maxAngle() * 1.15f;
		float len = offset.length();
		if (len > max && len > 0) {
			offset.mul(max / len);
			float radial = velocity.dot(offset) / max;
			if (radial > 0) velocity.fma(-radial / max, offset);
		}
	}

	public void reset() {
		offset.zero();
		velocity.zero();
		target.zero();
		pinned.zero();
		force.zero();
		held = false;
		wasHeld = false;
		releaseTimer = 0;
	}

	/**
	 * Starts the gentle glide back, as after letting go (e.g. a saved pose was dropped).
	 */
	public void glide() {
		releaseTimer = RELEASE_GLIDE;
	}

	/**
	 * Pins the current shape so the joint keeps it after release.
	 */
	public void pinCurrent() {
		pinned.set(offset);
		target.zero();
	}

	public Quaternionf rotation(Quaternionf dest) {
		float a = offset.length();
		if (a < 1e-6f) return dest.identity();
		return dest.fromAxisAngleRad(offset.x / a, offset.y / a, offset.z / a, a);
	}
}
