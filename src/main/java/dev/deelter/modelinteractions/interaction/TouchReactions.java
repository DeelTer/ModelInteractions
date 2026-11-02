package dev.deelter.modelinteractions.interaction;

import dev.deelter.modelinteractions.ModelInteractions;
import dev.deelter.modelinteractions.config.MiConfig;
import dev.deelter.modelinteractions.figura.FiguraBridge;
import dev.deelter.modelinteractions.model.Bone;
import dev.deelter.modelinteractions.model.BodyRegion;
import dev.deelter.modelinteractions.physics.PartType;
import dev.deelter.modelinteractions.wire.Wire;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns the stream of touches into "someone is touching this part" spans with a start and a stop,
 * and tells the model about them: CPM animations on our own model, Figura events on any avatar.
 * <p>
 * PC holds (grab, petting) and VR fiddling look the same here: every touch refreshes its span, a
 * span ends when the grab is let go or nothing refreshed it for a moment. See docs/REACTIONS.md.
 */
public final class TouchReactions {
	/**
	 * Strength changes smaller than this aren't reported again.
	 */
	private static final float STRENGTH_STEP = 0.05f;
	private static final long UPDATE_INTERVAL_MS = 100;
	private final Map<Key, Span> spans = new LinkedHashMap<>();
	/**
	 * CPM animations we turned on, by name: how many spans hold each one.
	 */
	private final Map<String, Integer> playing = new HashMap<>();
	/**
	 * Values we last gave each {@code mi_<kind>_value} layer.
	 */
	private final Map<String, Integer> layers = new HashMap<>();
	@Setter
	private InteractionManager.Animator animator = (n, v) -> false;
	@Getter
	@Setter
	private static boolean debug;

	/**
	 * A part being touched, or null part fields for the torso / the whole player (hugs).
	 */
	public record Part(int bone, String name, PartType type, BodyRegion region) {
		static final Part WHOLE = new Part(Wire.BODY_BONE, null, PartType.STATIC, BodyRegion.BODY);

		static Part of(PlayerRig rig, int boneId) {
			Bone b = boneId == Wire.BODY_BONE || rig == null ? null : rig.bone(boneId);
			if (b == null) return WHOLE;
			return new Part(boneId, b.getName(), b.getType(), b.getRegion());
		}

		/**
		 * The precise tag: the vanilla part name, or the CPM element number.
		 */
		public String exact() {
			if (bone == Wire.BODY_BONE) return null;
			return name != null && !name.isBlank() ? tag(name) : "part" + bone;
		}

		public String typeTag() {
			return type == PartType.STATIC ? null : tag(type.name());
		}

		public String regionTag() {
			return tag(region.name());
		}
	}

	public enum Kind {
		/**
		 * Holding the part (PC: right mouse held; VR: trigger/grip held).
		 */
		GRAB(0),
		/**
		 * Petting (PC: Shift + right mouse held; VR: the petting gesture).
		 */
		PET(1500),
		/**
		 * A VR hand brushing over the part.
		 */
		TOUCH(400),
		/**
		 * A PC punch with an empty hand.
		 */
		HIT(500),
		/**
		 * A fast VR hand.
		 */
		SLAP(700),
		HUG(2500);
		/**
		 * How long a span lasts without being refreshed; 0: until it is ended.
		 */
		final long linger;

		Kind(long linger) {
			this.linger = linger;
		}

		public String tag() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	private record Key(UUID target, UUID actor, int hand, Kind kind) {
	}

	private static final class Span {
		Part part;
		float strength;
		float reported = -1;
		long until;
		long lastReport;
		/**
		 * The CPM animation this span turned on, if any.
		 */
		String animation;
		boolean seen;
	}

	static String tag(String s) {
		return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
	}

