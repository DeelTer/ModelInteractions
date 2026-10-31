package dev.deelter.modelinteractions.interaction;

import dev.deelter.modelinteractions.config.MiConfig;
import dev.deelter.modelinteractions.debug.DummyManager;
import dev.deelter.modelinteractions.input.PcInput;
import dev.deelter.modelinteractions.net.RelayNet;
import dev.deelter.modelinteractions.wire.CarryGroup;
import dev.deelter.modelinteractions.wire.Wire;
import lombok.Setter;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Lifting and throwing with both VR hands, being lifted, and having your head turned.
 * <p>
 * Who moves what: a mob is moved by the server (the plugin, or the integrated server in
 * singleplayer); a player moves themselves, from the carrier's hand positions, so their
 * own view stays smooth. Test dummies are moved right here.
 */
public final class CarryClient {
	/**
	 * One player alone lifts mobs up to SOLO size with two hands; a group lifts up to GROUP size,
	 * with more hands for bigger ones. The plugin has its own, authoritative limits.
	 */
	public static final float SOLO_WIDTH = 1.0f, SOLO_HEIGHT = 1.6f, GROUP_WIDTH = 1.6f, GROUP_HEIGHT = 2.0f;
	private static final long SEND_INTERVAL = 50_000_000L, TIMEOUT = 500_000_000L, LOOK_INTERVAL = 150_000_000L;
	/**
	 * A hand speed (blocks/s) below this when letting go is just setting down.
	 */
	private static final float THROW_MIN = 2.5f;
	private static final float MAX_STEER = 0.8f;
	/**
	 * Slack of a hand on the ground, blocks; beyond it the held one is pulled along.
	 */
	private static final double TETHER_SLACK = 0.15;
	private static final float MAX_THROW_LOCAL = 1.6f;
	/**
	 * Hand speed to throw speed (per tick); a VR arm swing is short, so it gets some help.
	 */
	private static final float THROW_GAIN = 1.8f / 20f;
	private static final Vector3f PC_POS = new Vector3f(), PC_VEL = new Vector3f();
	private static final Map<UUID, Mine> MINE = new HashMap<>();
	/**
	 * Of the things we hold: which may be lifted (the rest only dragged).
	 */
	private static final java.util.Set<UUID> LIFTABLE = new java.util.HashSet<>();

	// ------------------------------------------------------------------ carrier
	/**
	 * Entities someone holds up off the ground right now (told by the server, or our own groups).
	 */
	private static final Map<UUID, Long> HELD_UP = new HashMap<>();
	/**
	 * Just let go of: a throw's fast swing must not also hit it.
	 */
	private static final Map<UUID, Long> RELEASED = new HashMap<>();
	/**
	 * Groups we run ourselves: test dummies and, in singleplayer, mobs.
	 */
	private static final Map<UUID, CarryGroup> LOCAL = new HashMap<>();
	private static final Map<UUID, SpCarry> SP = new HashMap<>();
	private static final Map<UUID, Long> SP_THROWN = new HashMap<>();
	private static final int BREAK_FREE_TICKS = 8;
	private static final double AWAY_SPEED = 0.04;
	private static final Map<UUID, Long> LOOK_SENT = new HashMap<>();
	private static final long IDLE_NANOS = 2_000_000_000L;
	/**
	 * True while our own VR session runs; set by the Vivecraft integration.
	 */
	@Setter
	private static BooleanSupplier selfInVr = () -> false;
	/**
	 * Whether another player plays in VR; set by the Vivecraft integration.
	 */
	@Setter
	private static Predicate<Player> isVrPlayer = p -> false;
	@Setter
	private static HandPose vrHands;
	private static boolean pcValid;
	private static long explainedAt;
	/**
	 * Set by the Vivecraft integration: what to do when something we hold comes off the ground.
	 */
	@Setter
	private static Runnable onLifted = () -> {};
	private static UUID carrier;
	private static Vec3 carryGoal;
	private static long carryNanos, escapedUntil;
	private static int carryHands = 2;
	private static Vec3 tetherGoal;
	/**
	 * Ticks we have been walking away from whoever holds us.
	 */
	private static int awayTicks;
	private static long tetherNanos;
	/**
	 * Who holds us on the ground, if anyone.
	 */
	private static UUID tetherFrom;
	private static UUID lookFrom;
	/**
	 * Last time we turned our own head (mouse), and the rotation seen last frame.
	 */
	private static long lastOwnTurn;
	private static float seenYaw = Float.NaN, seenPitch;

