package dev.deelter.modelinteractions.interaction;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.deelter.modelinteractions.ModelInteractions;
import dev.deelter.modelinteractions.config.MiConfig;
import dev.deelter.modelinteractions.model.Bone;
import dev.deelter.modelinteractions.net.NetBridge;
import dev.deelter.modelinteractions.net.Protocol;
import dev.deelter.modelinteractions.physics.PartType;
import dev.deelter.modelinteractions.physics.SpringJoint;
import dev.deelter.modelinteractions.toy.ToyBridge;
import dev.deelter.modelinteractions.toy.ToyZones;
import dev.deelter.modelinteractions.wire.Wire;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

/**
 * Client-side hub: owns rigs, active grabs and permission states, runs physics once per
 * rendered frame and translates between local input and network messages.
 */
public final class InteractionManager {
	private static final InteractionManager INSTANCE = new InteractionManager();
	private static final long REMOTE_GRAB_TIMEOUT = 4_000_000_000L;
	/**
	 * A held grab that doesn't move is re-sent this often, well within {@link #REMOTE_GRAB_TIMEOUT}.
	 */
	private static final long KEEPALIVE = 1_000_000_000L;
	private static final long REPUBLISH = 30_000_000_000L;
	private static final long CAPTURE_MAX_AGE = 500_000_000L;
	private static final int MAX_CHAIN = 3;
	/**
	 * A hand slower than this (blocks/s) on a zone is resting there, not touching.
	 */
	private static final float TOY_MIN_SPEED = 0.3f;
	/**
	 * Rigs further than this from the camera are not simulated.
	 */
	private static final double SIM_RANGE = 48;
	/**
	 * "Pose me": stepping further than this from where you were posed lets the pose go.
	 */
	private static final double POSE_LEAVE_DISTANCE = 1.2;
	/**
	 * Player models are drawn at 15/16 scale.
	 */
	private static final float PLAYER_MODEL_SCALE = 0.9375f;
	/**
	 * Model volume (px^3) that reacts half as much to a push as a weightless part.
	 */
	private static final float PUSH_HALF_MASS = 64f;
	/**
	 * A hand faster than this (blocks/s) on contact slaps instead of pushing.
	 */
	private static final float SLAP_SPEED = 3f;
	/**
	 * Speed of a punch, blocks per second, along the view direction.
	 */
	private static final float PUNCH_SPEED = 3.5f;
	/**
	 * Up to this much volume (px^3) a torso / part follows the hand fully.
	 */
	private static final float BODY_EASY_MASS = 400f, PART_EASY_MASS = 250f;
	private final Cache<UUID, PlayerRig> rigs = Caffeine.newBuilder().maximumSize(1024).expireAfterAccess(Duration.ofMinutes(2)).build();
	private final Cache<UUID, PermissionState> permissions = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(2)).build();
	private final Cache<UUID, Map<Integer, Vector3f>> savedPoses = Caffeine.newBuilder().expireAfterAccess(Duration.ofMinutes(30)).build();
	private final Cache<UUID, Protocol.OwnerTypes> ownerTypes = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(2)).build();
	/**
	 * When we last asked the server for a player's states.
	 */
	private final Cache<UUID, Long> stateRequests = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(3)).build();
	private final Map<Grab.Key, Grab> grabs = new HashMap<>();
	private final List<Supplier<List<HandSample>>> handSources = new ArrayList<>();
	private final Map<String, Long> reactionCooldown = new HashMap<>();
	/**
	 * Client-only test dummies: never touch the network; value = their permissions.
	 */
	private final Map<UUID, PermissionState> localOnly = new HashMap<>();
	private PermissionState ownPermissions;
	@Getter
	@Setter
	private NetBridge net = NetBridge.NONE;
	@Setter
	private Animator animator = (n, v) -> false;
	@Setter
	private TouchListener touchListener;
	private long lastFrame, lastStateSweep, lastRepublish;
	/**
	 * Remote grabs not shown because the holder sees another model of the target.
	 */
	private long otherModelFrames;
	private long publishPoseAt = -1;
	@Getter
	private boolean permissionsPublished;
	private String publishedTypesKey;
	private long ownAnimUntil;
	private String ownAnim;
	/**
	 * Where we stood when someone last posed us in "pose me" mode.
	 */
	private Vec3 poseAnchor;

	private InteractionManager() {
	}

	public static InteractionManager get() {
		return INSTANCE;
	}

	private static boolean isPlayer(PlayerRig rig) {
		ClientLevel level = Minecraft.getInstance().level;
		return level != null && level.getPlayerByUUID(rig.getUuid()) != null;
	}

	private static Map<Integer, PartType> parseTypes(Map<String, String> raw) {
		if (raw == null || raw.isEmpty()) return Map.of();
		Map<Integer, PartType> out = new HashMap<>();
		raw.forEach((id, name) -> {
			try {
				out.put(Integer.parseInt(id), PartType.valueOf(name));
			} catch (IllegalArgumentException ignored) {
			}
		});
		return out;
	}

	/**
	 * The entity a rig belongs to, by the id the renderer gave us, checked against the uuid.
	 */
	public static Entity entityOf(ClientLevel level, PlayerRig rig) {
		if (level == null) return null;
		Entity e = rig.getEntityId() != -1 ? level.getEntity(rig.getEntityId()) : null;
		if (e != null && e.getUUID().equals(rig.getUuid())) return e;
		return level.getPlayerByUUID(rig.getUuid());
	}

	private static void slapSound(PlayerRig rig, PartHit hit) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || !MiConfig.get().isSounds()) return;
		Vector3f w = rig.getModelToWorld().transformPosition(new Vector3f(hit.pointModel()));
		level.playLocalSound(w.x, w.y, w.z, SoundEvents.PLAYER_ATTACK_WEAK,
				SoundSource.PLAYERS, 0.8f, 1.3f + level.getRandom().nextFloat() * 0.3f, false);
	}

	// ------------------------------------------------------------------ dummies

	/**
	 * 1 up to {@code easy}, then falling as 1/sqrt(mass), never below {@code min}.
	 */
	private static float compliance(float massPx3, float easy, float min) {
		return Math.clamp((float) Math.sqrt(easy / Math.max(massPx3, easy)), min, 1f);
	}

	/**
	 * 1 for a weightless part, 1/2 at {@link #PUSH_HALF_MASS}... heavier parts barely move.
	 */
	private static float massFactor(float massPx3) {
		return (float) (1 / Math.sqrt(1 + Math.max(massPx3, 0) / PUSH_HALF_MASS * 3));
	}

	/**
	 * Permission needed to grab this bone, after resolving rigid parts to what really moves.
	 */
	public static InteractionKind kindOf(PlayerRig rig, int boneId) {
		if (boneId == Wire.BODY_BONE) return InteractionKind.TURN_BODY;
		Bone b = rig.bone(boneId);
		return b == null ? InteractionKind.GRAB_OTHER : InteractionKind.grabOf(b.getType());
	}

	private static String nameOf(UUID uuid) {
		ClientLevel level = Minecraft.getInstance().level;
		Player p = level == null ? null : level.getPlayerByUUID(uuid);
		return p == null ? "" : p.getName().getString();
	}

	/**
	 * Bone a hit really moves: itself, its first movable parent, or the torso.
	 */
	public static int grabTarget(PartHit hit) {
		Bone m = hit.bone().movable();
		return m == null || m.isRoot() && m.getType() == PartType.STATIC ? Wire.BODY_BONE : m.getId();
	}

	public static boolean isSoft(PartType t) {
		return PlayerRig.isSoft(t);
	}

	// ------------------------------------------------------------------ part types

	public PlayerRig rig(UUID uuid) {
		return rigs.get(uuid, PlayerRig::new);
	}

	public PlayerRig existingRig(UUID uuid) {
		return rigs.getIfPresent(uuid);
	}

	public Iterable<PlayerRig> rigs() {
		return rigs.asMap().values();
	}

	public void addHandSource(Supplier<List<HandSample>> source) {
		handSources.add(source);
	}

	// ------------------------------------------------------------------ frame

	public void onRigRebuilt(PlayerRig rig) {
		Map<Integer, Vector3f> pose = savedPoses.getIfPresent(rig.getUuid());
		if (pose != null) applyPose(rig, pose);
		UUID self = self();
		// own model changed: tell others which types we picked for this one
		if (rig.getUuid().equals(self) && !rig.getModelKey().equals(publishedTypesKey)) publishOwnTypes();
		// a player we have no states of: ask the server once in a while
		if (!rig.getUuid().equals(self) && !localOnly.containsKey(rig.getUuid()) && permissions.getIfPresent(rig.getUuid()) == null
				&& isPlayer(rig) && stateRequests.getIfPresent(rig.getUuid()) == null) {
			stateRequests.put(rig.getUuid(), System.nanoTime());
			net.requestStates(rig.getUuid());
		}
	}

	/**
	 * Players around us whose permissions we don't know yet: ask again. The first ask can come
	 * before the server has them (they only just joined); without them their parts can't be
	 * shown being touched, and our own touches on them stay on our screen.
	 */
	private void requestMissingStates() {
		UUID self = self();
		for (PlayerRig rig : rigs.asMap().values()) {
			UUID u = rig.getUuid();
			if (u.equals(self) || localOnly.containsKey(u) || permissions.getIfPresent(u) != null || !isPlayer(rig))
				continue;
			if (stateRequests.getIfPresent(u) != null) continue;
			stateRequests.put(u, System.nanoTime());
			net.requestStates(u);
		}
	}

	/**
	 * For /mi debug net.
	 */
	public List<String> netStatus() {
		List<String> out = new ArrayList<>();
		long remote = grabs.values().stream().filter(g -> !g.isLocal()).count();
		out.add("Grabs: " + (grabs.size() - remote) + " mine, " + remote + " by others; hidden frames (other model) " + otherModelFrames);
		UUID self = self();
		for (PlayerRig rig : rigs.asMap().values()) {
			if (rig.getUuid().equals(self) || !isPlayer(rig)) continue;
			out.add(nameOf(rig.getUuid()) + ": permissions " + (permissions.getIfPresent(rig.getUuid()) != null ? "known" : "UNKNOWN")
					+ ", model " + rig.getSource() + " " + rig.getModelKey());
		}
		return out;
	}

	public void clear() {
		rigs.invalidateAll();
		permissions.invalidateAll();
		savedPoses.invalidateAll();
		ownerTypes.invalidateAll();
		stateRequests.invalidateAll();
		grabs.clear();
		reactionCooldown.clear();
		localOnly.clear();
		permissionsPublished = false;
		publishPoseAt = -1;
		publishedTypesKey = null;
		poseAnchor = null;
	}

	/**
	 * Another world: nothing we or others held here means anything any more.
	 */
	public void dropAllGrabs() {
		grabs.clear();
	}

	public void registerLocalDummy(UUID uuid) {
		localOnly.put(uuid, PermissionState.ALL);
		rigs.invalidate(uuid);
		savedPoses.invalidate(uuid);
	}

	public void unregisterLocalDummy(UUID uuid) {
		localOnly.remove(uuid);
		rigs.invalidate(uuid);
		grabs.values().removeIf(g -> g.getTarget().equals(uuid));
	}

	/**
	 * Puts a dummy in or out of "pose me" mode.
	 */
	public void setDummyFree(UUID uuid, boolean free) {
		if (localOnly.containsKey(uuid)) localOnly.put(uuid, free ? PermissionState.ALL_FREE : PermissionState.ALL);
	}

	public boolean isLocalOnly(UUID uuid) {
		return localOnly.containsKey(uuid);
	}

	/**
	 * Our own permissions changed in the config.
	 */
	public void onConfigChanged() {
		ownPermissions = null;
		publishOwnPermissions();
		rigs().forEach(PlayerRig::refreshProfiles);
	}

	private void send(UUID target, Wire.Act act) {
		if (localOnly.containsKey(target)) return;
		// bone numbers only mean something on the model we see; the torso is the same on all
		PlayerRig rig = rigs.getIfPresent(target);
		boolean byBone = act.bone() != Wire.BODY_BONE && act.op() != Wire.OP_RELEASE;
		net.sendAction(rig != null && byBone ? act.withModel(rig.getModelKey().hashCode()) : act);
	}

	/**
	 * Part types the owner picked for this model, or an empty map.
	 */
	public Map<Integer, PartType> ownerTypes(UUID owner, String modelKey) {
		if (owner.equals(self())) return parseTypes(MiConfig.get().getOwnTypes().get(modelKey));
		Protocol.OwnerTypes t = ownerTypes.getIfPresent(owner);
		return t != null && t.modelKey().equals(modelKey) ? t.types() : Map.of();
	}

	/**
	 * Sets the type of a part. On your own model the choice is published so every viewer uses
	 * it; on anyone else's it is a local fix only you see. {@code type == null} clears it.
	 */
	public void setPartType(PartHit hit, PartType type) {
		PlayerRig rig = hit.rig();
		boolean own = rig.getUuid().equals(self());
		Map<String, Map<String, String>> all = own ? MiConfig.get().getOwnTypes() : MiConfig.get().getOverrides();
		Map<String, String> m = all.computeIfAbsent(rig.getModelKey(), k -> new HashMap<>());
		String id = Integer.toString(hit.bone().getId());
		if (type == null) m.remove(id);
		else m.put(id, type.name());
		if (m.isEmpty()) all.remove(rig.getModelKey());
		MiConfig.save();
		rig.invalidate();
		if (own) publishOwnTypes();
	}

	private void publishOwnTypes() {
		UUID self = self();
		PlayerRig rig = self == null ? null : rigs.getIfPresent(self);
		if (rig == null || rig.getModelKey().isEmpty()) return;
		if (net.publishState(Wire.S_TYPES, Protocol.types(rig.getModelKey(), ownerTypes(self, rig.getModelKey())))) {
			publishedTypesKey = rig.getModelKey();
		}
	}

	public void onFrame(Minecraft mc, float partialTick) {
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0 : Math.min((now - lastFrame) / 1e9f, 0.1f);
		lastFrame = now;
		ClientLevel level = mc.level;
		if (level == null || !MiConfig.get().isEnabled()) return;

		Vec3 cam = mc.player.position(); // onFrame only runs with a player
		for (PlayerRig rig : rigs.asMap().values()) {
			// held is re-set by applyGrab every frame; out-of-view rigs must not stay "held" forever
			rig.joints().values().forEach(j -> j.setHeld(false));
			if (rig.getBodyJoint() != null) rig.getBodyJoint().setHeld(false);
			Entity e = entityOf(level, rig);
			// our own model isn't drawn in first person, but what others do to it still has to happen:
			// we publish the shape it ends up in, for everyone
			boolean seen = e == mc.player || now - rig.getLastSeenNanos() <= 1_000_000_000L;
			if (e == null || !seen || e.distanceToSqr(cam) > SIM_RANGE * SIM_RANGE) {
				rig.setWorldValid(false);
				continue;
			}
			updateWorld(rig, e, partialTick, dt, now);
		}

		Iterator<Grab> it = grabs.values().iterator();
		while (it.hasNext()) {
			Grab g = it.next();
			if (!g.isLocal() && now - g.getLastUpdateNanos() > REMOTE_GRAB_TIMEOUT) {
				it.remove();
				continue;
			}
			PlayerRig rig = rigs.getIfPresent(g.getTarget());
			if (g.isLocal() && (rig == null || entityOf(level, rig) == null)) {
				// target left or changed model: let go instead of holding a ghost
				it.remove();
				send(g.getTarget(), Protocol.release(g.getTarget(), g.getBoneId(), g.getKey().hand(), false));
				continue;
			}
			if (rig != null && rig.isWorldValid()) {
				applyGrab(rig, g, dt);
				// someone holding one of our zones keeps it going, more while they move
				Bone zb = rig.bone(g.getBoneId());
				if (zb != null && !g.isLocal()) {
					float moving = g.getSmoothModel().distance(g.getGoalModel()) * 8f;
					ToyZones.touched(rig, zb, zb.model().transformPosition(new Vector3f(g.getAnchorLocal())), g.getKey().grabber(), Math.clamp(0.35f + moving, 0f, 1f));
				}
			}
		}

		if (MiConfig.get().getPhysics().isTouch()) {
			for (Supplier<List<HandSample>> src : handSources) {
				List<HandSample> hands;
				try {
					hands = src.get();
				} catch (RuntimeException e) {
					continue;
				}
				for (HandSample h : hands) applyTouch(h, dt);
			}
		}

		MiConfig cfg = MiConfig.get();
		for (PlayerRig rig : rigs.asMap().values()) {
			if (rig.isWorldValid()) {
				rig.step(dt, cfg);
				rig.updateDeformed();
			}
		}

		sendLocalMoves(now);
		// a state the server dropped (rate limit) or lost must not stay wrong until the next join
		if (now - lastRepublish > REPUBLISH) {
			lastRepublish = now;
			if (permissionsPublished) {
				publishOwnPermissions();
				// what we last told, not what the rig says now: a rig just rebuilt knows no shape yet
				UUID me = self();
				Map<Integer, Vector3f> told = me == null ? null : savedPoses.getIfPresent(me);
				if (told != null) net.publishState(Wire.S_POSE, Protocol.pose(told));
			}
		}
		if (now - lastStateSweep > 2_000_000_000L) {
			lastStateSweep = now;
			requestMissingStates();
		}
		if (publishPoseAt > 0 && now >= publishPoseAt) {
			publishPoseAt = -1;
			publishOwnPose();
		}
	}

	private void updateWorld(PlayerRig rig, Entity e, float partial, float dt, long now) {
		Vec3 pos = e.getPosition(partial);
		float bodyYaw = e instanceof LivingEntity le ? Mth.rotLerp(partial, le.yBodyRotO, le.yBodyRot) : e.getYRot();
		Matrix4f base = new Matrix4f();
		boolean valid;
		if (now - rig.getCapturedNanos() < CAPTURE_MAX_AGE) {
			// exactly what the renderer drew last frame, moved along with the entity
			base.translation((float) pos.x, (float) pos.y, (float) pos.z).mul(rig.getCapturedRel());
			valid = true;
		} else if (e instanceof Player p) {
			// mirrors LivingEntityRenderer + AvatarRenderer transforms
			Pose pose = p.getPose();
			float s = p.getScale();
			// AvatarRenderer.getRenderOffset lowers crouching players by 2px
			double y = pos.y - (pose == Pose.CROUCHING ? 0.125 * s : 0);
			base.translation((float) pos.x, (float) y, (float) pos.z)
					.scale(s)
					.rotateY((float) Math.toRadians(180f - bodyYaw))
					.scale(-1, -1, 1)
					.scale(PLAYER_MODEL_SCALE)
					.translate(0, -1.501f, 0);
			valid = pose == Pose.STANDING || pose == Pose.CROUCHING;
		} else {
			valid = false;
		}
		// feet on the ground: tip over them; in the air: turn about the middle
		Vector3f feet = base.invert(new Matrix4f()).transformPosition(new Vector3f((float) pos.x, (float) pos.y, (float) pos.z));
		boolean grounded = e.onGround() && !(e == Minecraft.getInstance().player && CarryClient.isCarried());
		rig.updatePivot(grounded, feet, dt);
		rig.setBase(base);
		rig.setWorldValid(valid);
	}

	// ------------------------------------------------------------------ permissions

	/**
	 * Does a bone number from someone who sees model {@code hash} mean the same part on what we see?
	 */
	private static boolean sameModel(PlayerRig rig, int hash) {
		return hash == 0 || rig.getModelKey().hashCode() == hash;
	}

	private void applyGrab(PlayerRig rig, Grab g, float dt) {
		if (!g.isPrivateOnly() && !allowed(g.getTarget(), g.getKey().grabber(), kindOf(rig, g.getBoneId()))) return;
		if (!sameModel(rig, g.getModelHash())) {
			otherModelFrames++;
			return;
		}
		if (g.isLocal()) g.getSmoothModel().set(g.getGoalModel());
		else g.getSmoothModel().lerp(g.getGoalModel(), 1f - (float) Math.exp(-dt * 18f));
		Vector3f goal = g.getSmoothModel();

		if (g.getBoneId() == Wire.BODY_BONE) {
			// anchor rides with the turned body; goal is in the unturned frame
			SpringJoint j = rig.bodyJoint();
			float c = compliance(rig.getTotalMass(), BODY_EASY_MASS, 0.3f);
			j.setCompliance(c);
			Vector3f p = rig.getPivot();
			Vector3f a = new Vector3f(g.getAnchorLocal()).sub(p), t = new Vector3f(goal).sub(p), rv = new Vector3f();
			// a heavy body only gives in part of the way: you feel it resist
			SpringJoint.rotationBetween(a, t, rv).mul(c);
			SpringJoint.clamp(rv, j.getProfile().maxAngle());
			j.getTarget().set(rv).sub(j.getPinned());
			j.setHeld(true);
			return;
		}

		Bone bone = rig.bone(g.getBoneId());
		if (bone == null || !bone.getType().isGrabbable()) return;
		// grabbed point at rest, model space
		Vector3f anchorModel = bone.model().transformPosition(new Vector3f(g.getAnchorLocal()));
		Matrix4f inv = new Matrix4f();
		Vector3f pivot = new Vector3f(), a = new Vector3f(), t = new Vector3f(), rv = new Vector3f();
		float weight = 1f;
		int depth = 0;
		for (Bone b = bone; b != null && depth <= MAX_CHAIN; b = b.getParent(), depth++) {
			if (depth > 0 && (b.isRoot() || b.getType() != bone.getType())) break;
			SpringJoint j = rig.joint(b.getId());
			if (j == null) break;
			float c = compliance(b.getSubtreeMass(), PART_EASY_MASS, 0.5f);
			j.setCompliance(c);
			b.parentModel().invert(inv);
			b.localRest().getTranslation(pivot);
			inv.transformPosition(anchorModel, a).sub(pivot);
			inv.transformPosition(goal, t).sub(pivot);
			SpringJoint.rotationBetween(a, t, rv).mul(weight * c);
			SpringJoint.clamp(rv, j.getProfile().maxAngle());
			j.getTarget().set(rv).sub(j.getPinned());
			j.setHeld(true);
			weight *= 0.4f;
		}
	}

	/**
	 * A hand moving through a part pushes it. The push spreads up the chain and into the
	 * torso; each joint turns by torque over its moment of inertia, so a light ear flicks
	 * away while the same push barely tilts a whole body.
	 */
	private void applyTouch(HandSample h, float dt) {
		float strength = MiConfig.get().getPhysics().getTouchStrength() * Math.min(1f, dt * 25f);
		Vector3f vel = h.velocity();
		for (PlayerRig rig : rigs.asMap().values()) {
			if (!rig.isWorldValid()) continue;
			if (rig.getModelToWorld().getTranslation(new Vector3f()).distanceSquared(h.pos()) > 16) continue;
			if (grabs.containsKey(new Grab.Key(h.owner(), h.hand()))) continue;
			if (rig.getUuid().equals(h.owner())) {
				// your own hands don't knock you over, but you may touch your own zones
				PartHit own = HitTester.sphere(rig, h.pos(), 0.05f, b -> true);
				// resting hands are always near the hips: only a moving hand counts
				if (own != null && vel.length() > TOY_MIN_SPEED)
					ToyZones.touched(rig, own.bone(), own.pointModel(), h.owner(), Math.clamp(vel.length() / 2.5f, 0.15f, 1f));
				continue;
			}
			boolean slap = vel.length() > SLAP_SPEED;
			// other people's slaps come over the network, exactly once; their hand poses are too coarse for them
			if (slap && !h.owner().equals(self())) continue;
			PartHit hit = HitTester.sphere(rig, h.pos(), 0.05f, b -> true);
			if (hit == null) continue;
			if (touchListener != null) touchListener.touched(h, hit);
			if (vel.length() > TOY_MIN_SPEED)
				ToyZones.touched(rig, hit.bone(), hit.pointModel(), h.owner(), Math.clamp(vel.length() / 2.5f, 0.15f, 1f));
			Vector3f velModel = rig.getWorldToModel().transformDirection(vel, new Vector3f());
			if (slap) {
				slap(rig, hit, velModel);
				continue;
			}
			push(rig, hit.bone(), hit.pointModel(), velModel, h.owner(), strength, 12f, 3f, 1f);
		}
	}

	/**
	 * Spreads a push at a contact point up the chain and into the torso. Each joint turns by
	 * torque over its weight, so a light ear flicks away while the same push barely tilts a
	 * whole body. Points and velocity in the rig's (turned) model space.
	 */
	private void push(PlayerRig rig, Bone hitBone, Vector3f contactModel, Vector3f velModel, UUID actor, float strength,
	                  float maxPart, float maxBody, float bodyShare) {
		Matrix4f inv = new Matrix4f();
		int depth = 0;
		for (Bone b = hitBone.movable(); b != null && depth <= MAX_CHAIN; b = b.getParent(), depth++) {
			if (!b.getType().isGrabbable() || !mayLocally(rig.getUuid(), actor, InteractionKind.grabOf(b.getType())))
				break;
			SpringJoint j = rig.joint(b.getId());
			if (j == null || j.isHeld()) break;
			b.parentModel().invert(inv);
			Vector3f v = inv.transformDirection(velModel, new Vector3f());
			Vector3f lever = inv.transformPosition(contactModel, new Vector3f()).sub(b.localRest().getTranslation(new Vector3f()));
			Vector3f dw = lever.cross(v, new Vector3f()).div(Math.max(lever.lengthSquared(), 1e-3f))
					.mul(strength * 1.6f * massFactor(b.getSubtreeMass()));
			j.addImpulse(SpringJoint.clamp(dw, maxPart));
			if (b.isRoot()) break;
		}
		if (!mayLocally(rig.getUuid(), actor, InteractionKind.TURN_BODY)) return;
		SpringJoint body = rig.bodyJoint();
		if (body.isHeld()) return;
		// turned model space -> unturned, where the torso joint lives
		Matrix4f bt = rig.bodyTransform(new Matrix4f());
		Vector3f v = bt.transformDirection(velModel, new Vector3f());
		Vector3f lever = bt.transformPosition(contactModel, new Vector3f()).sub(rig.getPivot());
		Vector3f dw = lever.cross(v, new Vector3f()).div(Math.max(lever.lengthSquared(), 0.04f))
				.mul(strength * 1.6f * massFactor(rig.getTotalMass()) * bodyShare);
		body.addImpulse(SpringJoint.clamp(dw, maxBody));
	}

	/**
	 * A slap: the part that was hit takes it (a cheek turns the head, an ear flicks, soft
	 * parts wobble), the body barely moves. Ours is sent once; everyone plays the sound.
	 */
	private void slap(PlayerRig rig, PartHit hit, Vector3f velModel) {
		UUID self = self();
		if (self == null || !cooldown("slap:" + rig.getUuid(), 250)) return;
		float strength = MiConfig.get().getPhysics().getTouchStrength();
		Vector3f v = SpringJoint.clamp(new Vector3f(velModel), Wire.MAX_COORD);
		push(rig, hit.bone(), hit.pointModel(), v, self, strength, 18f, 0.6f, 0.15f);
		slapSound(rig, hit);
		if (shared(rig, self, grabTarget(hit))) {
			send(rig.getUuid(), new Wire.Act(Wire.OP_PUSH, rig.getUuid(), hit.bone().getId(), 0, true,
					Protocol.arr(hit.pointModel()), Protocol.arr(v)));
		}
	}

	/**
	 * PC: hitting a part with an empty hand knocks it the way you hit.
	 */
	public void punch(PartHit hit, Vector3f lookWorld) {
		UUID self = self();
		PlayerRig rig = hit.rig();
		if (self == null || rig.getUuid().equals(self) || !rig.isWorldValid()) return;
		if (!cooldown("punch:" + rig.getUuid(), 150)) return;
		Vector3f velModel = rig.getWorldToModel().transformDirection(new Vector3f(lookWorld).normalize(PUNCH_SPEED), new Vector3f());
		float strength = MiConfig.get().getPhysics().getTouchStrength();
		push(rig, hit.bone(), hit.pointModel(), velModel, self, strength, 9f, 1.5f, 1f);
		if (shared(rig, self, grabTarget(hit)))
			send(rig.getUuid(), Protocol.push(rig.getUuid(), hit.bone().getId(), hit.pointModel(), velModel));
	}

	public boolean allowed(UUID target, UUID actor, InteractionKind kind) {
		if (target.equals(actor)) return true;
		return stateOf(target).allows(nameOf(actor), kind);
	}

	/**
	 * Allowed for everyone to see, or, for our own hands, at least on our own screen: physics
	 * of touches never leaves this client anyway.
	 */
	private boolean mayLocally(UUID target, UUID actor, InteractionKind kind) {
		if (allowed(target, actor, kind)) return true;
		return MiConfig.get().isLocalWhenDenied() && actor.equals(self());
	}

	/**
	 * Permissions of anything that can be interacted with: you, players, dummies, mobs.
	 */
	private PermissionState stateOf(UUID target) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && target.equals(mc.player.getUUID())) return ownPermissions();
		PermissionState local = localOnly.get(target);
		if (local != null) return local;
		PermissionState s = permissions.getIfPresent(target);
		if (s != null) return s;
		PlayerRig rig = rigs.getIfPresent(target);
		if (rig != null && !isPlayer(rig)) return MiConfig.get().isMobs() ? PermissionState.MOB : PermissionState.NONE;
		return MiConfig.get().isInteractWithoutMod() ? PermissionState.UNKNOWN : PermissionState.NONE;
	}

	/**
	 * The owner asked to be posed: what others let go of keeps its shape.
	 */
	public boolean isFreeMode(UUID target) {
		return stateOf(target).freeMode();
	}

	// ------------------------------------------------------------------ local actions

	private PermissionState ownPermissions() {
		PermissionState p = ownPermissions;
		if (p == null) ownPermissions = p = PermissionState.fromConfig(MiConfig.get().getPermissions());
		return p;
	}

	public PermissionState permissionsOf(UUID uuid) {
		return permissions.getIfPresent(uuid);
	}

	/**
	 * The server just answered our hello: it knows nothing about us yet (or forgot, after a reload).
	 */
	public void onRelayHello() {
		stateRequests.invalidateAll();
		publishOwnPermissions();
		publishOwnPose();
	}

	public void publishOwnPermissions() {
		permissionsPublished = net.publishState(Wire.S_PERM, Protocol.perm(PermissionState.fromConfig(MiConfig.get().getPermissions())));
		if (permissionsPublished) publishOwnTypes();
	}

	private UUID self() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? null : mc.player.getUUID();
	}

	public boolean canGrab(PartHit hit) {
		UUID self = self();
		if (self == null) return false;
		PlayerRig rig = hit.rig();
		return mayLocally(rig.getUuid(), self, kindOf(rig, grabTarget(hit)));
	}

	/**
	 * Others would see this grab: the owner allows it and it isn't a local dummy.
	 */
	private boolean shared(PlayerRig rig, UUID self, int boneId) {
		return !localOnly.containsKey(rig.getUuid()) && allowed(rig.getUuid(), self, kindOf(rig, boneId));
	}

	public Grab startLocalGrab(int hand, PartHit hit) {
		UUID self = self();
		if (self == null || !canGrab(hit)) return null;
		Grab.Key key = new Grab.Key(self, hand);
		releaseLocal(hand, false);
		PlayerRig rig = hit.rig();
		int boneId = grabTarget(hit);
		Vector3f anchor, point;
		if (boneId == Wire.BODY_BONE) {
			// anchor in the turned (rest) frame, goal in the unturned one
			anchor = new Vector3f(hit.pointModel());
			point = rig.bodyTransform(new Matrix4f()).transformPosition(new Vector3f(anchor));
		} else {
			Bone b = rig.bone(boneId);
			anchor = b == hit.bone() ? new Vector3f(hit.anchorLocal())
					: b.deformed().invert(new Matrix4f()).transformPosition(new Vector3f(hit.pointModel()));
			point = new Vector3f(hit.pointModel());
		}
		Grab g = new Grab(key, rig.getUuid(), boneId, anchor, point, true);
		g.setPrivateOnly(!shared(rig, self, boneId));
		grabs.put(key, g);
		g.setLastSentNanos(System.nanoTime());
		g.getLastSent().set(point);
		if (!g.isPrivateOnly()) send(g.getTarget(), Protocol.grab(g.getTarget(), boneId, hand, anchor, point));
		return g;
	}

	public Grab localGrab(int hand) {
		UUID self = self();
		return self == null ? null : grabs.get(new Grab.Key(self, hand));
	}

	/**
	 * How readily what this grab holds gives in, 0..1 (1 = light).
	 */
	public float complianceOf(Grab g) {
		PlayerRig rig = rigs.getIfPresent(g.getTarget());
		if (rig == null) return 1f;
		SpringJoint j = rig.joint(g.getBoneId());
		return j == null ? 1f : j.getCompliance();
	}

	/**
	 * Moves a held local grab to a world-space point.
	 */
	public void updateLocalGrab(int hand, Vector3f worldPoint) {
		Grab g = localGrab(hand);
		if (g == null) return;
		PlayerRig rig = rigs.getIfPresent(g.getTarget());
		if (rig == null || !rig.isWorldValid()) return;
		Matrix4f toModel = g.getBoneId() == Wire.BODY_BONE ? rig.getBaseWorldToModel() : rig.getWorldToModel();
		toModel.transformPosition(worldPoint, g.getGoalModel());
		g.setLastUpdateNanos(System.nanoTime());
	}

	/**
	 * World position of the held bone's grabbed point (where the hand "should" be).
	 */
	public Vector3f grabbedPointWorld(Grab g, Vector3f dest) {
		PlayerRig rig = rigs.getIfPresent(g.getTarget());
		if (rig == null) return null;
		if (g.getBoneId() == Wire.BODY_BONE)
			return rig.getModelToWorld().transformPosition(dest.set(g.getAnchorLocal()));
		Bone b = rig.bone(g.getBoneId());
		if (b == null) return null;
		return rig.getModelToWorld().transformPosition(b.deformed().transformPosition(dest.set(g.getAnchorLocal())));
	}

	public void releaseLocal(int hand, boolean keep) {
		UUID self = self();
		if (self == null) return;
		Grab g = grabs.remove(new Grab.Key(self, hand));
		if (g == null) return;
		keep |= g.isKeepOnRelease();
		CarryClient.lookEnd(g.getTarget());
		if (g.isPrivateOnly()) {
			// only on our screen: keep the shape here if asked, tell nobody
			if (keep || isFreeMode(g.getTarget())) pinChain(g);
			else unpinChain(g);
			return;
		}
		boolean pin = keepsShape(g.getTarget(), self, keep);
		if (keep && !pin && Minecraft.getInstance().player != null)
			Minecraft.getInstance().player.sendOverlayMessage(Component.translatable("modelinteractions.msg.keep_denied"));
		if (pin) pinChain(g);
		else if (unpinChain(g) && g.getTarget().equals(self)) schedulePosePublish();
		send(g.getTarget(), Protocol.release(g.getTarget(), g.getBoneId(), hand, pin));
		if (pin && g.getTarget().equals(self)) posedNow();
	}

	/**
	 * Shift-release keeps a shape if the owner allows it; in "pose me" mode everything does.
	 */
	private boolean keepsShape(UUID target, UUID actor, boolean keepRequested) {
		if (isFreeMode(target)) return true;
		return keepRequested && allowed(target, actor, InteractionKind.PIN);
	}

	public boolean pet(PlayerRig rig, PartHit where) {
		UUID self = self();
		if (self == null || !mayLocally(rig.getUuid(), self, InteractionKind.PET)) return false;
		if (!cooldown("pet:" + rig.getUuid(), 400)) return true;
		onPet(rig.getUuid(), self);
		if (allowed(rig.getUuid(), self, InteractionKind.PET))
			send(rig.getUuid(), Protocol.act(Wire.OP_PET, rig.getUuid()));
		return true;
	}

	public boolean hug(UUID target) {
		UUID self = self();
		if (self == null || target.equals(self) || !mayLocally(target, self, InteractionKind.HUG)) return false;
		if (!cooldown("hug:" + target, 3000)) return false;
		onHug(target, self);
		if (allowed(target, self, InteractionKind.HUG)) send(target, Protocol.act(Wire.OP_HUG, target));
		return true;
	}

	/**
	 * Drops all deformations on yourself, makes everyone let go and leaves "pose me" mode.
	 */
	public void resetSelf() {
		UUID self = self();
		if (self == null) return;
		PlayerRig rig = rigs.getIfPresent(self);
		if (rig != null) rig.resetAll();
		grabs.values().removeIf(g -> g.getTarget().equals(self));
		savedPoses.invalidate(self);
		net.sendAction(Protocol.act(Wire.OP_RESET, self));
		net.publishState(Wire.S_POSE, Protocol.pose(Map.of()));
		ToyBridge.stop();
		MiConfig.Permissions p = MiConfig.get().getPermissions();
		if (p.isFreeMode()) {
			p.setFreeMode(false);
			MiConfig.save();
		}
	}

	/**
	 * Keeps your current shape (e.g. after adjusting your own tail in VR).
	 */
	public void pinSelf() {
		UUID self = self();
		PlayerRig rig = self == null ? null : rigs.getIfPresent(self);
		if (rig == null) return;
		rig.pinAll();
		schedulePosePublish();
	}

	/**
	 * Resets a local dummy.
	 */
	public void resetLocal(UUID target) {
		if (!localOnly.containsKey(target)) return;
		PlayerRig rig = rigs.getIfPresent(target);
		if (rig != null) rig.resetAll();
		grabs.values().removeIf(g -> g.getTarget().equals(target));
	}

	private boolean cooldown(String key, long ms) {
		long now = System.currentTimeMillis();
		Long last = reactionCooldown.get(key);
		if (last != null && now - last < ms) return false;
		reactionCooldown.put(key, now);
		if (reactionCooldown.size() > 512) reactionCooldown.values().removeIf(t -> now - t > 10_000);
		return true;
	}

	private void sendLocalMoves(long now) {
		long interval = 1_000_000_000L / MiConfig.get().getInput().getSyncHz();
		UUID self = self();
		for (Grab g : grabs.values()) {
			// started before their permissions arrived: now that they allow it, everyone gets to see it
			if (g.isLocal() && g.isPrivateOnly() && self != null) {
				PlayerRig r = rigs.getIfPresent(g.getTarget());
				if (r != null && shared(r, self, g.getBoneId())) {
					g.setPrivateOnly(false);
					send(g.getTarget(), Protocol.grab(g.getTarget(), g.getBoneId(), g.getKey().hand(), g.getAnchorLocal(), g.getGoalModel()));
					g.setLastSentNanos(now);
					g.getLastSent().set(g.getGoalModel());
				}
			}
			if (g.isLocal() && !g.isPrivateOnly()) lookIfHead(g);
			if (!g.isLocal() || g.isPrivateOnly() || now - g.getLastSentNanos() < interval) continue;
			// < 1cm: nothing to send, but say "still holding" now and then, or others let go after a while
			boolean still = g.getLastSent().distanceSquared(g.getGoalModel()) < 0.0001f;
			if (still && now - g.getLastSentNanos() < KEEPALIVE) continue;
			g.setLastSentNanos(now);
			g.getLastSent().set(g.getGoalModel());
			send(g.getTarget(), Protocol.move(g.getTarget(), g.getBoneId(), g.getKey().hand(), g.getAnchorLocal(), g.getGoalModel()));
		}
	}

	/**
	 * Holding a player's (or dummy's) head turns it towards us, if they allow it.
	 */
	private void lookIfHead(Grab g) {
		PlayerRig rig = rigs.getIfPresent(g.getTarget());
		Bone b = rig == null ? null : rig.bone(g.getBoneId());
		if (b == null || b.getType() != PartType.HEAD) return;
		UUID self = self();
		Entity e = entityOf(Minecraft.getInstance().level, rig);
		if (!(e instanceof Player) || self == null || !allowed(g.getTarget(), self, InteractionKind.LOOK)) return;
		CarryClient.lookTick(g.getTarget());
	}

	private boolean pinChain(Grab g) {
		PlayerRig rig = rigs.getIfPresent(g.getTarget());
		if (rig == null || !sameModel(rig, g.getModelHash())) return false;
		if (g.getBoneId() == Wire.BODY_BONE) {
			rig.bodyJoint().pinCurrent();
			return true;
		}
		Bone bone = rig.bone(g.getBoneId());
		if (bone == null) return false;
		int depth = 0;
		for (Bone b = bone; b != null && depth <= MAX_CHAIN; b = b.getParent(), depth++) {
			if (depth > 0 && (b.isRoot() || b.getType() != bone.getType())) break;
			SpringJoint j = rig.joint(b.getId());
			if (j != null) j.pinCurrent();
		}
		return true;
	}

	/**
	 * Let go without "keep": a shape saved on this part before is dropped too, and it glides
	 * back to how the player really stands. Returns whether anything was pinned.
	 */
	private boolean unpinChain(Grab g) {
		PlayerRig rig = rigs.getIfPresent(g.getTarget());
		if (rig == null || !sameModel(rig, g.getModelHash())) return false;
		boolean any = false;
		if (g.getBoneId() == Wire.BODY_BONE) {
			SpringJoint b = rig.getBodyJoint();
			if (b != null && b.getPinned().lengthSquared() > 1e-6f) {
				b.getPinned().zero();
				b.glide();
				any = true;
			}
			return any;
		}
		Bone bone = rig.bone(g.getBoneId());
		int depth = 0;
		for (Bone b = bone; b != null && depth <= MAX_CHAIN; b = b.getParent(), depth++) {
			if (depth > 0 && (b.isRoot() || b.getType() != bone.getType())) break;
			SpringJoint j = rig.joints().get(b.getId());
			if (j != null && j.getPinned().lengthSquared() > 1e-6f) {
				j.getPinned().zero();
				j.glide();
				any = true;
			}
		}
		return any;
	}

	private void schedulePosePublish() {
		publishPoseAt = System.nanoTime() + 600_000_000L; // let springs settle first
	}

	// ------------------------------------------------------------------ network in

	private void publishOwnPose() {
		UUID self = self();
		PlayerRig rig = self == null ? null : rigs.getIfPresent(self);
		if (rig == null) return;
		Map<Integer, Vector3f> pinned = new HashMap<>();
		rig.joints().forEach((id, j) -> {
			if (j.getPinned().lengthSquared() > 1e-6f) pinned.put(id, new Vector3f(j.getPinned()));
		});
		if (rig.getBodyJoint() != null && rig.getBodyJoint().getPinned().lengthSquared() > 1e-6f) {
			pinned.put(Wire.BODY_BONE, new Vector3f(rig.getBodyJoint().getPinned()));
		}
		savedPoses.put(self, pinned);
		net.publishState(Wire.S_POSE, Protocol.pose(pinned));
	}

	private void applyPose(PlayerRig rig, Map<Integer, Vector3f> pose) {
		rig.joints().forEach((id, j) -> {
			if (!pose.containsKey(id) && j.getPinned().lengthSquared() > 1e-6f) j.glide();
			j.getPinned().zero();
		});
		if (rig.getBodyJoint() != null) {
			if (!pose.containsKey(Wire.BODY_BONE)) rig.getBodyJoint().glide();
			rig.getBodyJoint().getPinned().zero();
		}
		pose.forEach((id, v) -> {
			SpringJoint j = rig.joint(id);
			if (j != null) j.getPinned().set(SpringJoint.clamp(new Vector3f(v), j.getProfile().maxAngle()));
		});
	}

	/**
	 * An action another client did. {@code sender} comes from the server, never from the message.
	 */
	public void handleAction(UUID sender, Wire.Act act) {
		UUID self = self();
		if (sender == null || sender.equals(self)) return;
		UUID target = act.target();
		boolean toMe = target.equals(self);
		Grab.Key key = new Grab.Key(sender, act.hand());
		switch (act.op()) {
			case Wire.OP_GRAB -> {
				// kept even if not allowed (yet): permissions may still be on their way, and
				// applyGrab checks them every frame
				PlayerRig rig = rigs.getIfPresent(target);
				Vector3f p = Protocol.vec(act.point(), new Vector3f());
				Grab fresh = new Grab(key, target, act.bone(), Protocol.vec(act.anchor(), new Vector3f()), p, false);
				fresh.setModelHash(act.model());
				grabs.put(key, fresh);
				if (toMe) playOwn(MiConfig.get().getReactions().getGrabbedAnimation());
			}
			case Wire.OP_MOVE -> {
				Grab g = grabs.get(key);
				if (g == null || !g.getTarget().equals(target) || g.getBoneId() != act.bone()) {
					// we came into range after the grab began: every move says all we need to join in
					Vector3f p = Protocol.vec(act.point(), new Vector3f());
					Grab joined = new Grab(key, target, act.bone(), Protocol.vec(act.anchor(), new Vector3f()), p, false);
					joined.setModelHash(act.model());
					grabs.put(key, joined);
					return;
				}
				Protocol.vec(act.point(), g.getGoalModel());
				// the holder's view of the model can settle after the grab (it was still loading)
				g.setModelHash(act.model());
				g.setLastUpdateNanos(System.nanoTime());
			}
			case Wire.OP_RELEASE -> {
				Grab g = grabs.remove(key);
				if (g == null) return;
				if (keepsShape(target, sender, act.keep())) {
					// not pinned here (we see another model): don't tell everyone the shape is gone
					if (pinChain(g) && toMe) posedNow();
				} else if (unpinChain(g) && toMe) {
					schedulePosePublish();
				}
			}
			case Wire.OP_PET -> {
				if (allowed(target, sender, InteractionKind.PET) && cooldown("rx-pet:" + sender, 300))
					onPet(target, sender);
			}
			case Wire.OP_HUG -> {
				if (allowed(target, sender, InteractionKind.HUG) && cooldown("rx-hug:" + sender, 1500))
					onHug(target, sender);
			}
			case Wire.OP_PUSH -> {
				PlayerRig rig = rigs.getIfPresent(target);
				Bone b = rig == null ? null : rig.bone(act.bone());
				if (b == null || !rig.isWorldValid() || !sameModel(rig, act.model())) return;
				Bone moves = b.movable();
				// same rule as the sender used: the part that really moves, or the torso
				int id = moves == null || moves.isRoot() && moves.getType() == PartType.STATIC ? Wire.BODY_BONE : moves.getId();
				if (!allowed(target, sender, kindOf(rig, id))) return;
				if (!cooldown("rx-push:" + sender, 100)) return;
				float strength = MiConfig.get().getPhysics().getTouchStrength();
				Vector3f contact = Protocol.vec(act.anchor(), new Vector3f());
				ToyZones.touched(rig, b, contact, sender, act.keep() ? 1f : 0.7f);
				if (act.keep()) {
					// a VR slap: sharp on the part, little on the body
					Vector3f v = SpringJoint.clamp(Protocol.vec(act.point(), new Vector3f()), Wire.MAX_COORD);
					push(rig, b, contact, v, sender, strength, 18f, 0.6f, 0.15f);
					slapSound(rig, new PartHit(rig, b, contact, contact, 0));
				} else {
					Vector3f v = SpringJoint.clamp(Protocol.vec(act.point(), new Vector3f()), PUNCH_SPEED * 1.5f);
					push(rig, b, contact, v, sender, strength, 9f, 1.5f, 1f);
				}
			}
			case Wire.OP_RESET -> {
				if (!sender.equals(target)) return;
				PlayerRig rig = rigs.getIfPresent(target);
				if (rig != null) rig.resetAll();
				savedPoses.invalidate(target);
				grabs.values().removeIf(g -> g.getTarget().equals(target));
			}
			default -> {
			}
		}
	}

	// ------------------------------------------------------------------ reactions

	/**
	 * A state another player published about themselves.
	 */
	public void handleState(UUID owner, int state, byte[] body) {
		if (owner == null || owner.equals(self())) return;
		try {
			switch (state) {
				case Wire.S_PERM -> {
					permissions.put(owner, Protocol.readPerm(body));
					dropDisallowedGrabs(owner);
				}
				case Wire.S_POSE -> {
					Map<Integer, Vector3f> pose = Protocol.readPose(body);
					savedPoses.put(owner, pose);
					PlayerRig rig = rigs.getIfPresent(owner);
					if (rig != null) applyPose(rig, pose);
				}
				case Wire.S_TYPES -> {
					ownerTypes.put(owner, Protocol.readTypes(body));
					PlayerRig rig = rigs.getIfPresent(owner);
					if (rig != null) rig.invalidate();
				}
				default -> {
				}
			}
		} catch (IOException | RuntimeException e) {
			ModelInteractions.LOG.debug("Bad state {} from {}", state, owner, e);
		}
	}

	private void dropDisallowedGrabs(UUID owner) {
		PlayerRig rig = rigs.getIfPresent(owner);
		if (rig == null) return;
		grabs.values().removeIf(g -> g.getTarget().equals(owner) && !allowed(owner, g.getKey().grabber(), kindOf(rig, g.getBoneId())));
	}

	private void onPet(UUID target, UUID actor) {
		if (target.equals(self())) playOwn(MiConfig.get().getReactions().getPetAnimation());
	}

	private void onHug(UUID target, UUID actor) {
		PlayerRig rig = rig(target);
		hearts(rig, 6, 0.9);
		UUID self = self();
		if (target.equals(self) || actor.equals(self)) playOwn(MiConfig.get().getReactions().getHugAnimation());
	}

	private void playOwn(String name) {
		if (name == null || name.isBlank()) return;
		if (animator.play(name, 1)) {
			ownAnim = name;
			ownAnimUntil = System.currentTimeMillis() + (long) (MiConfig.get().getReactions().getAnimationSeconds() * 1000);
		}
	}

	/**
	 * Someone (maybe us) left a shape on us: publish it, and remember where we stood.
	 */
	private void posedNow() {
		schedulePosePublish();
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && isFreeMode(mc.player.getUUID())) poseAnchor = mc.player.position();
	}

	/**
	 * "Pose me" holds while you stand. Walking off, flying, swimming, sleeping or being
	 * teleported lets the pose go: every part glides back, and everyone is told.
	 * Shapes saved on purpose (shift-release with the PIN permission) outside "pose me" stay.
	 */
	private void checkPoseLeave() {
		Minecraft mc = Minecraft.getInstance();
		if (poseAnchor == null || mc.player == null) return;
		if (!isFreeMode(mc.player.getUUID())) {
			poseAnchor = null;
			return;
		}
		Vec3 p = mc.player.position();
		double dx = p.x - poseAnchor.x, dz = p.z - poseAnchor.z;
		Pose pose = mc.player.getPose();
		boolean left = dx * dx + dz * dz > POSE_LEAVE_DISTANCE * POSE_LEAVE_DISTANCE || Math.abs(p.y - poseAnchor.y) > 3
				|| pose == Pose.FALL_FLYING || pose == Pose.SWIMMING || pose == Pose.SLEEPING || mc.player.getAbilities().flying;
		if (!left) return;
		poseAnchor = null;
		PlayerRig rig = rigs.getIfPresent(mc.player.getUUID());
		if (rig != null) applyPose(rig, Map.of());
		savedPoses.invalidate(mc.player.getUUID());
		publishPoseAt = -1;
		net.publishState(Wire.S_POSE, Protocol.pose(Map.of()));
	}

	/**
	 * Stops timed reaction animations; called every client tick.
	 */
	public void tick() {
		checkPoseLeave();
		if (ownAnim != null && System.currentTimeMillis() > ownAnimUntil) {
			animator.play(ownAnim, 0);
			ownAnim = null;
		}
	}

	private void hearts(PlayerRig rig, int count, double spread) {
		if (!MiConfig.get().isParticles()) return;
		ClientLevel level = Minecraft.getInstance().level;
		Entity p = entityOf(level, rig);
		if (p == null) return;
		var rnd = level.getRandom();
		for (int i = 0; i < count; i++) {
			level.addParticle(ParticleTypes.HEART,
					p.getX() + (rnd.nextDouble() - 0.5) * spread,
					p.getY() + p.getBbHeight() + 0.1 + rnd.nextDouble() * 0.3,
					p.getZ() + (rnd.nextDouble() - 0.5) * spread, 0, 0.05, 0);
		}
	}

	/**
	 * Plays a CPM command animation on the local player's own model; set by the CPM plugin.
	 */
	public interface Animator {
		boolean play(String name, int value);
	}

	/**
	 * Told when a hand touches a part; VR uses it for haptics.
	 */
	public interface TouchListener {
		void touched(HandSample hand, PartHit hit);
	}
}
