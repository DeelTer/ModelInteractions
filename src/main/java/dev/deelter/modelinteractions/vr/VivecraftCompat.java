package dev.deelter.modelinteractions.vr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.joml.Vector3f;
import org.vivecraft.api.VRAPI;
import org.vivecraft.api.client.HeldInteractModule;
import org.vivecraft.api.client.VRClientAPI;
import org.vivecraft.api.data.VRBodyPart;
import org.vivecraft.api.data.VRBodyPartData;
import org.vivecraft.api.data.VRPose;

import dev.deelter.modelinteractions.ModelInteractions;
import dev.deelter.modelinteractions.config.MiConfig;
import dev.deelter.modelinteractions.interaction.CarryClient;
import dev.deelter.modelinteractions.interaction.Grab;
import dev.deelter.modelinteractions.interaction.HandSample;
import dev.deelter.modelinteractions.interaction.HitTester;
import dev.deelter.modelinteractions.interaction.InteractionManager;
import dev.deelter.modelinteractions.interaction.PartHit;
import dev.deelter.modelinteractions.interaction.PlayerRig;
import dev.deelter.modelinteractions.model.BodyRegion;
import dev.deelter.modelinteractions.model.Bone;
import dev.deelter.modelinteractions.physics.PartType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Vivecraft integration. Only loaded when Vivecraft is present.
 * <ul>
 * <li>Interact (trigger/grip) on a part grabs it with that hand</li>
 * <li>every VR player's hands brush parts physically (local simulation, no network)</li>
 * <li>slow strokes over head/ears/back pet, both arms around someone hugs</li>
 * </ul>
 */
public final class VivecraftCompat {
	/** Hand ids 1/2 so they don't clash with the PC grab (0). */
	static int handId(InteractionHand hand) {
		return hand == InteractionHand.MAIN_HAND ? 1 : 2;
	}

	private static final Map<String, Vector3f> PREV = new HashMap<>();
	private static long lastFrame;
	private static float frameDt;
	private static long lastHaptic;

	private VivecraftCompat() {}

	/** @return per-frame callback */
	public static Runnable init() {
		try {
			VRClientAPI.instance().addClientRegistrationHandler(e -> e.registerInteractModules(new GrabModule()));
		} catch (IllegalStateException e) {
			ModelInteractions.LOG.warn("Vivecraft registration closed, VR grabbing disabled", e);
		}
		InteractionManager.get().addHandSource(VivecraftCompat::hands);
		InteractionManager.get().setTouchListener(VivecraftCompat::touched);
		CarryClient.setSelfInVr(VivecraftCompat::vrActive);
		CarryClient.setIsVrPlayer(p -> VRAPI.instance().isVRPlayer(p));
		CarryClient.setVrHands(new CarryClient.HandPose() {
			@Override
			public Vector3f pos(int hand) {
				return vrActive() && (hand == 1 || hand == 2) ? HAND_POS[hand - 1] : null;
			}

			@Override
			public Vector3f vel(int hand) {
				return hand == 1 || hand == 2 ? HAND_PEAK[hand - 1] : null;
			}
		});
		CarryClient.setOnLifted(() -> {
			if (!vrActive() || !MiConfig.get().isHaptics()) return;
			VRClientAPI.instance().triggerHapticPulse(VRBodyPart.MAIN_HAND, 0.08f, 80f, 0.9f, 0f);
			VRClientAPI.instance().triggerHapticPulse(VRBodyPart.OFF_HAND, 0.08f, 80f, 0.9f, 0f);
		});
		ModelInteractions.LOG.info("Vivecraft integration enabled");
		return VivecraftCompat::frame;
	}

	private static boolean vrActive() {
		return VRClientAPI.instance().isVRActive();
	}

