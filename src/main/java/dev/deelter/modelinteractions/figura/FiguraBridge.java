package dev.deelter.modelinteractions.figura;

import dev.deelter.modelinteractions.ModelInteractions;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

/**
 * Calls {@code events["modelinteractions.touch"]} on a player's Figura avatar. Figura is optional and
 * its internals change between versions, so everything goes through reflection and any mismatch
 * just turns the bridge off.
 */
public final class FiguraBridge {
	static final String EVENT = "MODELINTERACTIONS.TOUCH";
	private static boolean available = FabricLoader.getInstance().isModLoaded("figura");
	private static boolean resolved;
	private static Method avatarFor, run;
	private static Field tickLimit;

	private FiguraBridge() {
	}

	public static boolean isAvailable() {
		return available;
	}

	private static boolean resolve() {
		if (resolved) return available;
		resolved = true;
		try {
			Class<?> manager = Class.forName("org.figuramc.figura.avatar.AvatarManager");
			Class<?> avatar = Class.forName("org.figuramc.figura.avatar.Avatar");
			Class<?> instructions = Class.forName("org.figuramc.figura.avatar.Avatar$Instructions");
			avatarFor = manager.getMethod("getAvatarForPlayer", UUID.class);
			run = avatar.getMethod("run", Object.class, instructions, Object[].class);
			tickLimit = avatar.getField("tick");
			ModelInteractions.LOG.info("Figura found: touch events go to avatars");
		} catch (ReflectiveOperationException | LinkageError e) {
			available = false;
			ModelInteractions.LOG.warn("This Figura version is not supported, no touch events for avatars: {}", e.toString());
		}
		return available;
	}

	/**
	 * Runs the avatar's touch event with one table argument. Silently does nothing without an avatar.
	 */
	public static void touch(UUID player, Map<String, Object> table) {
		if (!available || !resolve()) return;
		try {
			Object avatar = avatarFor.invoke(null, player);
			if (avatar == null) return;
			run.invoke(avatar, EVENT, tickLimit.get(avatar), new Object[]{table});
		} catch (ReflectiveOperationException | RuntimeException e) {
			// a script error is the avatar's business (Figura reports it); this is about us
			ModelInteractions.LOG.debug("Figura touch event failed for {}", player, e);
		}
	}
}
