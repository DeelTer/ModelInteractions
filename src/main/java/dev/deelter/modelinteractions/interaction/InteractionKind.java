package dev.deelter.modelinteractions.interaction;

import dev.deelter.modelinteractions.physics.PartType;

/**
 * What is being done to a player; each kind maps to one permission flag bit.
 */
public enum InteractionKind {
	GRAB_EARS, GRAB_TAIL, GRAB_HAIR, GRAB_LIMBS, GRAB_HEAD, GRAB_OTHER, PET, HUG, PIN,
	/**
	 * Soft body parts (JELLY); added last so older flag bits keep their meaning.
	 */
	GRAB_BODY,
	/**
	 * Turning / tipping the whole torso.
	 */
	TURN_BODY,
	/**
	 * Not an action but a flag of the owner: "pose me". Everything others let go of keeps its
	 * shape. Only the owner can switch it on, for themselves.
	 */
	FREE_MODE,
	/**
	 * Lifting and throwing (VR, two hands). Mobs: by the server's rules.
	 */
	CARRY,
	/**
	 * Turning your head / camera by holding your head.
	 */
	LOOK,
	/**
	 * Pulling you along by a hand on the ground, and holding you back.
	 */
	DRAG,
	/**
	 * Kept from falling by whoever holds a part of you.
	 */
	CATCH;

	public static final InteractionKind[] VALUES = values();

	public static InteractionKind grabOf(PartType type) {
		return switch (type) {
			case EAR -> GRAB_EARS;
			case TAIL -> GRAB_TAIL;
			case HAIR -> GRAB_HAIR;
			case LIMB -> GRAB_LIMBS;
			case HEAD -> GRAB_HEAD;
			case JELLY -> GRAB_BODY;
			default -> GRAB_OTHER;
		};
	}

	public int bit() {
		return 1 << ordinal();
	}
}