	private static Vector3f handPos(VRPose pose, InteractionHand hand) {
		if (pose == null) return null;
		VRBodyPartData d = pose.getHand(hand);
		if (d == null) return null;
		Vec3 p = d.getPos();
		return new Vector3f((float) p.x, (float) p.y, (float) p.z);
	}

	private static void frame() {
		long now = System.nanoTime();
		frameDt = lastFrame == 0 ? 0 : Math.min((now - lastFrame) / 1e9f, 0.1f);
		lastFrame = now;
		if (!vrActive()) return;
		InteractionManager mgr = InteractionManager.get();
		VRPose pose = VRClientAPI.instance().getWorldRenderPose();
		for (InteractionHand hand : InteractionHand.values()) {
			Grab g = mgr.localGrab(handId(hand));
			Vector3f p = handPos(pose, hand);
			if (g == null || p == null) continue;
			mgr.updateLocalGrab(handId(hand), p);
			tension(mgr, g, p, hand);
		}
		gestures(mgr, pose);
		carry(mgr, pose);
	}

	/** Hand velocity over the last frames, blocks per second, for throws. */
	private static final Vector3f[] HAND_VEL = { new Vector3f(), new Vector3f() };
	private static final Vector3f[] HAND_PREV = new Vector3f[2];
	private static final Vector3f[] HAND_POS = new Vector3f[2];
	private static final Vector3f[] HAND_PEAK = { new Vector3f(), new Vector3f() };
	private static final long[] HAND_PEAK_AT = new long[2];

	/** Positions and smoothed speeds of both hands, for lifting and throwing. */
	private static void carry(InteractionManager mgr, VRPose pose) {
		for (InteractionHand h : InteractionHand.values()) {
			int i = h.ordinal();
			Vector3f p = handPos(pose, h);
			if (p != null && HAND_PREV[i] != null && frameDt > 0) {
				Vector3f vel = new Vector3f(p).sub(HAND_PREV[i]).div(frameDt);
				if (vel.lengthSquared() < 400) { // drop tracking jumps
					HAND_VEL[i].lerp(vel, 0.5f);
					// a throw is judged by its fastest moment: the hand is already braking when it opens
					long now = System.nanoTime();
					if (vel.lengthSquared() >= HAND_PEAK[i].lengthSquared() || now - HAND_PEAK_AT[i] > 150_000_000L) {
						HAND_PEAK[i].set(vel);
						HAND_PEAK_AT[i] = now;
					}
				}
			}
			HAND_PREV[i] = p == null ? null : new Vector3f(p);
			HAND_POS[i] = p;
		}
	}

	/** Haptic feedback grows with how far the part is pulled from where it wants to be. */
	private static void tension(InteractionManager mgr, Grab g, Vector3f hand, InteractionHand h) {
		if (!MiConfig.get().isHaptics()) return;
		Vector3f rest = mgr.grabbedPointWorld(g, new Vector3f());
		if (rest == null) return;
		float stretch = rest.distance(hand);
		long now = System.currentTimeMillis();
		if (stretch > 0.08f && now - lastHaptic > 90) {
			lastHaptic = now;
			VRClientAPI.instance().triggerHapticPulse(VRBodyPart.fromInteractionHand(h), 0.02f, 120f, Math.min(1f, stretch * 2.5f), 0f);
		}
	}

	private static long lastTouchHaptic;

	/** A soft tick in the controller when your own hand brushes a part, so touch is felt, not only seen. */
	private static void touched(HandSample h, PartHit hit) {
		Minecraft mc = Minecraft.getInstance();
		if (!MiConfig.get().isHaptics() || mc.player == null || !h.owner().equals(mc.player.getUUID())) return;
		long now = System.currentTimeMillis();
		if (now - lastTouchHaptic < 250 || h.velocity().length() < 0.6f) return; // only a real brush, not resting hands
		lastTouchHaptic = now;
		float amp = Math.min(1f, 0.15f + h.velocity().length() * 0.4f);
		VRClientAPI.instance().triggerHapticPulse(h.hand() == 1 ? VRBodyPart.MAIN_HAND : VRBodyPart.OFF_HAND, 0.01f, 160f, amp, 0f);
	}