	// singleplayer: drive the mob on the integrated server directly -----------
	private static Vec3 lookPoint;
	private static long lookNanos, lookCancelledUntil;
	private static float lastYaw, lastPitch;
	private static boolean steering;

	private CarryClient() {
	}

	/** Velocity towards the goal, so the mob still collides with blocks instead of going through. */

	/**
	 * The mouse grab's point this frame; it counts as one hand.
	 */
	public static void setPcHand(Vector3f pos, float dt) {
		if (pcValid && dt > 0) PC_VEL.lerp(new Vector3f(pos).sub(PC_POS).div(dt), 0.5f);
		PC_POS.set(pos);
		pcValid = true;
	}

	// ------------------------------------------------------------------ being carried (us)

	public static void clearPcHand() {
		pcValid = false;
		PC_VEL.zero();
	}

	private static Vector3f handPos(int hand) {
		if (hand == 0) return pcValid ? PC_POS : null;
		return vrHands == null ? null : vrHands.pos(hand);
	}

	private static Vector3f handVel(int hand) {
		if (hand == 0) return PC_VEL;
		Vector3f v = vrHands == null ? null : vrHands.vel(hand);
		return v == null ? new Vector3f() : v;
	}

	/**
	 * Held up in the air by someone: its model hangs from the grip instead of falling or jumping normally.
	 */
	public static boolean isHeldUp(UUID uuid) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && uuid.equals(mc.player.getUUID())) return carrier != null;
		Long t = HELD_UP.get(uuid);
		// a lost "let go" mustn't leave it hanging forever
		if (t != null && System.nanoTime() - t > 60_000_000_000L) {
			HELD_UP.remove(uuid);
			return false;
		}
		return t != null;
	}

	/**
	 * Both hands on something that won't lift: say why, now and then.
	 */
	private static void explain(Minecraft mc, PlayerRig rig) {
		long now = System.nanoTime();
		if (!MiConfig.get().isCarryHints() || now - explainedAt < 5_000_000_000L || mc.player == null) return;
		explainedAt = now;
		Entity e = InteractionManager.entityOf(mc.level, rig);
		String key;
		if (e instanceof Player) {
			key = !RelayNet.isActive() ? "modelinteractions.msg.carry_no_server"
					: !InteractionManager.get().allowed(rig.getUuid(), mc.player.getUUID(), InteractionKind.CARRY) ? "modelinteractions.msg.carry_not_allowed"
					  : null;
		} else if (e != null && (e.getBbWidth() > GROUP_WIDTH || e.getBbHeight() > GROUP_HEIGHT)) {
			key = "modelinteractions.msg.carry_too_big";
		} else if (e != null && !mc.hasSingleplayerServer() && !(RelayNet.isActive() && RelayNet.isCarryAllowed())) {
			key = "modelinteractions.msg.carry_no_server";
		} else {
			key = null;
		}
		if (key != null) mc.player.sendOverlayMessage(net.minecraft.network.chat.Component.translatable(key));
	}

	/**
	 * Don't let a swing attack what we hold or just threw (Vivecraft hits whatever a fast hand touches).
	 */
	public static boolean protects(UUID target) {
		if (MINE.containsKey(target)) return true;
		InteractionManager mgr = InteractionManager.get();
		for (int h = 0; h <= Wire.MAX_HAND; h++) {
			Grab g = mgr.localGrab(h);
			if (g != null && g.getTarget().equals(target)) return true;
		}
		Long t = RELEASED.get(target);
		if (t == null) return false;
		if (System.nanoTime() - t < 600_000_000L) return true;
		RELEASED.remove(target);
		return false;
	}

	/**
	 * Can this be lifted at all (by us, maybe together with others)?
	 */
	public static boolean carryable(PlayerRig rig) {
		Minecraft mc = Minecraft.getInstance();
		Entity e = InteractionManager.entityOf(mc.level, rig);
		InteractionManager mgr = InteractionManager.get();
		if (!(e instanceof LivingEntity) || mc.player == null || e == mc.player || e.isPassenger() || e.isVehicle())
			return false;
		if (!mgr.allowed(rig.getUuid(), mc.player.getUUID(), InteractionKind.CARRY)) return false;
		if (mgr.isLocalOnly(rig.getUuid())) return true;
		if (e instanceof Player) return RelayNet.isActive();
		if (e.getBbWidth() > GROUP_WIDTH || e.getBbHeight() > GROUP_HEIGHT || PcInput.vanillaHandUseWins(mc, e))
			return false;
		// a mannequin set to immovable is part of a build: the game itself won't let it move
		if (e instanceof net.minecraft.world.entity.decoration.Mannequin m && ((dev.deelter.modelinteractions.mixin.MannequinAccessor) m).mi$immovable())
			return false;
		return mc.hasSingleplayerServer() || RelayNet.isActive() && RelayNet.isCarryAllowed();
	}

	/**
	 * Can a hand on it pull it along the ground (players who allow it, mobs)?
	 */
	public static boolean draggable(PlayerRig rig) {
		Minecraft mc = Minecraft.getInstance();
		Entity e = InteractionManager.entityOf(mc.level, rig);
		InteractionManager mgr = InteractionManager.get();
		if (!(e instanceof LivingEntity) || mc.player == null || e == mc.player || e.isPassenger()) return false;
		if (!mgr.allowed(rig.getUuid(), mc.player.getUUID(), InteractionKind.DRAG)) return false;
		if (mgr.isLocalOnly(rig.getUuid())) return true;
		if (e instanceof Player) return RelayNet.isActive();
		if (PcInput.vanillaHandUseWins(mc, e)) return false;
		if (e instanceof net.minecraft.world.entity.decoration.Mannequin m && ((dev.deelter.modelinteractions.mixin.MannequinAccessor) m).mi$immovable())
			return false;
		return mc.hasSingleplayerServer() || RelayNet.isActive() && RelayNet.isCarryAllowed();
	}

	/**
	 * Every frame: tell who holds what with how many hands. Each of our hands on a liftable
	 * thing counts, the mouse as one; whoever decides (server, or us for dummies and in
	 * singleplayer) adds them up with everyone else's.
	 * <p>
	 * Each hand keeps hold of its own point: when it takes hold we note where the thing's feet
	 * are relative to the hand, and from then on ask for the feet there. With several hands the
	 * thing sits where all of them agree best; let one go and the others keep their points,
	 * so climbing along someone hand over hand doesn't jerk them about.
	 */
	public static void frameCarry(Minecraft mc) {
		InteractionManager mgr = InteractionManager.get();
		Map<UUID, List<Integer>> holding = new HashMap<>();
		for (int hand = 0; hand <= Wire.MAX_HAND; hand++) {
			Grab g = mgr.localGrab(hand);
			if (g == null || g.isPrivateOnly() || handPos(hand) == null) continue;
			holding.computeIfAbsent(g.getTarget(), k -> new ArrayList<>()).add(hand);
		}
		long now = System.nanoTime();
		// hands that let go: a fast one throws, a slow one sets down
		for (Iterator<Map.Entry<UUID, Mine>> it = MINE.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Mine> e = it.next();
			Mine m = e.getValue();
			int n = holding.getOrDefault(e.getKey(), List.of()).size();
			if (n >= m.hands) continue;
			boolean thrown = m.vel.length() >= THROW_MIN;
			// per tick, a little stronger than the hand so a throw feels like one
			Vector3f v = thrown ? new Vector3f(m.vel).mul(THROW_GAIN) : new Vector3f();
			if (v.length() > Wire.MAX_THROW) v.normalize(Wire.MAX_THROW);
			send(new Wire.Carry(thrown ? Wire.CARRY_THROW : Wire.CARRY_DROP, e.getKey(), m.mid.x, m.mid.y, m.mid.z, v.x, v.y, v.z, n));
			if (n == 0) {
				it.remove();
				RELEASED.put(e.getKey(), now);
			} else {
				m.hands = n;
			}
		}
		for (Map.Entry<UUID, List<Integer>> e : holding.entrySet()) {
			PlayerRig rig = mgr.existingRig(e.getKey());
			if (rig == null) continue;
			boolean lift = carryable(rig);
			if (!lift && !draggable(rig)) {
				if (e.getValue().size() >= 2) explain(mc, rig);
				continue;
			}
			if (lift) LIFTABLE.add(e.getKey());
			else LIFTABLE.remove(e.getKey());
			Entity ent = InteractionManager.entityOf(mc.level, rig);
			if (ent == null) continue;
			Vec3 feet = ent.position();
			Vector3f mid = new Vector3f(), vel = new Vector3f();
			for (int h : e.getValue()) {
				Grab g = mgr.localGrab(h);
				Vector3f hand = handPos(h);
				// first frame this hand counts: remember where the feet are from it
				if (g.getCarryOffset() == null)
					g.setCarryOffset(new Vector3f((float) feet.x, (float) feet.y, (float) feet.z).sub(hand));
				mid.add(hand).add(g.getCarryOffset());
				vel.add(handVel(h));
			}
			int n = e.getValue().size();
			mid.div(n);
			vel.div(n);
			Mine m = MINE.get(e.getKey());
			boolean fresh = m == null;
			if (fresh) MINE.put(e.getKey(), m = new Mine());
			m.mid.set(mid);
			m.vel.set(vel);
			if (!fresh && n <= m.hands && now - m.sentNanos < SEND_INTERVAL) continue;
			m.hands = n;
			m.sentNanos = now;
			send(new Wire.Carry(fresh ? Wire.CARRY_START : Wire.CARRY_MOVE, e.getKey(), mid.x, mid.y, mid.z, 0, 0, 0, n));
		}
	}

	private static void send(Wire.Carry c) {
		Minecraft mc = Minecraft.getInstance();
		Entity e = mc.level == null ? null : mc.level.getEntity(c.target());
		boolean local = InteractionManager.get().isLocalOnly(c.target())
				|| !(e instanceof Player) && mc.hasSingleplayerServer() && !RelayNet.isActive();
		if (!local) {
			RelayNet.INSTANCE.sendRaw(Wire.encodeCarry(c, null));
			return;
		}
		if (e == null || mc.player == null) return;
		CarryGroup g = LOCAL.computeIfAbsent(c.target(), k -> new CarryGroup(
				CarryGroup.handsNeeded(e.getBbWidth() * e.getBbWidth() * e.getBbHeight(), SOLO_WIDTH * SOLO_WIDTH * SOLO_HEIGHT)));
		long ms = System.currentTimeMillis();
		UUID me = mc.player.getUUID();
		switch (c.sub()) {
			case Wire.CARRY_START, Wire.CARRY_MOVE -> g.hold(me, c.hands(), c.x(), c.y(), c.z(), ms);
			case Wire.CARRY_THROW, Wire.CARRY_DROP ->
					g.letGo(me, c.hands(), c.sub() == Wire.CARRY_THROW, c.vx(), c.vy(), c.vz(), ms);
			default -> {
			}
		}
	}

	/**
	 * Runs the groups we decide ourselves, the way the plugin runs its own.
	 */
	private static void tickLocalGroups(Minecraft mc) {
		if (LOCAL.isEmpty()) return;
		long ms = System.currentTimeMillis();
		for (Iterator<Map.Entry<UUID, CarryGroup>> it = LOCAL.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, CarryGroup> e = it.next();
			UUID target = e.getKey();
			CarryGroup g = e.getValue();
			Entity ent = mc.level == null ? null : mc.level.getEntity(target);
			if (ent == null) {
				it.remove();
				continue;
			}
			boolean dummy = InteractionManager.get().isLocalOnly(target);
			g.setGrounded(dummy ? DummyManager.isGrounded(target) : ent.onGround());
			if (!LIFTABLE.contains(target) && !g.isLifted()) {
				// may only be dragged: pull it along while somebody holds it
				if (g.hands(ms) > 0 && g.goal() != null) {
					double[] t = g.goal();
					apply(new Wire.Carry(Wire.CARRY_TETHER, target, t[0], t[1], t[2], 0, 0, 0, g.hands(ms)));
				} else if (g.isEmpty()) {
					it.remove();
				}
				continue;
			}
			CarryGroup.Change change = g.update(ms);
			if (!g.isLifted() && change == CarryGroup.Change.NONE && g.hands(ms) > 0 && g.goal() != null) {
				// held on the ground but not (yet) lifted: dragged along
				double[] t = g.goal();
				apply(new Wire.Carry(Wire.CARRY_TETHER, target, t[0], t[1], t[2], 0, 0, 0, g.hands(ms)));
			}
			double[] goal = g.goal();
			if (change == CarryGroup.Change.FALL) {
				double vol = ent.getBbWidth() * ent.getBbWidth() * ent.getBbHeight();
				double mass = Math.max(0.3, 1 - 0.7 * Math.min(1, vol / (GROUP_WIDTH * GROUP_WIDTH * GROUP_HEIGHT)));
				float[] v = g.throwVelocity(MAX_THROW_LOCAL * mass);
				boolean thrown = v[0] != 0 || v[1] != 0 || v[2] != 0;
				Vec3 at = ent.position();
				apply(new Wire.Carry(thrown ? Wire.CARRY_THROW : Wire.CARRY_DROP, target, at.x, at.y, at.z, v[0], v[1], v[2], 0));
				HELD_UP.remove(target);
			} else if (g.isLifted() && goal != null) {
				apply(new Wire.Carry(change == CarryGroup.Change.LIFT ? Wire.CARRY_START : Wire.CARRY_MOVE, target,
						goal[0], goal[1], goal[2], 0, 0, 0, g.hands(ms)));
				if (change == CarryGroup.Change.LIFT) {
					HELD_UP.put(target, System.nanoTime());
					liftedFeedback();
				}
			}
			if (!g.isLifted() && g.isEmpty()) it.remove();
		}
	}

	private static void apply(Wire.Carry c) {
		Minecraft mc = Minecraft.getInstance();
		if (InteractionManager.get().isLocalOnly(c.target())) DummyManager.carry(c);
		else singleplayer(mc, c);
	}

	private static void liftedFeedback() {
		onLifted.run();
	}

	private static void singleplayer(Minecraft mc, Wire.Carry c) {
		var server = mc.getSingleplayerServer();
		var dim = mc.level.dimension();
		server.execute(() -> {
			ServerLevel sl = server.getLevel(dim);
			Entity e = sl == null ? null : sl.getEntity(c.target());
			if (e == null) return;
			// a mob hunting someone won't be held: without moves the hold times out and it drops
			boolean holding = c.sub() != Wire.CARRY_THROW && c.sub() != Wire.CARRY_DROP;
			if (holding && e instanceof net.minecraft.world.entity.Mob m && m.getTarget() != null) return;
			Vec3 goal = new Vec3(c.x(), c.y(), c.z());
			switch (c.sub()) {
				case Wire.CARRY_START, Wire.CARRY_MOVE -> {
					e.setNoGravity(true);
					SP.put(c.target(), new SpCarry(c.target(), goal, c.hands(), System.nanoTime()));
				}
				case Wire.CARRY_TETHER -> {
					Vec3 to = new Vec3(goal.x - e.getX(), 0, goal.z - e.getZ());
					double d = to.length();
					if (d > TETHER_SLACK) {
						Vec3 pull = to.normalize().scale(Math.min((d - TETHER_SLACK) * 0.8, 0.7));
						e.setDeltaMovement(pull.x, e.getDeltaMovement().y, pull.z);
						((dev.deelter.modelinteractions.mixin.EntityAccessor) e).mi$markVelocityDirty();
					}
				}
				case Wire.CARRY_THROW, Wire.CARRY_DROP -> {
					SP.remove(c.target());
					e.setNoGravity(false);
					if (c.sub() == Wire.CARRY_THROW) {
						e.setDeltaMovement(new Vec3(c.vx(), c.vy(), c.vz()));
						((dev.deelter.modelinteractions.mixin.EntityAccessor) e).mi$markVelocityDirty();
						SP_THROWN.put(c.target(), System.nanoTime());
					}
				}
				default -> {
				}
			}
		});
	}

	/**
	 * Registers the integrated server's tick for singleplayer carrying.
	 */
	public static void register() {
		// a world closed while holding a mob: give it its gravity back, the flag would be saved with it
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (ServerLevel sl : server.getAllLevels()) {
				for (SpCarry c : SP.values()) {
					Entity e = sl.getEntity(c.target());
					if (e != null) e.setNoGravity(false);
				}
			}
			SP.clear();
			SP_THROWN.clear();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (SP.isEmpty() && SP_THROWN.isEmpty()) return;
			long now = System.nanoTime();
			for (ServerLevel sl : server.getAllLevels()) {
				Iterator<SpCarry> it = SP.values().iterator();
				while (it.hasNext()) {
					SpCarry c = it.next();
					Entity e = sl.getEntity(c.target());
					if (e == null) continue;
					if (now - c.lastNanos() > TIMEOUT) {
						e.setNoGravity(false);
						it.remove();
						continue;
					}
					steer(e, c.goal());
					e.resetFallDistance();
				}
				SP_THROWN.entrySet().removeIf(t -> {
					Entity e = sl.getEntity(t.getKey());
					if (e != null) e.resetFallDistance(); // being thrown doesn't hurt
					return now - t.getValue() > 6_000_000_000L;
				});
			}
		});
	}

	/**
	 * Feet towards {@code goal}, by velocity, so it still collides with blocks instead of going through.
	 */
	private static void steer(Entity e, Vec3 goal) {
		Vec3 d = goal.subtract(e.position()).scale(0.5);
		if (d.length() > MAX_STEER) d = d.normalize().scale(MAX_STEER);
		e.setDeltaMovement(d);
		((dev.deelter.modelinteractions.mixin.EntityAccessor) e).mi$markVelocityDirty();
	}

	// ------------------------------------------------------------------ head turning

	/**
	 * The server says another player lifts / moves / throws / drops us.
	 */
	public static void onCarried(UUID from, Wire.Carry c) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer me = mc.player;
		MiConfig.Permissions p = MiConfig.get().getPermissions();
		if (me != null && !c.target().equals(me.getUUID())) {
			// someone else is held up or let go: only matters for how their model hangs
			if (c.sub() == Wire.CARRY_START) HELD_UP.put(c.target(), System.nanoTime());
			else if (c.sub() != Wire.CARRY_MOVE) HELD_UP.remove(c.target());
			return;
		}
		if (me != null && c.sub() == Wire.CARRY_TETHER) {
			if (p.isDragMe() && System.nanoTime() >= escapedUntil) {
				tetherGoal = new Vec3(c.x(), c.y(), c.z());
				tetherNanos = System.nanoTime();
				tetherFrom = from;
			}
			return;
		}
		// lifted needs "carry me"; caught while falling by someone holding our hand is "drag me" too
		if (me == null || !p.isCarryMe() && !p.isDragMe()) return;
		if (selfInVr.getAsBoolean() && !p.isCarryMeInVr()) return;
		long now = System.nanoTime();
		if (now < escapedUntil) return;
		switch (c.sub()) {
			case Wire.CARRY_START, Wire.CARRY_MOVE -> {
				if (carrier != null && !carrier.equals(from)) return; // one at a time
				carrier = from;
				carryGoal = new Vec3(c.x(), c.y(), c.z());
				carryHands = c.hands();
				carryNanos = now;
			}
			case Wire.CARRY_THROW -> {
				if (!from.equals(carrier)) return;
				carrier = null;
				Vec3 v = new Vec3(c.vx(), c.vy(), c.vz());
				if (v.length() > 1.5) v = v.normalize().scale(1.5); // our own, gentler limit for players
				me.setDeltaMovement(v);
			}
			case Wire.CARRY_DROP -> {
				if (from.equals(carrier)) carrier = null;
			}
			default -> {
			}
		}
	}

	/**
	 * Every client tick: follow the carrier's hands, or break free with jump / sneak.
	 */
	public static void tick(Minecraft mc) {
		tickLocalGroups(mc);
		tether(mc);
		LocalPlayer me = mc.player;
		if (me == null || carrier == null) return;
		long now = System.nanoTime();
		if (now - carryNanos > TIMEOUT || mc.options.keyJump.isDown() || mc.options.keyShift.isDown()) {
			if (now - carryNanos <= TIMEOUT) escapedUntil = now + 3_000_000_000L;
			carrier = null;
			return;
		}
		steer(me, carryGoal);
		me.resetFallDistance();
	}

	/**
	 * Someone holds us on the ground: if they are further than the slack, we are pulled
	 * after them (and walk, so our legs step that way), and walking away from them doesn't
	 * get us anywhere. Sneak breaks free for a few seconds.
	 */
	private static void tether(Minecraft mc) {
		LocalPlayer me = mc.player;
		long now = System.nanoTime();
		if (me == null || tetherGoal == null || carrier != null) return;
		if (now - tetherNanos > TIMEOUT) {
			tetherGoal = null;
			return;
		}
		if (mc.options.keyShift.isDown()) {
			escapedUntil = now + 3_000_000_000L;
			tetherGoal = null;
			return;
		}
		Vec3 to = new Vec3(tetherGoal.x - me.getX(), 0, tetherGoal.z - me.getZ());
		double d = to.length();
		if (d <= TETHER_SLACK) return;
		Vec3 dir = to.scale(1 / d);
		Vec3 v = me.getDeltaMovement();
		double along = v.x * dir.x + v.z * dir.z;
		// our own wish wins: keep walking away from the hand for a moment and we break free
		if (along < -AWAY_SPEED) {
			if (++awayTicks >= BREAK_FREE_TICKS) {
				escapedUntil = now + 3_000_000_000L;
				tetherGoal = null;
				awayTicks = 0;
				return;
			}
		} else {
			awayTicks = 0;
		}
		// until then, walking away from the hand goes nowhere
		if (along < 0) v = v.subtract(dir.x * along, 0, dir.z * along);
		// close the gap within a few ticks; the slack only soaks up ping
		Vec3 pull = dir.scale(Math.min((d - TETHER_SLACK) * 0.8, 0.7));
		double keep = Math.max(0, v.x * dir.x + v.z * dir.z);
		me.setDeltaMovement(dir.x * Math.max(keep, pull.length()) + (v.x - dir.x * keep) * 0.3, v.y,
				dir.z * Math.max(keep, pull.length()) + (v.z - dir.z * keep) * 0.3);
	}

	/**
	 * Holder and held don't push each other apart: we and what we hold, we and whoever holds
	 * us. Runs for the integrated server too, so singleplayer mobs don't shove the carrier.
	 */
	public static boolean noPush(Entity a, Entity b) {
		return holdsOrHeldBy(a, b) || holdsOrHeldBy(b, a);
	}

	private static boolean holdsOrHeldBy(Entity player, Entity other) {
		Minecraft mc = Minecraft.getInstance();
		if (!(player instanceof Player) || mc.player == null || !player.getUUID().equals(mc.player.getUUID()))
			return false;
		UUID o = other.getUUID();
		return protects(o) || o.equals(carrier) || o.equals(tetherFrom) && tetherGoal != null;
	}

	public static boolean isCarried() {
		return carrier != null;
	}

	/**
	 * While we hold someone's head: ask their head to turn to us.
	 */
	public static void lookTick(UUID target) {
		long now = System.nanoTime();
		Long last = LOOK_SENT.get(target);
		if (last != null && now - last < LOOK_INTERVAL) return;
		LOOK_SENT.put(target, now);
		sendLook(target, true);
	}

	public static void lookEnd(UUID target) {
		if (LOOK_SENT.remove(target) != null) sendLook(target, false);
	}

	private static void sendLook(UUID target, boolean active) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		Vec3 eye = mc.player.getEyePosition();
		Wire.Look l = new Wire.Look(active, target, eye.x, eye.y, eye.z);
		if (InteractionManager.get().isLocalOnly(target)) {
			DummyManager.look(l);
			return;
		}
		RelayNet.INSTANCE.sendRaw(Wire.encodeLook(l, null));
	}

	public static void onLook(UUID from, Wire.Look l) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		Vec3 point = new Vec3(l.x(), l.y(), l.z());
		if (l.target().equals(mc.player.getUUID())) {
			// our camera, only if we said so and only outside VR
			var mode = MiConfig.get().getPermissions().getLookMode();
			if (mode == dev.deelter.modelinteractions.config.LookMode.NEVER || selfInVr.getAsBoolean()) return;
			// an active player keeps their view; one that sat still for a while can be turned
			if (mode == dev.deelter.modelinteractions.config.LookMode.IDLE && System.nanoTime() - lastOwnTurn < IDLE_NANOS && lookFrom == null)
				return;
			if (!l.active()) {
				if (from.equals(lookFrom)) lookFrom = null;
				return;
			}
			lookFrom = from;
			lookPoint = point;
			lookNanos = System.nanoTime();
			return;
		}
		// someone else's head: a VR player can't have their headset turned, so only their model's head turns
		Entity e = mc.level.getEntity(l.target());
		if (e instanceof Player p && isVrPlayer.test(p)) {
			PlayerRig rig = InteractionManager.get().existingRig(l.target());
			if (rig != null) rig.lookAt(l.active() ? point : null);
		}
	}

	/**
	 * Every frame: turn our camera smoothly; moving the mouse takes it back.
	 */
	public static void frame(Minecraft mc, float dt) {
		LocalPlayer me = mc.player;
		long now = System.nanoTime();
		if (me != null) {
			// any turn we didn't make ourselves is the player's own
			boolean turned = !Float.isNaN(seenYaw) && (Math.abs(Mth.wrapDegrees(me.getYRot() - seenYaw)) > 0.3f || Math.abs(me.getXRot() - seenPitch) > 0.3f);
			if (turned && !steering) lastOwnTurn = now;
		}
		try {
			frameLook(mc, dt, now);
		} finally {
			if (me != null) {
				seenYaw = me.getYRot();
				seenPitch = me.getXRot();
			}
		}
	}

	private static void frameLook(Minecraft mc, float dt, long now) {
		LocalPlayer me = mc.player;
		if (me == null || lookFrom == null) {
			steering = false;
			return;
		}
		if (now - lookNanos > TIMEOUT) {
			lookFrom = null;
			steering = false;
			return;
		}
		// the player turned their head themselves since our last nudge
		if (steering && (Math.abs(Mth.wrapDegrees(me.getYRot() - lastYaw)) > 0.5f || Math.abs(me.getXRot() - lastPitch) > 0.5f)) {
			lookCancelledUntil = now + 1_500_000_000L;
			lastOwnTurn = now;
			lookFrom = null;
			steering = false;
			return;
		}
		if (now < lookCancelledUntil) return;
		Vec3 d = lookPoint.subtract(me.getEyePosition());
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
		float k = Math.min(1f, dt * 5f);
		float dy = Mth.wrapDegrees(yaw - me.getYRot()), dp = pitch - me.getXRot();
		// at most 120 degrees a second: a guided turn, not a snap
		float max = 120f * dt;
		me.setYRot(me.getYRot() + Mth.clamp(dy * k, -max, max));
		me.setXRot(Mth.clamp(me.getXRot() + Mth.clamp(dp * k, -max, max), -90f, 90f));
		lastYaw = me.getYRot();
		lastPitch = me.getXRot();
		steering = true;
	}

	public static void reset() {
		MINE.clear();
		LOCAL.clear();
		HELD_UP.clear();
		LIFTABLE.clear();
		tetherGoal = null;
		carrier = null;
		lookFrom = null;
		steering = false;
		LOOK_SENT.clear();
	}

	/**
	 * World position and velocity (blocks/s) of a VR hand (ids 1, 2), or null.
	 */
	public interface HandPose {
		Vector3f pos(int hand);

		Vector3f vel(int hand);
	}

	/**
	 * What we told about a target: how many hands, when, and how fast they moved last.
	 */
	private static final class Mine {
		final Vector3f mid = new Vector3f(), vel = new Vector3f();
		int hands;
		long sentNanos;
	}

	private record SpCarry(UUID target, Vec3 goal, int hands, long lastNanos) {
	}
}
