package dev.deelter.modelinteractions.physics;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Semantic category of a model element. Drives default physics and which
 * interactions are allowed on it.
 */
@Getter
@RequiredArgsConstructor
public enum PartType {
	//        freq  damp  maxDeg inertia grabbable
	EAR(9.0f, 0.35f, 75f, 0.8f, true),
	TAIL(4.5f, 0.25f, 110f, 1.6f, true),
	HAIR(6.0f, 0.45f, 60f, 1.2f, true),
	WING(5.0f, 0.55f, 50f, 0.6f, true),
	/**
	 * Soft body parts (chest, belly): small, fast, barely damped wobble. Never auto-detected.
	 */
	JELLY(11f, 0.15f, 14f, 1.5f, true),
	LIMB(7.0f, 0.80f, 70f, 0.2f, true),
	HEAD(8.0f, 0.90f, 35f, 0.0f, true),
	ACCESSORY(12f, 0.60f, 25f, 0.4f, true),
	STATIC(20f, 1.00f, 0f, 0.0f, false);

	public static final PartType[] VALUES = values();

	/**
	 * Natural angular frequency for a 1-block (16px) long element, rad/s.
	 */
	private final float baseFrequency;
	/**
	 * Damping ratio, 1 = critically damped.
	 */
	private final float dampingRatio;
	private final float maxAngleDeg;
	/**
	 * How strongly body motion swings the element.
	 */
	private final float inertia;
	private final boolean grabbable;

	public PhysicsProfile defaultProfile() {
		return new PhysicsProfile(baseFrequency, dampingRatio, (float) Math.toRadians(maxAngleDeg), inertia, grabbable);
	}
}