	/**
	 * CPM animation names to try for a span, most precise first.
	 */
	public static List<String> animationNames(Kind kind, Part part) {
		List<String> out = new ArrayList<>(4);
		String base = "mi_" + kind.tag();
		if (part.exact() != null) out.add(base + "_" + part.exact());
		if (part.typeTag() != null) out.add(base + "_" + part.typeTag());
		if (part.bone() != Wire.BODY_BONE || kind != Kind.HUG) out.add(base + "_" + part.regionTag());
		out.add(base);
		return out;
	}

	/**
	 * A touch that lasts a moment (petting, a hand brushing, a slap, a hug); repeated touches keep it going.
	 */
	public void touch(UUID target, UUID actor, int hand, Kind kind, PlayerRig rig, int boneId, float strength) {
		if (target == null || actor == null || target.equals(actor)) return;
		refresh(new Key(target, actor, hand, kind), Part.of(rig, boneId), strength,
				System.currentTimeMillis() + kind.linger);
	}

	/**
	 * Called every tick with the grabs in progress: new ones start, gone ones stop.
	 */
	public void syncGrabs(Iterable<Grab> grabs, InteractionManager mgr) {
		Set<Key> live = new java.util.HashSet<>();
		for (Grab g : grabs) {
			UUID actor = g.getKey().grabber();
			if (actor.equals(g.getTarget())) continue;
			PlayerRig rig = mgr.existingRig(g.getTarget());
			if (rig == null) continue;
			// not allowed (shown only on the grabber's screen) or held on another model than we see
			if (!mgr.allowed(g.getTarget(), actor, InteractionManager.kindOf(rig, g.getBoneId()))
					|| !InteractionManager.sameModel(rig, g.getModelHash())) continue;
			Key k = new Key(g.getTarget(), actor, g.getKey().hand(), Kind.GRAB);
			live.add(k);
			refresh(k, Part.of(rig, g.getBoneId()), tension(mgr, rig, g), Long.MAX_VALUE);
		}
		for (Iterator<Map.Entry<Key, Span>> it = spans.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Key, Span> e = it.next();
			if (e.getKey().kind == Kind.GRAB && !live.contains(e.getKey())) {
				stop(e.getKey(), e.getValue());
				it.remove();
			}
		}
	}

	/**
	 * How hard the part is pulled, 0..1: how far the hand is from where the held point actually is.
	 */
	private static float tension(InteractionManager mgr, PlayerRig rig, Grab g) {
		if (g.getBoneId() == Wire.BODY_BONE) {
			if (!rig.isWorldValid()) return 0;
			var held = mgr.grabbedPointWorld(g, new org.joml.Vector3f());
			if (held == null) return 0;
			var goal = rig.getBaseModelToWorld().transformPosition(g.getGoalModel(), new org.joml.Vector3f());
			return Math.clamp(held.distance(goal) / 0.5f, 0f, 1f);
		}
		// in model space: works for our own model in first person too, where it isn't placed in the world
		Bone b = rig.bone(g.getBoneId());
		if (b == null) return 0;
		var held = b.deformed().transformPosition(new org.joml.Vector3f(g.getAnchorLocal()));
		return Math.clamp(held.distance(g.getGoalModel()) / 0.5f, 0f, 1f);
	}

	private void refresh(Key k, Part part, float strength, long until) {
		if (!MiConfig.get().getReactions().isTouchEvents()) return;
		Span s = spans.get(k);
		boolean fresh = s == null;
		if (s != null && s.part.bone() != part.bone()) {
			// moved to another part: that's a new touch
			stop(k, s);
			fresh = true;
		}
		if (fresh) {
			s = new Span();
			spans.put(k, s);
		}
		s.part = part;
		s.strength = Math.clamp(strength, 0f, 1f);
		s.until = until;
		if (fresh) start(k, s);
		else update(k, s);
	}

	public void tick() {
		long now = System.currentTimeMillis();
		for (Iterator<Map.Entry<Key, Span>> it = spans.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Key, Span> e = it.next();
			if (now > e.getValue().until) {
				stop(e.getKey(), e.getValue());
				it.remove();
			}
		}
		updateLayers();
	}

