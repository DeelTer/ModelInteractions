package dev.deelter.modelinteractions.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.deelter.modelinteractions.ModelInteractions;
import lombok.Data;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client config, stored as JSON in config/modelinteractions.json.
 */
@Data
public class MiConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("modelinteractions.json");
	private static MiConfig instance;

	private boolean enabled = true;
	/**
	 * Show other players' deformations on models drawn in GUIs (inventory etc).
	 */
	private boolean deformInGui = false;
	private boolean particles = true;
	private boolean haptics = true;
	/**
	 * Simulate players whose client has no Model Interactions (local only, nobody else sees it).
	 */
	private boolean interactWithoutMod = true;
	private boolean debugOverlay = false;
	/**
	 * Mobs can be grabbed and pushed too (only you see it unless the server runs the plugin).
	 */
	private boolean mobs = true;
	/**
	 * When someone doesn't allow being touched, you can still play with their model on your
	 * screen only. Nothing is sent and they never see it.
	 */
	private boolean localWhenDenied = true;
	/**
	 * The one-time hint about what the mouse does has been shown.
	 */
	private boolean hintSeen = false;
	/**
	 * A light tap in the controller whenever a hand comes within reach of a part.
	 */
	private boolean hoverHaptics = false;
	/**
	 * Say why something can't be lifted when both hands hold it.
	 */
	private boolean carryHints = false;
	/**
	 * VR: a slow stroke over a head pets, both arms around someone hugs (with hearts).
	 */
	private boolean vrGestures = false;
	/**
	 * The slap sound on fast touches.
	 */
	private boolean sounds = false;
	/**
	 * Armour, wool and other layers bend along with the body (vanilla models; CPM draws its own).
	 */
	private boolean deformLayers = true;

	private Physics physics = new Physics();
	private Input input = new Input();
	private Permissions permissions = new Permissions();
	private Reactions reactions = new Reactions();
	private Toy toy = new Toy();

	/**
	 * modelKey -> (cubeId -> PartType name). Manual fixes for the auto classifier, seen only by you.
	 */
	private Map<String, Map<String, String>> overrides = new HashMap<>();
	/**
	 * modelKey -> (cubeId -> PartType name). Types you chose for your own model; sent to everyone.
	 */
	private Map<String, Map<String, String>> ownTypes = new HashMap<>();

	public static MiConfig get() {
		if (instance == null) load();
		return instance;
	}

	public static synchronized void load() {
		MiConfig cfg = null;
		if (Files.exists(FILE)) {
			try (Reader r = Files.newBufferedReader(FILE)) {
				cfg = GSON.fromJson(r, MiConfig.class);
			} catch (IOException | RuntimeException e) {
				ModelInteractions.LOG.error("Failed to read config, using defaults", e);
			}
		}
		instance = cfg != null ? cfg.fixup() : new MiConfig();
		if (cfg == null) save();
	}

	public static synchronized void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer w = Files.newBufferedWriter(FILE)) {
				GSON.toJson(instance, w);
			}
		} catch (IOException e) {
			ModelInteractions.LOG.error("Failed to save config", e);
		}
		ConfigListeners.fire();
	}

	private MiConfig fixup() {
		if (physics == null) physics = new Physics();
		if (input == null) input = new Input();
		if (permissions == null) permissions = new Permissions();
		if (permissions.mode == null) permissions.mode = PermissionMode.EVERYONE;
		if (permissions.whitelist == null) permissions.whitelist = new ArrayList<>();
		if (permissions.blacklist == null) permissions.blacklist = new ArrayList<>();
		if (reactions == null) reactions = new Reactions();
		if (toy == null) toy = new Toy();
		if (toy.allowed == null) toy.allowed = new ArrayList<>();
		if (toy.zones == null) toy.zones = new HashMap<>();
		toy.maxLevel = Math.clamp(toy.maxLevel, 0, 20);
		if (overrides == null) overrides = new HashMap<>();
		if (ownTypes == null) ownTypes = new HashMap<>();
		if (permissions.lookMode == null) permissions.lookMode = LookMode.IDLE;
		physics.returnSmoothing = Math.clamp(physics.returnSmoothing, 0f, 1f);
		physics.idleSway = Math.clamp(physics.idleSway, 0f, 0.2f);
		physics.stiffness = Math.clamp(physics.stiffness, 0.1f, 5f);
		physics.damping = Math.clamp(physics.damping, 0.1f, 5f);
		input.syncHz = Math.clamp(input.syncHz, 2, 30);
		return this;
	}

	@Data
	public static class Physics {
		private float stiffness = 1f;
		private float damping = 1f;
		/**
		 * Ears/tails react to the owner walking, jumping, turning.
		 */
		private boolean bodyMotion = true;
		/**
		 * VR hands brushing parts push them.
		 */
		private boolean touch = true;
		private float touchStrength = 1f;
		/**
		 * 0..1: how gently parts glide back after being released.
		 */
		private float returnSmoothing = 0.6f;
		/**
		 * Idle sway of soft and posed parts, radians. 0 disables.
		 */
		private float idleSway = 0.035f;
	}

	@Data
	public static class Input {
		private float pcReach = 3.0f;
		private float vrGrabRadius = 0.10f;
		/**
		 * Network update rate for held grabs.
		 */
		private int syncHz = 12;
		/**
		 * Grab with the use key (right mouse) when looking at a part.
		 */
		private boolean useKeyGrab = true;
	}

	@Data
	public static class Permissions {
		private PermissionMode mode = PermissionMode.EVERYONE;
		/**
		 * Player names allowed in WHITELIST mode.
		 */
		private List<String> whitelist = new ArrayList<>();
		/**
		 * Player names never allowed.
		 */
		private List<String> blacklist = new ArrayList<>();
		private boolean ears = true;
		private boolean tail = true;
		private boolean hair = true;
		private boolean limbs = true;
		private boolean head = false;
		private boolean other = true;
		private boolean pet = true;
		private boolean hug = true;
		/**
		 * Others may leave a deformation on you that stays after they let go.
		 */
		private boolean pin = false;
		/**
		 * Soft body parts marked JELLY (chest, belly).
		 */
		private boolean body = false;
		/**
		 * Others may turn or tip your whole torso.
		 */
		private boolean turnBody = true;
		/**
		 * "Pose me": everything others let go of on you keeps its shape. Your choice only;
		 * nobody can switch it on for you. Off again with /mi freemode or K (reset).
		 */
		private boolean freeMode = false;
		/**
		 * VR players may pick you up with both hands and throw you. Your client moves you, smoothly.
		 */
		private boolean carryMe = false;
		/**
		 * ...also while you are in VR yourself. Being moved without moving can make you sick.
		 */
		private boolean carryMeInVr = false;
		/**
		 * Someone holding you (on the ground) pulls you along when they walk off, and you can't walk away from them.
		 */
		private boolean dragMe = false;
		/**
		 * Holding your head turns your camera towards whoever holds it. IDLE: only while you haven't
		 * moved the mouse for 2 seconds. In VR only your model's head turns.
		 */
		private LookMode lookMode = LookMode.IDLE;
	}

	/**
	 * External device (Lovense Remote, game mode). Only on servers that announced adult features.
	 */
	@Data
	public static class Toy {
		private boolean enabled = false;
		/**
		 * The Lovense Remote app's local address; only addresses on this computer are used.
		 */
		private String url = "http://127.0.0.1:20010/command";
		/**
		 * 0..20
		 */
		private int maxLevel = 10;
		/**
		 * Names of players whose touches reach the device. You always do.
		 */
		private List<String> allowed = new ArrayList<>();
		/**
		 * The space between the legs counts as a zone.
		 */
		private boolean defaultZone = true;
		/**
		 * modelKey -> extra part ids that count as zones.
		 */
		private Map<String, List<Integer>> zones = new HashMap<>();
	}

	@Data
	public static class Reactions {
		/**
		 * CPM animation names played on your own model; ignored if your model lacks them.
		 */
		private String petAnimation = "pet";
		private String hugAnimation = "hug";
		private String grabbedAnimation = "grabbed";
		private float animationSeconds = 2.5f;
	}
}
