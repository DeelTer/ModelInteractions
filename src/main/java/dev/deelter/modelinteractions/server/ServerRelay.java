package dev.deelter.modelinteractions.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.deelter.modelinteractions.ModelInteractions;
import dev.deelter.modelinteractions.mixin.EntityAccessor;
import dev.deelter.modelinteractions.net.MiPayload;
import dev.deelter.modelinteractions.wire.CarryGroup;
import dev.deelter.modelinteractions.wire.Session;
import dev.deelter.modelinteractions.wire.Wire;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The server side on Fabric: a dedicated server with this mod, or the integrated server of a
 * LAN or singleplayer world. Same rules as the Paper plugin; only checks, forwards and moves
 * held mobs, physics stays on the clients. All on the server thread.
 */
public final class ServerRelay implements ModInitializer {
	private static final long SOFT_LANDING_MS = 6000, BREAK_FREE_MS = 1000;
	private static final int NEVER = 99;
	private static final double MAX_STEER = 0.8, SLACK = 0.15, MAX_HOLDER_DISTANCE_SQ = 36;
	private static final int MAX_MESSAGE = Wire.MAX_STATE_BODY + 32;

	private final Map<UUID, Session> sessions = new HashMap<>();
	private final Map<UUID, ServerLevel> sessionLevels = new HashMap<>();
	private final Map<UUID, Long> lastHello = new HashMap<>();
	private final Set<UUID> versionWarned = new HashSet<>();
	private final Map<UUID, Held> held = new HashMap<>();
	private final Map<UUID, Long> softLanding = new HashMap<>();
	private final Map<UUID, Long> restoreFlight = new HashMap<>();
	private final Map<UUID, Long> lastThrow = new HashMap<>();
	private final Map<UUID, Long> lastCombat = new HashMap<>();
	private Config config = new Config();
	private MinecraftServer server;

	/**
	 * config/modelinteractions-server.json
	 */
	public static class Config {
		public double range = 16, reach = 6, stateRange = 48;
		public boolean mobs = true, adultFeatures = false;
		public int maxWatchers = 32;
		public double actionsPerSecond = 100, actionsBurst = 150, statesPerSecond = 2, statesBurst = 6, requestsPerSecond = 8, requestsBurst = 24;
		public long petCooldownMs = 250, hugCooldownMs = 1000;
		public boolean carryMobs = true, dragMobs = true, noFallDamage = true;
		public double maxWidth = 1.0, maxHeight = 1.6, groupMaxWidth = 1.6, groupMaxHeight = 2.0, holdDistance = 2.5, maxThrowSpeed = 1.6;
		public long throwCooldownMs = 1000, combatLockMs = 5000;
		/**
		 * calm: mobs only while they hunt nobody. none: never, nor any monster. all: anything goes.
		 */
		public String hostile = "calm";
		public List<String> deny = new ArrayList<>(List.of("villager", "wandering_trader"));
	}

	private static final class Held {
		final Entity entity;
		final CarryGroup group;
		final boolean canLift, canDrag;
		boolean hadNoGravity, hadFlight;
		long angrySince;

