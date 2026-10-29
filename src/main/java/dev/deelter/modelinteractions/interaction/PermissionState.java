package dev.deelter.modelinteractions.interaction;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import dev.deelter.modelinteractions.config.MiConfig;
import dev.deelter.modelinteractions.config.PermissionMode;

/**
 * Permissions a player published about themselves. Every client checks these before
 * applying an interaction, so the owner's choice holds even though messages are broadcast.
 */
public record PermissionState(PermissionMode mode, int flags, Set<String> whitelist, Set<String> blacklist) {

	/** Assumed for players that haven't published anything (no mod installed). */
	public static final PermissionState UNKNOWN = new PermissionState(PermissionMode.EVERYONE,
			InteractionKind.GRAB_EARS.bit() | InteractionKind.GRAB_TAIL.bit() | InteractionKind.GRAB_HAIR.bit()
			| InteractionKind.GRAB_OTHER.bit() | InteractionKind.PET.bit() | InteractionKind.HUG.bit() | InteractionKind.TURN_BODY.bit(), Set.of(), Set.of());

	/** Mobs have no owner to ask: everything but soft body parts, nothing stays. */
	public static final PermissionState MOB = new PermissionState(PermissionMode.EVERYONE,
			~(InteractionKind.FREE_MODE.bit() | InteractionKind.PIN.bit() | InteractionKind.GRAB_BODY.bit()), Set.of(), Set.of());

	/** Everything allowed; used for local test dummies. */
	public static final PermissionState ALL = new PermissionState(PermissionMode.EVERYONE, ~InteractionKind.FREE_MODE.bit(), Set.of(), Set.of());
	public static final PermissionState NONE = new PermissionState(PermissionMode.NOBODY, 0, Set.of(), Set.of());

	/** Test dummies in posing mode. */
	public static final PermissionState ALL_FREE = new PermissionState(PermissionMode.EVERYONE, ~0, Set.of(), Set.of());

	public boolean freeMode() {
		return (flags & InteractionKind.FREE_MODE.bit()) != 0;
	}

	public boolean allows(String actorName, InteractionKind kind) {
		String n = actorName == null ? "" : actorName.toLowerCase(Locale.ROOT);
		if (blacklist.contains(n)) return false;
		return switch (mode) {
			case NOBODY -> false;
			case WHITELIST -> whitelist.contains(n) && (flags & kind.bit()) != 0;
			case EVERYONE -> (flags & kind.bit()) != 0;
		};
	}

	public static PermissionState fromConfig(MiConfig.Permissions p) {
		int f = 0;
		if (p.isEars()) f |= InteractionKind.GRAB_EARS.bit();
		if (p.isTail()) f |= InteractionKind.GRAB_TAIL.bit();
		if (p.isHair()) f |= InteractionKind.GRAB_HAIR.bit();
		if (p.isLimbs()) f |= InteractionKind.GRAB_LIMBS.bit();
		if (p.isHead()) f |= InteractionKind.GRAB_HEAD.bit();
		if (p.isOther()) f |= InteractionKind.GRAB_OTHER.bit();
		if (p.isPet()) f |= InteractionKind.PET.bit();
		if (p.isHug()) f |= InteractionKind.HUG.bit();
		if (p.isPin()) f |= InteractionKind.PIN.bit();
		if (p.isBody()) f |= InteractionKind.GRAB_BODY.bit();
		if (p.isTurnBody()) f |= InteractionKind.TURN_BODY.bit();
		if (p.isFreeMode()) f |= InteractionKind.FREE_MODE.bit();
		if (p.isCarryMe()) f |= InteractionKind.CARRY.bit();
		if (p.isDragMe()) f |= InteractionKind.DRAG.bit();
		if (p.getLookMode() != dev.deelter.modelinteractions.config.LookMode.NEVER) f |= InteractionKind.LOOK.bit();
		return new PermissionState(p.getMode(), f, lower(p.getWhitelist()), lower(p.getBlacklist()));
	}

	private static Set<String> lower(Iterable<String> names) {
		Set<String> s = new HashSet<>();
		for (String n : names) if (n != null && !n.isBlank()) s.add(n.trim().toLowerCase(Locale.ROOT));
		return s;
	}
}
