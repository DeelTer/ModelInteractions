package dev.deelter.modelinteractions.debug;

import com.mojang.authlib.GameProfile;
import dev.deelter.modelinteractions.ModelInteractions;
import dev.deelter.modelinteractions.input.PcInput;
import dev.deelter.modelinteractions.interaction.InteractionManager;
import dev.deelter.modelinteractions.interaction.PartHit;
import dev.deelter.modelinteractions.model.BodyRegion;
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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
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
	 * So there is a place to see for your own model files.
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
	 * Builtin names plus *.cpmmodel and *.cpmproject files from the config folder.
	 */
	public static List<String> availableModels() {
		TreeSet<String> names = new TreeSet<>(CPM ? BUILTIN : List.of("steve"));
		Path dir = userDir();
		if (CPM && Files.isDirectory(dir)) {
			try (Stream<Path> s = Files.list(dir)) {
				s.map(p -> p.getFileName().toString())
						.filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".cpmmodel") || n.toLowerCase(Locale.ROOT).endsWith(".cpmproject"))
						.forEach(n -> names.add(n.substring(0, n.lastIndexOf('.'))));
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
		Path project = userDir().resolve(model + ".cpmproject");
		if (Files.isRegularFile(project))
			return new java.io.ByteArrayInputStream(dev.deelter.modelinteractions.cpm.CpmProjects.export(project));
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
				// a following dummy walks after the hand by itself
				if (d.motion == Motion.FOLLOW) return;
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

	/**
	 * Dresses a dummy with a copy of the item, in the slot the item belongs to.
	 */
	public static EquipmentSlot equip(UUID uuid, ItemStack stack) {
		EquipmentSlot slot = DUMMIES.get(uuid).entity.getEquipmentSlotForItem(stack);
		equip(uuid, slot, stack);
		return slot;
	}

	public static void equip(UUID uuid, EquipmentSlot slot, ItemStack stack) {
		DUMMIES.get(uuid).entity.setItemSlot(slot, stack.copyWithCount(Math.min(stack.getCount(), slot.isArmor() ? 1 : stack.getCount())));
	}

	/**
	 * Takes off what is worn on that part of the body, the first of its slots with anything in it.
	 * Mobs' other parts have no slots of their own, so any slot will do.
	 */
	public static ItemStack take(UUID uuid, BodyRegion region) {
		LivingEntity e = DUMMIES.get(uuid).entity;
		EquipmentSlot[] order = switch (region) {
			case HEAD -> new EquipmentSlot[]{EquipmentSlot.HEAD};
			case BODY -> new EquipmentSlot[]{EquipmentSlot.CHEST};
			case RIGHT_ARM -> new EquipmentSlot[]{EquipmentSlot.MAINHAND};
			case LEFT_ARM -> new EquipmentSlot[]{EquipmentSlot.OFFHAND};
			case LEFT_LEG, RIGHT_LEG -> new EquipmentSlot[]{EquipmentSlot.LEGS, EquipmentSlot.FEET};
			default -> new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD,
					EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
		};
		for (EquipmentSlot slot : order) {
			ItemStack worn = e.getItemBySlot(slot);
			if (worn.isEmpty()) continue;
			e.setItemSlot(slot, ItemStack.EMPTY);
			return worn;
		}
		return ItemStack.EMPTY;
	}

	/**
	 * The dummy under the crosshair, else the nearest one; null when there are none.
	 */
	public static UUID aimed(Minecraft mc) {
		PartHit hit = PcInput.pick(mc, 1f);
		if (hit != null && isDummy(hit.rig().getUuid())) return hit.rig().getUuid();
		return DUMMIES.values().stream()
				.min(Comparator.comparingDouble(d -> d.entity.distanceToSqr(mc.player)))
				.map(d -> d.entity.getUUID()).orElse(null);
	}

	/**
	 * Toggles following for one dummy; true when it follows now.
	 */
	public static boolean toggleFollow(UUID uuid) {
		Dummy d = DUMMIES.get(uuid);
		if (d == null) return false;
		d.motion = d.motion == Motion.FOLLOW ? Motion.STILL : Motion.FOLLOW;
		d.center = d.entity.position();
		d.speed = 0;
		return d.motion == Motion.FOLLOW;
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
				case FOLLOW -> {
					// the step itself happened in beforeTick; here only where it looks
					d.airborne = false;
					e.setOnGround(true);
					followFace(mc, d);
				}
				case FLOAT -> {
					d.phase += 0.05f;
					e.setPos(d.center.x, d.center.y + 1.2 + Math.sin(d.phase) * 0.05, d.center.z);
					face(d, d.baseYaw);
				}
			}
		}
	}

	/**
	 * How far from its middle the held hand may be before the dummy is pulled along: about an arm's reach.
	 */
	private static final double ARM_REACH = 0.75;
	private static final double FOLLOW_MAX_SPEED = 0.3;

	/**
	 * Called at the start of every client-side living entity tick. A following dummy steps here, inside
	 * its own tick, so the game sees it move and animates its legs (CPM walking animations too).
	 * It doesn't walk to you by itself: it is pulled by the hand you hold, once the arm is stretched.
	 */
	public static void beforeTick(LivingEntity e) {
		if (DUMMIES.isEmpty()) return;
		Dummy d = DUMMIES.get(e.getUUID());
		if (d == null || d.entity != e || d.motion != Motion.FOLLOW) return;
		Minecraft mc = Minecraft.getInstance();
		Vec3 hand = mc.player == null || d.carriedAt != null || d.flying != null ? null : heldHand(e.getUUID());
		d.led = hand != null;
		double want = 0;
		Vec3 to = Vec3.ZERO;
		if (hand != null) {
			to = hand.subtract(e.position()).multiply(1, 0, 1);
			double stretch = to.length() - ARM_REACH;
			if (stretch > 0) want = Math.min(stretch * 0.6, FOLLOW_MAX_SPEED);
		}
		// eases into a walk and out of it instead of starting and stopping dead
		d.speed += (want - d.speed) * 0.35;
		if (d.speed < 0.005 || to.lengthSqr() < 1e-6) {
			if (want == 0 && d.speed < 0.005) d.speed = 0;
			return;
		}
		Vec3 step = to.normalize().scale(d.speed);
		d.walkYaw = (float) Math.toDegrees(Math.atan2(-step.x, step.z));
		walk(mc, e, step);
		d.center = e.position();
	}

	/**
	 * Where our hand holding one of its arms is, in the world; null when no hand holds an arm.
	 */
	private static Vec3 heldHand(UUID uuid) {
		InteractionManager mgr = InteractionManager.get();
		var rig = mgr.existingRig(uuid);
		if (rig == null || !rig.isWorldValid()) return null;
		for (int hand = 0; hand <= Wire.MAX_HAND; hand++) {
			var g = mgr.localGrab(hand);
			if (g == null || !g.getTarget().equals(uuid)) continue;
			var b = rig.bone(g.getBoneId());
			if (b == null || b.getRegion() != BodyRegion.LEFT_ARM && b.getRegion() != BodyRegion.RIGHT_ARM) continue;
			var p = rig.getModelToWorld().transformPosition(new org.joml.Vector3f(g.getGoalModel()));
			return new Vec3(p.x, p.y, p.z);
		}
		return null;
	}

	/**
	 * A step on the real blocks: up a slab or a block edge (like a player), down to the ground below.
	 */
	private static void walk(Minecraft mc, LivingEntity e, Vec3 step) {
		var box = e.getBoundingBox();
		Vec3 move = step;
		if (!mc.level.noCollision(e, box.move(move))) {
			Vec3 up = step.add(0, 0.6, 0);
			if (mc.level.noCollision(e, box.move(up))) move = up;
			else if (mc.level.noCollision(e, box.move(step.x, 0, 0))) move = new Vec3(step.x, 0, 0);
			else if (mc.level.noCollision(e, box.move(0, 0, step.z))) move = new Vec3(0, 0, step.z);
			else return;
		}
		var moved = box.move(move);
		// settle onto whatever is below, at most a block per tick
		double drop = 0;
		while (drop < 1.0 && mc.level.noCollision(e, moved.move(0, -(drop + 0.05), 0))) drop += 0.05;
		e.setPos(e.position().add(move.x, move.y - drop, move.z));
	}

	/**
	 * Faces the way it walks; while led, the head turns a little (not all the way) to the one leading.
	 */
	private static void followFace(Minecraft mc, Dummy d) {
		LivingEntity e = d.entity;
		float body = e.getYRot() + net.minecraft.util.Mth.wrapDegrees(d.walkYaw - e.getYRot()) * 0.3f;
		float turn = 0, pitch = 0;
		if (d.led && mc.player != null) {
			Vec3 v = mc.player.getEyePosition().subtract(e.getEyePosition());
			float toYaw = (float) Math.toDegrees(Math.atan2(-v.x, v.z));
			float toPitch = (float) -Math.toDegrees(Math.atan2(v.y, Math.sqrt(v.x * v.x + v.z * v.z)));
			turn = net.minecraft.util.Mth.clamp(net.minecraft.util.Mth.wrapDegrees(toYaw - body) * 0.35f, -30f, 30f);
			pitch = net.minecraft.util.Mth.clamp(toPitch * 0.3f, -15f, 15f);
		}
		d.headTurn += (turn - d.headTurn) * 0.2f;
		d.headPitch += (pitch - d.headPitch) * 0.2f;
		e.setYRot(body);
		e.setYBodyRot(body);
		e.setYHeadRot(body + d.headTurn);
		e.setXRot(d.headPitch);
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
	public enum Motion {STILL, WALK, SPIN, JUMP, FLOAT, FOLLOW}

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
		/**
		 * FOLLOW: walking speed (blocks/tick), the way it walks, and how far its head is turned to us.
		 */
		double speed;
		float walkYaw, headTurn, headPitch;
		boolean led;

		Dummy(LivingEntity entity, GameProfile profile, Vec3 center, float baseYaw) {
			this.carriedAt = null;
			this.center = center;
			this.entity = entity;
			this.profile = profile;
			this.center = center;
			this.baseYaw = baseYaw;
			this.walkYaw = baseYaw;
		}
	}
}