		Held(Entity entity, int required, boolean canDrag) {
			this.entity = entity;
			this.group = new CarryGroup(required > 0 ? required : NEVER);
			this.canLift = required > 0;
			this.canDrag = canDrag;
		}
	}

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().register(MiPayload.TYPE, MiPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MiPayload.TYPE, MiPayload.CODEC);
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			loadConfig();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> releaseAll());
		ServerPlayNetworking.registerGlobalReceiver(MiPayload.TYPE, (p, ctx) -> receive(ctx.player(), p.data()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> leave(handler.player));
		ServerTickEvents.END_SERVER_TICK.register(s -> tick());
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(this::onDamage);
	}

	private void loadConfig() {
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		Path file = FabricLoader.getInstance().getConfigDir().resolve("modelinteractions-server.json");
		try {
			if (Files.exists(file)) {
				try (Reader r = Files.newBufferedReader(file)) {
					Config c = gson.fromJson(r, Config.class);
					if (c != null) config = c;
				}
			}
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file)) {
				gson.toJson(config, w);
			}
		} catch (IOException | RuntimeException e) {
			ModelInteractions.LOG.error("Failed to read {}, using defaults", file, e);
		}
		if (config.deny == null) config.deny = new ArrayList<>();
		if (config.hostile == null) config.hostile = "calm";
		config.maxThrowSpeed = Math.min(config.maxThrowSpeed, Wire.MAX_THROW);
	}

	// ------------------------------------------------------------------ messages

	private void send(ServerPlayer p, byte[] msg) {
		ServerPlayNetworking.send(p, new MiPayload(msg));
	}

	private void hello(ServerPlayer p) {
		sessions.computeIfAbsent(p.getUUID(), u -> new Session(config.actionsPerSecond, config.actionsBurst, config.statesPerSecond,
				config.statesBurst, config.requestsPerSecond, config.requestsBurst));
		sessionLevels.put(p.getUUID(), p.level());
		int flags = (config.mobs ? Wire.HELLO_MOBS : 0) | (config.carryMobs ? Wire.HELLO_CARRY : 0) | (config.adultFeatures ? Wire.HELLO_ADULT : 0);
		send(p, Wire.encodeHello((int) config.range, flags));
	}

	private void receive(ServerPlayer player, byte[] message) {
		if (message.length > MAX_MESSAGE) return;
		UUID id = player.getUUID();
		if (Wire.isOtherVersionHello(message)) {
			if (versionWarned.add(id)) {
				ModelInteractions.LOG.info("{} has a Model Interactions mod with protocol {}, this server speaks {}", player.getName().getString(), message[0], Wire.VERSION);
			}
			send(player, Wire.encodeHello((int) config.range, 0));
			return;
		}
		if (message.length == 2 && message[0] == Wire.VERSION && message[1] == Wire.K_HELLO) {
			long now = System.currentTimeMillis();
			Long last = lastHello.get(id);
			if (last == null || now - last >= 1000) {
				lastHello.put(id, now);
				hello(player);
			}
			return;
		}
		Session s = sessions.get(id);
		if (s == null) return;
		try {
			DataInputStream in = Wire.in(message);
			switch (Wire.kind(in)) {
				case Wire.K_ACT -> {
					if (!s.actions.take()) {
						s.refuse("rate");
						return;
					}
					Wire.Act act = Wire.readAct(in);
					Wire.end(in);
					onAct(player, s, act);
				}
				case Wire.K_CARRY -> {
					if (!s.actions.take()) return;
					Wire.Carry c = Wire.readCarry(in);
					Wire.end(in);
					Entity t = player.level().getEntity(c.target());
					if (canAct(player) && reachable(player, t)) onCarry(player, c, t);
				}
				case Wire.K_LOOK -> {
					if (!s.actions.take()) return;
					Wire.Look l = Wire.readLook(in);
					Wire.end(in);
					onLook(player, l);
				}
				case Wire.K_STATE -> {
					if (!s.states.take()) {
						s.refuse("state-rate");
						return;
					}
					int state = in.readUnsignedByte();
					if (state >= Wire.S_COUNT) return;
					byte[] body = Wire.canonicalState(state, Wire.readStateBody(in));
					Wire.end(in);
					if (state == Wire.S_PERM) {
						s.perm = Wire.decodePerm(body);
						body = Wire.redacted(s.perm);
					}
					s.stored[state] = body;
					relay(Wire.encodeState(state, id, body), player.level(), player.position(), config.stateRange, player);
				}
				case Wire.K_REQ -> {
					if (!s.requests.take()) return;
					UUID target = Wire.readUuid(in);
					Wire.end(in);
					ServerPlayer tp = server.getPlayerList().getPlayer(target);
					Session ts = tp == null ? null : sessions.get(target);
					if (ts == null || tp.level() != player.level() || tp.distanceToSqr(player) > config.stateRange * config.stateRange) return;
					for (int i = 0; i < Wire.S_COUNT; i++) {
						if (ts.stored[i] != null) send(player, Wire.encodeState(i, target, ts.stored[i]));
					}
				}
				default -> {
				}
			}
		} catch (IOException | RuntimeException e) {
			if (++s.strikes == 20) ModelInteractions.LOG.warn("{} keeps sending malformed interaction messages", player.getName().getString());
		}
	}

	private void onAct(ServerPlayer sender, Session s, Wire.Act act) {
		UUID target = act.target();
		// letting go always counts, however far the target got
		if (act.op() == Wire.OP_RELEASE) {
			if (!s.holds(act.hand(), target, act.bone())) return;
			s.release(act.hand());
			relayRelease(sender, act);
			return;
		}
		if (!canAct(sender)) return;
		Entity t;
		if (act.op() == Wire.OP_RESET) {
			if (!target.equals(sender.getUUID())) return;
			s.dropGrabs();
			t = sender;
		} else {
			t = sender.level().getEntity(target);
			// a hold that began in reach is shown as long as anyone near could see it
			if (act.op() == Wire.OP_MOVE ? !touchable(sender, t) || t.distanceToSqr(sender) > config.range * config.range : !reachable(sender, t)) {
				s.refuse("reach");
				return;
			}
		}
		if (t instanceof ServerPlayer tp && tp != sender) {
			Session ts = sessions.get(tp.getUUID());
			if (ts != null && !ts.allowsActor(sender.getName().getString())) {
				s.refuse("not-allowed");
				return;
			}
		}
		switch (act.op()) {
			case Wire.OP_GRAB -> s.grab(act.hand(), target, act.bone());
			case Wire.OP_MOVE -> {
				if (!s.admitMove(act.hand(), target, act.bone(), reachable(sender, t))) {
					s.refuse("move-without-grab");
					return;
				}
			}
			case Wire.OP_PET -> {
				if (!s.cooldown("pet", target, config.petCooldownMs)) return;
			}
			case Wire.OP_PUSH -> {
				if (target.equals(sender.getUUID()) || !s.cooldown("push", target, 100)) return;
			}
			case Wire.OP_HUG -> {
				if (target.equals(sender.getUUID()) || !s.cooldown("hug", target, config.hugCooldownMs)) return;
			}
			default -> {
			}
		}
		relay(Wire.encodeAct(act, sender.getUUID()), sender.level(), t.position(), config.range, sender);
	}

	private void relayRelease(ServerPlayer sender, Wire.Act act) {
		Entity t = sender.level().getEntity(act.target());
		relay(Wire.encodeAct(act, sender.getUUID()), sender.level(), t != null ? t.position() : sender.position(), config.range, sender);
	}

	private void onLook(ServerPlayer sender, Wire.Look l) {
		if (!canAct(sender)) return;
		ServerPlayer tp = server.getPlayerList().getPlayer(l.target());
		Session ts = tp == null ? null : sessions.get(tp.getUUID());
		if (ts == null || tp == sender || !reachable(sender, tp)) return;
		if (ts.perm == null || (ts.perm.flags() & Wire.FLAG_LOOK) == 0 || !ts.allowsActor(sender.getName().getString())) return;
		// the point to look at is the sender's own eyes, whatever the message says
		Vec3 eye = sender.getEyePosition();
		byte[] msg = Wire.encodeLook(new Wire.Look(l.active(), tp.getUUID(), eye.x, eye.y, eye.z), sender.getUUID());
		relay(msg, tp.level(), tp.position(), config.range, sender);
	}

	private static boolean canAct(ServerPlayer p) {
		return !p.isDeadOrDying() && !p.isSpectator();
	}

	private boolean touchable(ServerPlayer sender, Entity t) {
		if (!(t instanceof LivingEntity le) || t.isRemoved() || le.isDeadOrDying() || t.level() != sender.level()) return false;
		if (t instanceof ServerPlayer tp) {
			if (tp.isSpectator()) return false;
		} else if (!config.mobs) {
			return false;
		}
		return true;
	}

	private boolean reachable(ServerPlayer sender, Entity t) {
		return touchable(sender, t) && t.getBoundingBox().distanceToSqr(sender.getEyePosition()) <= config.reach * config.reach;
	}

	private void relay(byte[] msg, ServerLevel level, Vec3 at, double range, ServerPlayer except) {
		List<ServerPlayer> out = new ArrayList<>();
		for (ServerPlayer p : level.players()) {
			if (p != except && sessions.containsKey(p.getUUID()) && p.position().distanceToSqr(at) <= range * range) out.add(p);
		}
		if (out.size() > config.maxWatchers) {
			out.sort(Comparator.comparingDouble(p -> p.position().distanceToSqr(at)));
			out = out.subList(0, config.maxWatchers);
		}
		for (ServerPlayer p : out) send(p, msg);
	}

	// ------------------------------------------------------------------ carrying

	private void onCarry(ServerPlayer sender, Wire.Carry c, Entity t) {
		long now = System.currentTimeMillis();
		UUID who = sender.getUUID();
		Held h = held.get(t.getUUID());
		switch (c.sub()) {
			case Wire.CARRY_START, Wire.CARRY_MOVE -> {
				if (h == null) {
					int required = mayCarry(sender, t);
					boolean drag = mayDrag(sender, t);
					if (required <= 0 && !drag && !mayCatch(sender, t)) return;
					h = new Held(t, required, drag);
					held.put(t.getUUID(), h);
				} else if (!h.group.has(who) && mayCarry(sender, t) <= 0 && !mayDrag(sender, t) && !mayCatch(sender, t)) {
					return;
				}
				Vec3 goal = clampToHolder(sender, c, t);
				h.group.hold(who, Math.min(c.hands(), 2), goal.x, goal.y, goal.z, now);
			}
			case Wire.CARRY_THROW, Wire.CARRY_DROP -> {
				if (h == null || !h.group.has(who)) return;
				boolean thrown = false;
				if (c.sub() == Wire.CARRY_THROW) {
					Long last = lastThrow.get(who);
					if (last == null || now - last >= config.throwCooldownMs) {
						lastThrow.put(who, now);
						thrown = true;
					}
				}
				h.group.letGo(who, Math.min(c.hands(), 2), thrown, c.vx(), c.vy(), c.vz(), now);
			}
			default -> {
			}
		}
	}

	private Vec3 clampToHolder(ServerPlayer sender, Wire.Carry c, Entity t) {
		Vec3 eye = sender.getEyePosition(), goal = new Vec3(c.x(), c.y(), c.z());
		double max = config.holdDistance + Math.max(t.getBbHeight(), t.getBbWidth());
		return goal.distanceToSqr(eye) > max * max ? eye.add(goal.subtract(eye).normalize().scale(max)) : goal;
	}

	private int mayCarry(ServerPlayer sender, Entity t) {
		if (tooDangerous(t, System.currentTimeMillis())) return 0;
		if (!(t instanceof LivingEntity) || t == sender || t.isPassenger() || t.isVehicle()) return 0;
		if (t instanceof ServerPlayer) return playerAllows(sender, t, Wire.FLAG_CARRY) ? 2 : 0;
		if (!config.carryMobs || denied(t) || othersPet(sender, t)) return 0;
		if (t.getBbWidth() > config.groupMaxWidth || t.getBbHeight() > config.groupMaxHeight) return 0;
		double w = config.maxWidth;
		return CarryGroup.handsNeeded(t.getBbWidth() * t.getBbWidth() * t.getBbHeight(), w * w * config.maxHeight);
	}

	/**
	 * Whoever holds a part of a player who falls keeps them from falling: only in the air.
	 */
	private boolean mayCatch(ServerPlayer sender, Entity t) {
		if (!(t instanceof ServerPlayer) || t == sender || t.isPassenger() || tooDangerous(t, System.currentTimeMillis())) return false;
		return playerAllows(sender, t, Wire.FLAG_CATCH);
	}

	private boolean mayDrag(ServerPlayer sender, Entity t) {
		if (tooDangerous(t, System.currentTimeMillis())) return false;
		if (!(t instanceof LivingEntity) || t == sender || t.isPassenger()) return false;
		if (t instanceof ServerPlayer) return playerAllows(sender, t, Wire.FLAG_DRAG);
		if (!config.dragMobs || !config.carryMobs || denied(t) || othersPet(sender, t)) return false;
		return t.getBbWidth() <= 3 && t.getBbHeight() <= 4;
	}

	private boolean playerAllows(ServerPlayer sender, Entity t, int flag) {
		Session ts = sessions.get(t.getUUID());
		return ts != null && ts.perm != null && (ts.perm.flags() & flag) != 0 && ts.allowsActor(sender.getName().getString());
	}

	private boolean denied(Entity t) {
		return config.deny.contains(BuiltInRegistries.ENTITY_TYPE.getKey(t.getType()).getPath());
	}

	private static boolean othersPet(ServerPlayer sender, Entity t) {
		return t instanceof OwnableEntity o && o.getOwnerReference() != null && !sender.getUUID().equals(o.getOwnerReference().getUUID());
	}

	/**
	 * Holding must not be a way out of a fight.
	 */
	private boolean tooDangerous(Entity t, long now) {
		if (t instanceof ServerPlayer) {
			Long last = lastCombat.get(t.getUUID());
			return last != null && now - last < config.combatLockMs;
		}
		boolean hunting = t instanceof Mob m && m.getTarget() != null;
		return switch (config.hostile) {
			case "all" -> false;
			case "none" -> t instanceof Enemy || hunting;
			default -> hunting;
		};
	}

	private static boolean grounded(Entity e) {
		return !e.level().noCollision(e, e.getBoundingBox().move(0, -0.08, 0)) || !(e instanceof ServerPlayer) && e.onGround();
	}

	private void tick() {
		if (server == null) return;
		long now = System.currentTimeMillis();
		// another world: nobody keeps seeing this player's hands, and they forget all grabs
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			ServerLevel known = sessionLevels.get(p.getUUID());
			if (known != null && known != p.level()) {
				letGoOfEverything(p, known);
				sessionLevels.put(p.getUUID(), p.level());
				send(p, Wire.encodeReset());
				drop(p.getUUID());
			}
		}
		Iterator<Held> it = held.values().iterator();
		while (it.hasNext()) {
			Held h = it.next();
			if (h.entity.isRemoved()) {
				it.remove();
				continue;
			}
			for (UUID who : h.group.holders()) {
				ServerPlayer p = server.getPlayerList().getPlayer(who);
				if (p == null || p.level() != h.entity.level() || p.distanceToSqr(h.entity) > MAX_HOLDER_DISTANCE_SQ) h.group.leave(who);
			}
			if (tooDangerous(h.entity, now)) {
				if (h.angrySince == 0) h.angrySince = now;
				if (h.entity instanceof ServerPlayer || now - h.angrySince >= BREAK_FREE_MS) {
					for (UUID who : h.group.holders()) h.group.leave(who);
				}
			} else {
				h.angrySince = 0;
			}
			h.group.setGrounded(grounded(h.entity) || !h.canLift && !(h.entity instanceof ServerPlayer));
			switch (h.group.update(now)) {
				case LIFT -> lift(h);
				case FALL -> fall(h, now);
				default -> {
				}
			}
			if (h.group.isLifted()) steer(h);
			else if (!h.group.isEmpty() && h.canDrag) tether(h);
			else if (h.group.isEmpty()) it.remove();
		}
		restoreFlight.entrySet().removeIf(e -> {
			if (now < e.getValue()) return false;
			Held again = held.get(e.getKey());
			if (again != null && again.group.isLifted()) return true;
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p != null) takeFlight(p);
			return true;
		});
		softLanding.values().removeIf(t -> now > t);
		lastThrow.values().removeIf(t -> now - t > 60_000);
		lastCombat.values().removeIf(t -> now - t > 60_000);
	}

	private static void takeFlight(ServerPlayer p) {
		if (p.isCreative() || p.isSpectator()) return;
		p.getAbilities().flying = false;
		p.getAbilities().mayfly = false;
		p.onUpdateAbilities();
	}

	private void announce(Held h, int sub) {
		Vec3 at = h.entity.position();
		UUID carrier = h.group.anyone();
		Wire.Carry c = new Wire.Carry(sub, h.entity.getUUID(), at.x, at.y, at.z, 0, 0, 0, 0);
		relay(Wire.encodeCarry(c, carrier != null ? carrier : h.entity.getUUID()), (ServerLevel) h.entity.level(), at, config.range,
				h.entity instanceof ServerPlayer p ? p : null);
	}

	private void lift(Held h) {
		announce(h, Wire.CARRY_START);
		if (h.entity instanceof ServerPlayer p) {
			// still flying on the flight we gave last time: that wasn't theirs to begin with
			h.hadFlight = restoreFlight.remove(p.getUUID()) == null && p.getAbilities().mayfly;
			p.getAbilities().mayfly = true;
			p.onUpdateAbilities();
			tell(p, Wire.CARRY_START, h, Vec3.ZERO);
		} else {
			h.hadNoGravity = h.entity.isNoGravity();
			h.entity.setNoGravity(true);
		}
	}

	private static void velocity(Entity e, Vec3 v) {
		e.setDeltaMovement(v);
		((EntityAccessor) e).mi$markVelocityDirty();
	}

	private void steer(Held h) {
		double[] g = h.group.goal();
		if (g == null) return;
		if (h.entity instanceof ServerPlayer p) {
			tell(p, Wire.CARRY_MOVE, h, Vec3.ZERO);
			return;
		}
		Vec3 d = new Vec3(g[0], g[1], g[2]).subtract(h.entity.position()).scale(0.5);
		velocity(h.entity, d.length() > MAX_STEER ? d.normalize().scale(MAX_STEER) : d);
		h.entity.resetFallDistance();
	}

	private void tether(Held h) {
		double[] g = h.group.goal();
		if (g == null) return;
		if (h.entity instanceof ServerPlayer p) {
			tell(p, Wire.CARRY_TETHER, h, Vec3.ZERO);
			return;
		}
		Vec3 to = new Vec3(g[0] - h.entity.getX(), 0, g[2] - h.entity.getZ());
		double d = to.length();
		if (d <= SLACK) return;
		Vec3 pull = to.normalize().scale(Math.min((d - SLACK) * 0.8, 0.7));
		velocity(h.entity, new Vec3(pull.x, h.entity.getDeltaMovement().y, pull.z));
	}

	private void fall(Held h, long now) {
		announce(h, Wire.CARRY_DROP);
		Entity e = h.entity;
		double vol = e.getBbWidth() * e.getBbWidth() * e.getBbHeight();
		double maxVol = config.groupMaxWidth * config.groupMaxWidth * config.groupMaxHeight;
		float[] v = h.group.throwVelocity(config.maxThrowSpeed * Math.max(0.3, 1 - 0.7 * Math.min(1, vol / maxVol)));
		Vec3 vel = new Vec3(v[0], v[1], v[2]);
		if (config.noFallDamage) softLanding.put(e.getUUID(), now + SOFT_LANDING_MS);
		if (e instanceof ServerPlayer p) {
			tell(p, vel.lengthSqr() > 0 ? Wire.CARRY_THROW : Wire.CARRY_DROP, h, vel);
			if (!h.hadFlight) restoreFlight.put(p.getUUID(), now + SOFT_LANDING_MS);
		} else {
			e.setNoGravity(h.hadNoGravity);
			velocity(e, vel);
		}
	}

	private void tell(ServerPlayer p, int sub, Held h, Vec3 v) {
		double[] g = h.group.goal();
		if (g == null) g = new double[]{p.getX(), p.getY(), p.getZ()};
		UUID carrier = h.group.anyone();
		Wire.Carry out = new Wire.Carry(sub, p.getUUID(), g[0], g[1], g[2], (float) v.x, (float) v.y, (float) v.z, h.group.hands(System.currentTimeMillis()));
		send(p, Wire.encodeCarry(out, carrier != null ? carrier : p.getUUID()));
	}

	private boolean onDamage(LivingEntity victim, net.minecraft.world.damagesource.DamageSource source, float amount) {
		long now = System.currentTimeMillis();
		if (source.is(DamageTypeTags.IS_FALL)) {
			Long until = softLanding.get(victim.getUUID());
			return until == null || now >= until;
		}
		Entity attacker = source.getEntity();
		if (attacker != null) {
			if (victim instanceof ServerPlayer) lastCombat.put(victim.getUUID(), now);
			if (attacker instanceof ServerPlayer) lastCombat.put(attacker.getUUID(), now);
		}
		return true;
	}

	// ------------------------------------------------------------------ leaving

	private void letGoOfEverything(ServerPlayer p, ServerLevel level) {
		Session s = sessions.get(p.getUUID());
		if (s == null) return;
		for (int hand = 0; hand <= Wire.MAX_HAND; hand++) {
			Wire.Act release = s.releaseOf(hand);
			if (release == null) continue;
			Entity t = level.getEntity(release.target());
			relay(Wire.encodeAct(release, p.getUUID()), level, t != null ? t.position() : p.position(), config.range, p);
		}
		s.dropGrabs();
	}

	private void leave(ServerPlayer p) {
		letGoOfEverything(p, p.level());
		UUID id = p.getUUID();
		sessions.remove(id);
		sessionLevels.remove(id);
		lastHello.remove(id);
		versionWarned.remove(id);
		drop(id);
	}

	private void drop(UUID player) {
		for (Held h : held.values()) h.group.leave(player);
		Held self = held.get(player);
		// flight is saved with the player: take ours back before they go, or they'd keep it
		ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(player);
		boolean ours = restoreFlight.remove(player) != null || self != null && self.group.isLifted() && !self.hadFlight;
		if (ours && p != null) takeFlight(p);
		if (self != null) held.remove(player);
	}

	private void releaseAll() {
		for (Held h : held.values()) {
			if (!h.group.isLifted()) continue;
			if (h.entity instanceof ServerPlayer p) {
				if (!h.hadFlight) takeFlight(p);
			} else if (!h.entity.isRemoved()) {
				h.entity.setNoGravity(h.hadNoGravity);
			}
		}
		held.clear();
		if (server != null) {
			for (UUID u : restoreFlight.keySet()) {
				ServerPlayer p = server.getPlayerList().getPlayer(u);
				if (p != null) takeFlight(p);
			}
		}
		restoreFlight.clear();
		sessions.clear();
		sessionLevels.clear();
		server = null;
	}
}
