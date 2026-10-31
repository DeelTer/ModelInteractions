package dev.deelter.modelinteractions.debug;

import com.mojang.authlib.GameProfile;
import dev.deelter.modelinteractions.ModelInteractions;
import dev.deelter.modelinteractions.interaction.InteractionManager;
import dev.deelter.modelinteractions.wire.Wire;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Client-only fake players (optionally wearing a CPM model) and mobs, for testing
 * interactions without a second client. The server never hears of them and no interaction
 * with them is sent over the network.
 */
public final class DummyManager {
	/**
	 * CPM models shipped inside the mod jar; "steve" is the plain player model.
	 */
	public static final List<String> BUILTIN = List.of("rana", "lucifer", "steve");
	private static final int FIRST_ID = -0x4D490000;
	private static final boolean CPM = FabricLoader.getInstance().isModLoaded("cpm");
	private static final Map<UUID, Dummy> DUMMIES = new LinkedHashMap<>();
	private static int nextId = FIRST_ID;
	private DummyManager() {
	}

	/**
	 * So there is a place to see for your own *.cpmmodel files.
	 */
	public static void createUserDir() {
		try {
			Files.createDirectories(userDir());
		} catch (IOException e) {
			ModelInteractions.LOG.warn("Could not create {}", userDir(), e);
		}
	}

	public static Path userDir() {
		return FabricLoader.getInstance().getConfigDir().resolve(ModelInteractions.MOD_ID).resolve("dummies");
	}