	private static final float PET_MIN_SPEED = 0.15f, PET_MAX_SPEED = 1.6f;

	private static void gestures(InteractionManager mgr, VRPose pose) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !MiConfig.get().isVrGestures()) return;
		UUID self = mc.player.getUUID();
		Vector3f[] hands = new Vector3f[2];
		for (InteractionHand hand : InteractionHand.values()) {
			int i = hand.ordinal();
			hands[i] = handPos(pose, hand);
			if (hands[i] == null || mgr.localGrab(handId(hand)) != null) continue;
			Vector3f prev = PREV.get(self + "/" + i);
			if (prev == null || frameDt <= 0) continue;
			float speed = prev.distance(hands[i]) / frameDt;
			if (speed < PET_MIN_SPEED || speed > PET_MAX_SPEED) continue;
			for (PlayerRig rig : mgr.rigs()) {
				if (rig.getUuid().equals(self)) continue;
				PartHit hit = HitTester.sphere(rig, hands[i], 0.06f, VivecraftCompat::pettable);
				if (hit != null) mgr.pet(rig, hit);
			}
		}
		if (hands[0] == null || hands[1] == null || hands[0].distance(hands[1]) < 0.25f) return;
		for (PlayerRig rig : mgr.rigs()) {
			if (rig.getUuid().equals(self) || !rig.isWorldValid()) continue;
			Vector3f body = bodyCenter(rig);
			if (body != null && body.distance(hands[0]) < 0.45f && body.distance(hands[1]) < 0.45f) mgr.hug(rig.getUuid());
		}
	}

	private static boolean pettable(Bone b) {
		return switch (b.getType()) {
			case EAR, HAIR, HEAD -> true;
			case TAIL -> true;
			default -> b.getRegion() == BodyRegion.BODY && !b.isRoot();
		};
	}

	private static Vector3f bodyCenter(PlayerRig rig) {
		return rig.getModelToWorld().transformPosition(new Vector3f(rig.getBodyCenter()));
	}

	/** Hands of every VR player nearby, with velocity, for touch physics. */
	private static List<HandSample> hands() {
		List<HandSample> out = new ArrayList<>();
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || frameDt <= 0) return out;
		VRAPI api = VRAPI.instance();
		for (Player p : mc.level.players()) {
			if (p.distanceToSqr(mc.player) > 64) continue;
			VRPose pose;
			if (p == mc.player) {
				if (!vrActive()) continue;
				pose = VRClientAPI.instance().getWorldRenderPose();
			} else {
				if (!api.isVRPlayer(p)) continue;
				pose = api.getVRPose(p);
			}
			for (InteractionHand hand : InteractionHand.values()) {
				Vector3f pos = handPos(pose, hand);
				if (pos == null) continue;
				String k = p.getUUID() + "/" + hand.ordinal();
				Vector3f prev = PREV.put(k, new Vector3f(pos));
				if (prev == null) continue;
				Vector3f vel = new Vector3f(pos).sub(prev).div(frameDt);
				if (vel.lengthSquared() > 100) continue; // teleport / tracking loss
				out.add(new HandSample(p.getUUID(), handId(hand), pos, vel));
			}
		}
		if (PREV.size() > 256) PREV.clear();
		return out;
	}

	/** Vivecraft interact module: grab with trigger/grip while the hand touches a part. */
	static final class GrabModule implements HeldInteractModule {
		private static final Identifier ID = Identifier.fromNamespaceAndPath(ModelInteractions.MOD_ID, "grab");
		private final PartHit[] candidate = new PartHit[2];
		/** This hand's trigger is down to mark the other hand's grab, not to grab. */
		private final boolean[] marking = new boolean[2];

		private static InteractionHand other(InteractionHand h) {
			return h == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		}

		@Override
		public Identifier getId() {
			return ID;
		}

		@Override
		public int getPriority() {
			return 100;
		}

		@Override
		public void reset(LocalPlayer player, InteractionHand hand) {
			candidate[hand.ordinal()] = null;
		}

		@Override
		public boolean isActive(LocalPlayer player, InteractionHand hand, Vec3 handPosition) {
			InteractionManager mgr = InteractionManager.get();
			if (!MiConfig.get().isEnabled()) return false;
			if (mgr.localGrab(handId(hand)) != null) return true;
			Vector3f p = new Vector3f((float) handPosition.x, (float) handPosition.y, (float) handPosition.z);
			float r = MiConfig.get().getInput().getVrGrabRadius();
			UUID self = player.getUUID();
			PartHit best = null;
			for (PlayerRig rig : mgr.rigs()) {
				boolean own = rig.getUuid().equals(self);
				if (!own && dev.deelter.modelinteractions.input.PcInput.vanillaHandUseWins(Minecraft.getInstance(),
						InteractionManager.entityOf(Minecraft.getInstance().level, rig))) continue;
				// you can fix your own hair/tail, but not grab your own arm with itself
				PartHit h = HitTester.sphere(rig, p, r, b -> !own || PlayerRig.isSoft(b.getType()) || b.getType() == PartType.ACCESSORY);
				if (h != null && mgr.canGrab(h) && (best == null || h.distance() < best.distance())) best = h;
			}
			// a light tap when the hand comes within reach of a part: "you can grab this"
			if (best != null && candidate[hand.ordinal()] == null && MiConfig.get().isHaptics() && MiConfig.get().isHoverHaptics()) {
				VRClientAPI.instance().triggerHapticPulse(VRBodyPart.fromInteractionHand(hand), 0.008f, 200f, 0.25f, 0f);
			}
			candidate[hand.ordinal()] = best;
			// free hand while the other one holds a part: its trigger marks "keep this shape"
			return best != null || marking[hand.ordinal()] || mgr.localGrab(handId(other(hand))) != null;
		}

		@Override
		public boolean onPress(LocalPlayer player, InteractionHand hand) {
			PartHit h = candidate[hand.ordinal()];
			Grab held = InteractionManager.get().localGrab(handId(other(hand)));
			if (h == null && held != null) {
				// press and let go with the free hand: what the other hand holds stays when it lets go
				dev.deelter.modelinteractions.input.PcInput.toggleKeep(Minecraft.getInstance());
				marking[hand.ordinal()] = true;
				if (MiConfig.get().isHaptics()) {
					float amp = held.isKeepOnRelease() ? 0.8f : 0.3f;
					VRClientAPI.instance().triggerHapticPulse(VRBodyPart.fromInteractionHand(hand), 0.05f, 120f, amp, 0f);
					VRClientAPI.instance().triggerHapticPulse(VRBodyPart.fromInteractionHand(other(hand)), 0.05f, 120f, amp, 0f);
				}
				return true;
			}
			if (h == null) return false;
			Grab g = InteractionManager.get().startLocalGrab(handId(hand), h);
			if (g != null && MiConfig.get().isHaptics()) {
				VRClientAPI.instance().triggerHapticPulse(VRBodyPart.fromInteractionHand(hand), 0.04f);
			}
			return g != null;
		}

		@Override
		public boolean onHoldTick(LocalPlayer player, InteractionHand hand) {
			return marking[hand.ordinal()] || InteractionManager.get().localGrab(handId(hand)) != null;
		}

		@Override
		public void onRelease(LocalPlayer player, InteractionHand hand) {
			if (marking[hand.ordinal()]) {
				marking[hand.ordinal()] = false;
				return;
			}
			InteractionManager.get().releaseLocal(handId(hand), false);
		}

		@Override
		public boolean swingsArm() {
			return false;
		}
	}
}
