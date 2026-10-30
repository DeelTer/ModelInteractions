package dev.deelter.modelinteractions.physics;

import lombok.With;

/**
 * Physics parameters of one element. Frequency is given for a 16px long element and is
 * scaled by {@link #frequencyFor(float)} so long tails swing slower than short ears.
 */
@With
public record PhysicsProfile(float baseFrequency, float dampingRatio, float maxAngle, float inertia,
                             boolean grabbable) {

	/**
	 * Pendulum-like scaling: omega ~ 1/sqrt(length). Clamped so tiny cubes don't explode.
	 */
	public float frequencyFor(float lengthPx) {
		float l = Math.max(lengthPx, 2f) / 16f;
		return baseFrequency / (float) Math.sqrt(l);
	}

	public PhysicsProfile scaled(float stiffnessMul, float dampingMul) {
		return new PhysicsProfile(baseFrequency * (float) Math.sqrt(stiffnessMul), dampingRatio * dampingMul, maxAngle, inertia, grabbable);
	}
}