	/**
	 * Builtin names plus *.cpmmodel files from the config folder.
	 */
	public static List<String> availableModels() {
		TreeSet<String> names = new TreeSet<>(CPM ? BUILTIN : List.of("steve"));
		Path dir = userDir();
		if (CPM && Files.isDirectory(dir)) {
			try (Stream<Path> s = Files.list(dir)) {
				s.map(p -> p.getFileName().toString())
						.filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".cpmmodel"))
						.forEach(n -> names.add(n.substring(0, n.length() - ".cpmmodel".length())));
			} catch (IOException ignored) {
			}
		}
		return new ArrayList<>(names);
	}

	/**
	 * Living entity types that can be spawned as a test mob.
	 */
	public static List<String> mobTypes() {
		List<String> out = new ArrayList<>();
		for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
			if (t.canSummon() && t != net.minecraft.world.entity.EntityTypes.PLAYER)
				out.add(BuiltInRegistries.ENTITY_TYPE.getKey(t).getPath());
		}
		return out;
	}

	private static InputStream open(String model) throws IOException {
		Path file = userDir().resolve(model + ".cpmmodel");
		if (Files.isRegularFile(file)) return Files.newInputStream(file);
		InputStream in = DummyManager.class.getResourceAsStream("/assets/modelinteractions/dummies/" + model.toLowerCase(Locale.ROOT) + ".cpmmodel");
		if (in == null) throw new IOException("Unknown model '" + model + "'");
		return in;
	}

	/**
	 * Spawns a fake player two blocks in front of the local player, facing them.
	 */
	public static LivingEntity spawn(String model, String name) throws IOException {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) throw new IOException("Not in a world");
		boolean plain = model.equalsIgnoreCase("steve");
		if (!plain && !CPM) throw new IOException("CPM models need Customizable Player Models installed");

		UUID uuid = UUID.nameUUIDFromBytes(("modelinteractions-dummy:" + name).getBytes());
		remove(uuid);
		GameProfile profile = new GameProfile(uuid, name);
		if (!plain) {
			try (InputStream in = open(model)) {
				dev.deelter.modelinteractions.cpm.CpmDummies.wear(profile, model, in);
			}
		}
		return place(new RemotePlayer(level, profile), profile);
	}

	/**
	 * Spawns a client-only mob of the given type, e.g. "fox".
	 */
	public static LivingEntity spawnMob(String type) throws IOException {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) throw new IOException("Not in a world");
		EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(type.contains(":") ? type : "minecraft:" + type))
				.orElseThrow(() -> new IOException("Unknown entity '" + type + "'"));
		Entity e = t.create(level, EntitySpawnReason.COMMAND);
		if (!(e instanceof LivingEntity le)) throw new IOException("'" + type + "' is not a living entity");
		return place(le, null);
	}

	private static LivingEntity place(LivingEntity e, GameProfile profile) {
		Minecraft mc = Minecraft.getInstance();
		e.setId(nextId--);
		Vec3 look = mc.player.getViewVector(1f).multiply(1, 0, 1).normalize();
		Vec3 pos = mc.player.position().add(look.scale(2.5));
		float yaw = mc.player.getYRot() + 180f;
		e.snapTo(pos.x, pos.y, pos.z, yaw, 0);
		face(e, yaw);
		e.setOldPosAndRot();
		InteractionManager.get().registerLocalDummy(e.getUUID());
		mc.level.addEntity(e);
		DUMMIES.put(e.getUUID(), new Dummy(e, profile, pos, yaw));
		return e;
	}

	/**
	 * Lifting and throwing a dummy, all on this client.
	 */
	public static void carry(Wire.Carry c) {
		Dummy d = DUMMIES.get(c.target());
		if (d == null) return;
		Vec3 at = new Vec3(c.x(), c.y(), c.z());
		switch (c.sub()) {
			case Wire.CARRY_START, Wire.CARRY_MOVE -> {
				d.carriedAt = at;
				d.carryHands = c.hands();
				d.flying = null;
			}
			case Wire.CARRY_THROW -> {
				d.carriedAt = null;
				d.flying = new Vec3(c.vx(), c.vy(), c.vz());
			}
			case Wire.CARRY_DROP -> {
				d.carriedAt = null;
				d.flying = Vec3.ZERO;
			}
			case Wire.CARRY_TETHER -> {
				// dragged: the spot it stands on follows the hand, beyond half a block of slack
				Vec3 to = new Vec3(at.x - d.center.x, 0, at.z - d.center.z);
				double dist = to.length();
				if (dist > 0.15) {
					d.center = d.center.add(to.normalize().scale(Math.min((dist - 0.15) * 0.8, 0.7)));
					d.motion = Motion.STILL;
				}
			}
			default -> {
			}
		}
	}

	public static void look(Wire.Look l) {
		Dummy d = DUMMIES.get(l.target());
		if (d == null) return;
		d.lookAt = l.active() ? new Vec3(l.x(), l.y(), l.z()) : null;
		d.lookNanos = System.nanoTime();
	}

	public static boolean setMotion(Motion motion) {
		DUMMIES.values().forEach(d -> d.motion = motion);
		return !DUMMIES.isEmpty();
	}

	/**
	 * "Pose me" for all dummies.
	 */
	public static boolean setPoseMode(boolean on) {
		DUMMIES.keySet().forEach(u -> InteractionManager.get().setDummyFree(u, on));
		return !DUMMIES.isEmpty();
	}

	public static int resetPoses() {
		DUMMIES.keySet().forEach(InteractionManager.get()::resetLocal);
		return DUMMIES.size();
	}

	public static int clear() {
		int n = DUMMIES.size();
		for (UUID uuid : new ArrayList<>(DUMMIES.keySet())) remove(uuid);
		return n;
	}

	private static void remove(UUID uuid) {
		Dummy d = DUMMIES.remove(uuid);
		if (d == null) return;
		ClientLevel level = Minecraft.getInstance().level;
		if (level != null && level.getEntity(d.entity.getId()) == d.entity)
			level.removeEntity(d.entity.getId(), Entity.RemovalReason.DISCARDED);
		InteractionManager.get().unregisterLocalDummy(uuid);
		if (CPM && d.profile != null) dev.deelter.modelinteractions.cpm.CpmDummies.forget(d.profile);
	}

	/**
	 * Forgets dummies of a level that was unloaded.
	 */
	public static void reset() {
		DUMMIES.keySet().forEach(InteractionManager.get()::unregisterLocalDummy);
		DUMMIES.clear();
		nextId = FIRST_ID;
	}

	/**
	 * On the ground (not jumping, held up or flying after a throw).
	 */
	public static boolean isGrounded(UUID uuid) {
		Dummy d = DUMMIES.get(uuid);
		return d == null || !d.airborne;
	}

	public static boolean isDummy(UUID uuid) {
		return DUMMIES.containsKey(uuid);
	}

	/**
	 * Runs after entities ticked, so the entity's own old-position bookkeeping interpolates our moves.
	 */
	public static void tick(Minecraft mc) {
		if (DUMMIES.isEmpty()) return;
		DUMMIES.values().removeIf(d -> d.entity.isRemoved() || d.entity.level() != mc.level);
		for (Dummy d : DUMMIES.values()) {
			LivingEntity e = d.entity;
			e.setDeltaMovement(Vec3.ZERO);
			if (d.carriedAt != null || d.flying != null) {
				carried(mc, d);
				// held or flying, it is in the air until something solid is under its feet
				d.airborne = d.flying != null || !standsOnSomething(mc, e);
				e.setOnGround(!d.airborne);
				continue;
			}
			d.airborne = d.motion == Motion.JUMP && Math.sin(d.phase) > 0.02 || d.motion == Motion.FLOAT;
			e.setOnGround(!d.airborne);
			switch (d.motion) {
				case STILL -> {
					e.setPos(d.center.x, d.center.y, d.center.z);
					face(d, d.baseYaw);
				}
				case WALK -> {
					d.phase += 0.06f;
					double r = 2;
					e.setPos(d.center.x + Math.cos(d.phase) * r, d.center.y, d.center.z + Math.sin(d.phase) * r);
					// tangent of a counter-clockwise circle: yaw = angle in degrees
					face(d, (float) Math.toDegrees(d.phase));
				}
				case SPIN -> {
					d.phase += 0.12f;
					e.setPos(d.center.x, d.center.y, d.center.z);
					// back and forth so tails swing both ways
					face(d, d.baseYaw + (float) Math.sin(d.phase) * 90f);
				}
				case JUMP -> {
					d.phase += 0.25f;
					double h = Math.max(0, Math.sin(d.phase)) * 0.6;
					e.setPos(d.center.x, d.center.y + h, d.center.z);
					face(d, d.baseYaw);
				}
				case FLOAT -> {
					d.phase += 0.05f;
					e.setPos(d.center.x, d.center.y + 1.2 + Math.sin(d.phase) * 0.05, d.center.z);
					face(d, d.baseYaw);
				}
			}
		}
	}

	private static void face(LivingEntity e, float yaw) {
		e.setYRot(yaw);
		e.setYBodyRot(yaw);
		e.setYHeadRot(yaw);
	}

	private static void face(Dummy d, float yaw) {
		face(d.entity, yaw);
		if (d.lookAt == null || System.nanoTime() - d.lookNanos > 500_000_000L) {
			d.entity.setXRot(0);
			return;
		}
		Vec3 v = d.lookAt.subtract(d.entity.getEyePosition());
		float headYaw = (float) Math.toDegrees(Math.atan2(-v.x, v.z));
		// a neck turns so far, the rest would be the body's job
		float rel = net.minecraft.util.Mth.clamp(net.minecraft.util.Mth.wrapDegrees(headYaw - yaw), -75f, 75f);
		d.entity.setYHeadRot(yaw + rel);
		d.entity.setXRot((float) -Math.toDegrees(Math.atan2(v.y, Math.sqrt(v.x * v.x + v.z * v.z))));
	}

	/**
	 * Held: hang below the hands. Thrown: a simple ballistic arc until it is back at its old height.
	 */
	private static void carried(Minecraft mc, Dummy d) {
		LivingEntity e = d.entity;
		if (d.carriedAt != null) {
			// carriedAt is where the feet go
			e.setPos(e.position().lerp(d.carriedAt, 0.5));
			return;
		}
		// thrown or let go: fall until the real blocks under it stop it
		Vec3 step = d.flying;
		d.flying = d.flying.multiply(0.98, 0.98, 0.98).subtract(0, 0.08, 0);
		if (mc.level.noCollision(e, e.getBoundingBox().move(step))) {
			e.setPos(e.position().add(step));
			return;
		}
		// blocked: keep the sideways part if that is free, and land when going down
		Vec3 flat = new Vec3(step.x, 0, step.z);
		if (mc.level.noCollision(e, e.getBoundingBox().move(flat))) e.setPos(e.position().add(flat));
		if (step.y <= 0) {
			d.flying = null;
			d.center = e.position();
			d.motion = Motion.STILL;
		} else {
			d.flying = new Vec3(d.flying.x, 0, d.flying.z);
		}
	}

	private static boolean standsOnSomething(Minecraft mc, LivingEntity e) {
		return !mc.level.noCollision(e, e.getBoundingBox().move(0, -0.06, 0));
	}

	/**
	 * FLOAT: hovers a little above the ground, so one hand can catch it and it hangs.
	 */
	public enum Motion {STILL, WALK, SPIN, JUMP, FLOAT}

	private static final class Dummy {
		final LivingEntity entity;
		final GameProfile profile;
		final float baseYaw;
		Motion motion = Motion.STILL;
		float phase;
		/**
		 * Lifted: hands' middle; thrown: velocity per tick until it lands.
		 */
		Vec3 carriedAt, flying;
		int carryHands = 2;
		boolean airborne;
		Vec3 lookAt;
		long lookNanos;
		Vec3 center;

		Dummy(LivingEntity entity, GameProfile profile, Vec3 center, float baseYaw) {
			this.carriedAt = null;
			this.center = center;
			this.entity = entity;
			this.profile = profile;
			this.center = center;
			this.baseYaw = baseYaw;
		}
	}
}
