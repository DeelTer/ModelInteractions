package dev.deelter.modelinteractions.cpm;

import com.mojang.authlib.GameProfile;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.config.ConfigKeys;
import com.tom.cpm.shared.config.ModConfig;
import com.tom.cpm.shared.definition.ModelDefinitionLoader;
import com.tom.cpm.shared.io.ModelFile;
import dev.deelter.modelinteractions.ModelInteractions;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Puts a CPM model on a test dummy. Only loaded when CPM is installed.
 */
public final class CpmDummies {
	private CpmDummies() {
	}

	@SuppressWarnings("unchecked")
	public static void wear(GameProfile profile, String name, InputStream in) throws IOException {
		ModelFile file = ModelFile.load(name, in);
		allowInCpmSafety(profile.id());
		ModelDefinitionLoader<GameProfile> loader = MinecraftClientAccess.get().getDefinitionLoader();
		file.registerLocalCache(loader);
		loader.setModel(profile, file.getDataBlock(), true);
	}

	@SuppressWarnings("unchecked")
	public static void forget(GameProfile profile) {
		try {
			ModelDefinitionLoader<GameProfile> loader = MinecraftClientAccess.get().getDefinitionLoader();
			loader.setModel(profile, null, false);
		} catch (RuntimeException ignored) {
		}
	}

	/**
	 * CPM's safety settings would block a model from an unknown player. Allow this dummy the
	 * same way the "Player" tab of CPM's safety screen does. The entry lives in CPM's
	 * in-memory config; CPM only writes it to disk if it saves its config for another reason.
	 */
	private static void allowInCpmSafety(UUID uuid) {
		try {
			ModConfig.getCommonConfig().getEntry(ConfigKeys.PLAYER_SETTINGS).getEntry(uuid.toString())
					.setBoolean(ConfigKeys.ENABLE_MODEL_LOADING.getName(), true);
		} catch (RuntimeException e) {
			ModelInteractions.LOG.warn("Couldn't whitelist dummy in CPM safety settings", e);
		}
	}
}