	public void clear() {
		spans.forEach(this::stop);
		spans.clear();
		updateLayers();
	}

	// ------------------------------------------------------------------ outputs

	private boolean isSelf(UUID uuid) {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && mc.player.getUUID().equals(uuid);
	}

	private void start(Key k, Span s) {
		if (isSelf(k.target)) s.animation = startAnimation(k.kind, s.part);
		figura(k, s, "start");
		s.reported = s.strength;
		s.lastReport = System.currentTimeMillis();
		if (debug) debugMessage(k, s);
	}

	private void update(Key k, Span s) {
		long now = System.currentTimeMillis();
		if (Math.abs(s.strength - s.reported) < STRENGTH_STEP || now - s.lastReport < UPDATE_INTERVAL_MS) return;
		figura(k, s, "update");
		s.reported = s.strength;
		s.lastReport = now;
	}

	private void stop(Key k, Span s) {
		if (s.animation != null) {
			int left = playing.merge(s.animation, -1, Integer::sum);
			if (left <= 0) {
				playing.remove(s.animation);
				animator.play(s.animation, 0);
			}
			s.animation = null;
		}
		figura(k, s, "stop");
	}

	/**
	 * Turns on the most precise animation the model has; shared by spans that pick the same one.
	 */
	private String startAnimation(Kind kind, Part part) {
		for (String name : animationNames(kind, part)) {
			if (playing.containsKey(name) || animator.play(name, 1)) {
				playing.merge(name, 1, Integer::sum);
				return name;
			}
		}
		return null;
	}

	/**
	 * {@code mi_<kind>_value}: value layers following the strongest touch of each kind on us, 0..255.
	 */
	private void updateLayers() {
		Map<String, Integer> want = new HashMap<>();
		for (Map.Entry<Key, Span> e : spans.entrySet()) {
			if (!isSelf(e.getKey().target)) continue;
			int v = Math.max(1, Math.round(e.getValue().strength * 255));
			want.merge("mi_" + e.getKey().kind.tag() + "_value", v, Math::max);
		}
		for (Iterator<Map.Entry<String, Integer>> it = layers.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<String, Integer> e = it.next();
			if (!want.containsKey(e.getKey())) {
				animator.play(e.getKey(), 0);
				it.remove();
			}
		}
		want.forEach((name, v) -> {
			Integer was = layers.get(name);
			if (was != null && Math.abs(was - v) < 8) return;
			if (animator.play(name, v)) layers.put(name, v);
		});
	}

	private void figura(Key k, Span s, String phase) {
		if (!FiguraBridge.isAvailable()) return;
		Map<String, Object> t = new LinkedHashMap<>();
		t.put("phase", phase);
		t.put("kind", k.kind.tag());
		t.put("part", s.part.exact() != null ? s.part.exact() : "body");
		t.put("bone", s.part.bone());
		t.put("type", s.part.type() == PartType.STATIC ? "body" : s.part.typeTag());
		t.put("region", s.part.regionTag());
		t.put("strength", (double) s.strength);
		t.put("hand", k.hand);
		t.put("actor", k.actor.toString());
		t.put("actorName", nameOf(k.actor));
		t.put("self", isSelf(k.target));
		t.put("byMe", isSelf(k.actor));
		FiguraBridge.touch(k.target, t);
	}

	private static String nameOf(UUID uuid) {
		var level = Minecraft.getInstance().level;
		var p = level == null ? null : level.getPlayerByUUID(uuid);
		return p == null ? "" : p.getName().getString();
	}

	private void debugMessage(Key k, Span s) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		String who = nameOf(k.target);
		String line = nameOf(k.actor) + " → " + (who.isEmpty() ? k.target : who) + ": "
				+ String.join(" / ", animationNames(k.kind, s.part)) + (s.animation != null ? "  ▶ " + s.animation : "");
		mc.player.sendOverlayMessage(Component.literal(line));
		ModelInteractions.LOG.info("[touch] {}", line);
	}
}
