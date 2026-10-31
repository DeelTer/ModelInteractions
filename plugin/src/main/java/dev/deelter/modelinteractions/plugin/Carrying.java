package dev.deelter.modelinteractions.plugin;

import dev.deelter.modelinteractions.wire.CarryGroup;
import dev.deelter.modelinteractions.wire.Wire;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Lifting and throwing, alone or together. Every player holding something reports their hands;
 * hands add up ({@link CarryGroup}), and bigger things need more of them. Mobs are moved here,
 * by velocity towards the hands' middle, so they still collide with blocks. Players move
 * themselves on their own client; this only checks, forwards, lets them float without a fly
 * kick while held, and spares the fall. Main thread only.
 */
final class Carrying implements Listener {
	private static final double MAX_STEER = 0.8, MAX_HOLDER_DISTANCE_SQ = 36;
	private static final long SOFT_LANDING_MS = 6000;
	/**
	 * Hands "needed" for something that may never be lifted from the ground.
	 */
	private static final int NEVER = 99;
	private static final long BREAK_FREE_MS = 1000;
	/**
	 * Slack of a hand on the ground, blocks: just enough to soak up ping. Beyond it the held one is pulled along.
	 */
	private static final double SLACK = 0.15;
	private final InteractionsPlugin plugin;
	/**
	 * held (or being taken hold of) entity -> its group
	 */
	private final Map<UUID, Held> held = new HashMap<>();
	/**
	 * thrown or dropped entity -> until when falls don't hurt it
	 */
	private final Map<UUID, Long> softLanding = new HashMap<>();
	/**
	 * players whose flight we allowed while held, until when to take it back
	 */
	private final Map<UUID, Long> restoreFlight = new HashMap<>();
	private final Map<UUID, Long> lastThrow = new HashMap<>();
	/** Players -> when they last hit or were hit by something. */
	private final Map<UUID, Long> lastCombat = new HashMap<>();
	private Rules rules;

	Carrying(InteractionsPlugin plugin, Rules rules) {
		this.plugin = plugin;
		this.rules = rules;
	}

	/**
	 * Standing on something solid. Asked of the world, not of the (client-reported) player flag.
	 */
	private static boolean grounded(Entity e) {
		var below = e.getLocation().subtract(0, 0.08, 0).getBlock();
		return below.getType().isSolid() || !(e instanceof Player) && e.isOnGround();
	}

	/**
	 * While held, it and its holders don't push each other apart.
	 */
	private static void setCollision(Held h, boolean on) {
		if (!(h.entity instanceof LivingEntity le)) return;
		if (!on && !h.collisionOff) {
			h.wasCollidable = le.isCollidable();
			le.setCollidable(false);
			h.collisionOff = true;
		} else if (on && h.collisionOff) {
			le.setCollidable(h.wasCollidable);
			h.collisionOff = false;
		}
	}

	void setRules(Rules rules) {
		this.rules = rules;
	}

	boolean mobsAllowedFor(Player p) {
		return rules.mobs() && p.hasPermission("modelinteractions.carry");
	}

	/**
	 * One CARRY message from {@code sender}, already parsed; {@code t} exists and is in reach.
	 */
	void onCarry(Player sender, Wire.Carry c, Entity t, Session targetSession) {
		long now = System.currentTimeMillis();
		UUID who = sender.getUniqueId();
		Held h = held.get(t.getUniqueId());
		switch (c.sub()) {
			case Wire.CARRY_START, Wire.CARRY_MOVE -> {
				if (h == null) {
					int required = mayCarry(sender, t, targetSession);
					boolean drag = mayDrag(sender, t, targetSession);
					if (required <= 0 && !drag) return;
					h = new Held(t, required, drag);
					held.put(t.getUniqueId(), h);
				} else if (!h.group.has(who) && mayCarry(sender, t, targetSession) <= 0 && !mayDrag(sender, t, targetSession)) {
					return;
				}
				Location goal = clampToHolder(sender, c, t);
				// two hands per player at most, however many a client claims
				h.group.hold(who, Math.min(c.hands(), 2), goal.getX(), goal.getY(), goal.getZ(), now);
			}
			case Wire.CARRY_THROW, Wire.CARRY_DROP -> {
				if (h == null || !h.group.has(who)) return;
				boolean thrown = c.sub() == Wire.CARRY_THROW && cooldownOk(who, now);
				h.group.letGo(who, Math.min(c.hands(), 2), thrown, c.vx(), c.vy(), c.vz(), now);
			}
			default -> {
			}
		}
	}

	private boolean cooldownOk(UUID who, long now) {
		Long last = lastThrow.get(who);
		if (last != null && now - last < rules.throwCooldownMs()) return false;
		lastThrow.put(who, now);
		return true;
	}

	/**
	 * The goal is where the held thing's feet go. The hands are on the holder's body, and the
	 * feet are at most the thing's own size away from a hand, so the goal stays near the holder.
	 */
	private Location clampToHolder(Player sender, Wire.Carry c, Entity t) {
		Location eye = sender.getEyeLocation();
		Location goal = new Location(sender.getWorld(), c.x(), c.y(), c.z());
		double max = rules.holdDistance() + Math.max(t.getHeight(), t.getWidth());
		if (goal.distanceSquared(eye) > max * max) {
			Vector d = goal.toVector().subtract(eye.toVector()).normalize().multiply(max);
			goal = eye.clone().add(d);
		}
		return goal;
	}

	/**
	 * Hands needed to lift {@code t}, or 0 when {@code sender} may not take hold of it at all.
	 */
	/**
	 * Holding must not be a way out of a fight: a mob that hunts someone can't be held (by the
	 * "hostile" rule), nor a player who is in combat.
	 */
	private boolean tooDangerous(Entity t, long now) {
		if (t instanceof Player) {
			Long last = lastCombat.get(t.getUniqueId());
			return last != null && now - last < rules.combatLockMs();
		}
		return switch (rules.hostile()) {
			case "all" -> false;
			case "none" -> t instanceof org.bukkit.entity.Monster || hunting(t);
			default -> hunting(t);
		};
	}

	private static boolean hunting(Entity t) {
		return t instanceof org.bukkit.entity.Mob m && m.getTarget() != null;
	}

	private int mayCarry(Player sender, Entity t, Session targetSession) {
		if (tooDangerous(t, System.currentTimeMillis())) return 0;
		if (!(t instanceof LivingEntity) || t == sender || t.isInsideVehicle() || !t.getPassengers().isEmpty())
			return 0;
		double vol = t.getWidth() * t.getWidth() * t.getHeight();
		if (t instanceof Player) {
			// the target said yes (published, server-side flags) to this sender
			boolean ok = targetSession != null && targetSession.perm != null
					&& (targetSession.perm.flags() & Wire.FLAG_CARRY) != 0 && targetSession.allowsActor(sender.getName());
			return ok ? 2 : 0;
		}
		if (!mobsAllowedFor(sender)) return 0;
		if (rules.deny().contains(t.getType().getKey().getKey())) return 0;
		if (t.getWidth() > rules.groupMaxWidth() || t.getHeight() > rules.groupMaxHeight()) return 0;
		// someone else's pet
		if (t instanceof Tameable tm && tm.isTamed() && tm.getOwnerUniqueId() != null && !tm.getOwnerUniqueId().equals(sender.getUniqueId())) {
			return 0;
		}
		return CarryGroup.handsNeeded(vol, rules.soloVolume());
	}

	/**
	 * Pulled along on the ground by a hand: players who allow it, mobs by the server's rules.
	 */
	private boolean mayDrag(Player sender, Entity t, Session targetSession) {
		if (tooDangerous(t, System.currentTimeMillis())) return false;
		if (!(t instanceof LivingEntity) || t == sender || t.isInsideVehicle()) return false;
		if (t instanceof Player) {
			return targetSession != null && targetSession.perm != null
					&& (targetSession.perm.flags() & Wire.FLAG_DRAG) != 0 && targetSession.allowsActor(sender.getName());
		}
		if (!rules.dragMobs() || !mobsAllowedFor(sender) || rules.deny().contains(t.getType().getKey().getKey()))
			return false;
		if (t.getWidth() > 3 || t.getHeight() > 4) return false;
		return !(t instanceof Tameable tm && tm.isTamed() && tm.getOwnerUniqueId() != null && !tm.getOwnerUniqueId().equals(sender.getUniqueId()));
	}

	/**
	 * 1 for small things, down to 0.3 for the biggest a group may lift.
	 */
	private double massFactor(Entity t) {
		double vol = t.getWidth() * t.getWidth() * t.getHeight();
		double maxVol = rules.groupMaxWidth() * rules.groupMaxWidth() * rules.groupMaxHeight();
		return Math.max(0.3, 1 - 0.7 * Math.min(1, vol / maxVol));
	}

	/**
	 * Every tick: drop holders that left, lift / steer / let fall, restore flight.
	 */
	void tick() {
		long now = System.currentTimeMillis();
		Iterator<Held> it = held.values().iterator();
		while (it.hasNext()) {
			Held h = it.next();
			if (!h.entity.isValid()) {
				if (h.group.isLifted() && h.entity instanceof Player p && !h.hadFlight)
					restoreFlight.put(p.getUniqueId(), now);
				it.remove();
				continue;
			}
			if (h.group.isEmpty() && !h.group.isLifted()) setCollision(h, true);
			// holders that went offline, to another world or too far
			for (UUID who : h.group.holders()) {
				Player p = plugin.getServer().getPlayer(who);
				if (p == null || !p.getWorld().equals(h.entity.getWorld())
						|| p.getLocation().distanceSquared(h.entity.getLocation()) > MAX_HOLDER_DISTANCE_SQ) {
					h.group.leave(who);
				}
			}
			// turned hostile or got into a fight in someone's hands: it breaks free after a moment
			if (tooDangerous(h.entity, now)) {
				if (h.angrySince == 0) h.angrySince = now;
				if (h.entity instanceof Player || now - h.angrySince >= BREAK_FREE_MS) {
					for (UUID who : h.group.holders()) h.group.leave(who);
				}
			} else {
				h.angrySince = 0;
			}
			// a mob that may only be dragged isn't caught either: only players who allow being held
			h.group.setGrounded(grounded(h.entity) || !h.canLift && !(h.entity instanceof Player));
			switch (h.group.update(now)) {
				case LIFT -> lift(h);
				case FALL -> fall(h, now);
				default -> {
				}
			}
			boolean holding = !h.group.isEmpty() && (h.group.isLifted() || h.canDrag);
			setCollision(h, !holding);
			if (h.group.isLifted()) steer(h);
			else if (!h.group.isEmpty() && h.canDrag) tether(h);
			else if (h.group.isEmpty()) it.remove();
		}
		restoreFlight.entrySet().removeIf(e -> {
			if (now < e.getValue()) return false;
			// held up again meanwhile: lift() took the entry over, nothing to do here
			Held again = held.get(e.getKey());
			if (again != null && again.group.isLifted()) return true;
			Player p = plugin.getServer().getPlayer(e.getKey());
			if (p != null && p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) {
				p.setFlying(false);
				p.setAllowFlight(false);
			}
			return true;
		});
		softLanding.values().removeIf(t -> now > t);
		lastThrow.values().removeIf(t -> now - t > 60_000);
		lastCombat.values().removeIf(t -> now - t > 60_000);
	}

	/**
	 * Everyone around learns that it is held up, so their clients let it swing from the grip.
	 */
	private void announce(Held h, int sub) {
		Location at = h.entity.getLocation();
		UUID carrier = h.group.anyone();
		Wire.Carry c = new Wire.Carry(sub, h.entity.getUniqueId(), at.getX(), at.getY(), at.getZ(), 0, 0, 0, 0);
		plugin.relay(Wire.encodeCarry(c, carrier != null ? carrier : h.entity.getUniqueId()), at, plugin.range(),
				h.entity instanceof Player p ? p : null);
	}

	private void lift(Held h) {
		announce(h, Wire.CARRY_START);
		h.hadGravity = h.entity.hasGravity();
		if (h.entity instanceof Player p) {
			// still flying on the flight we gave last time: that wasn't theirs to begin with
			h.hadFlight = restoreFlight.remove(p.getUniqueId()) == null && p.getAllowFlight();
			p.setAllowFlight(true);
			tell(p, Wire.CARRY_START, h, new Vector());
		} else {
			h.entity.setGravity(false);
		}
	}

	private void steer(Held h) {
		double[] g = h.group.goal();
		if (g == null) return;
		if (h.entity instanceof Player p) {
			tell(p, Wire.CARRY_MOVE, h, new Vector());
			return;
		}
		// the goal is for the feet: every hand keeps hold of its own point
		Vector d = new Vector(g[0], g[1], g[2]).subtract(h.entity.getLocation().toVector()).multiply(0.5);
		if (d.length() > MAX_STEER) d.normalize().multiply(MAX_STEER);
		h.entity.setVelocity(d);
		h.entity.setFallDistance(0);
	}

	/**
	 * On the ground and held: pulled towards the hands, the way a hand pulls an arm.
	 */
	private void tether(Held h) {
		double[] g = h.group.goal();
		if (g == null) return;
		if (h.entity instanceof Player p) {
			tell(p, Wire.CARRY_TETHER, h, new Vector());
			return;
		}
		Vector to = new Vector(g[0], 0, g[2]).subtract(h.entity.getLocation().toVector().setY(0));
		double d = to.length();
		if (d <= SLACK) return;
		Vector v = h.entity.getVelocity();
		Vector pull = to.normalize().multiply(Math.min((d - SLACK) * 0.8, 0.7));
		h.entity.setVelocity(new Vector(pull.getX(), v.getY(), pull.getZ()));
	}

	private void fall(Held h, long now) {
		announce(h, Wire.CARRY_DROP);
		float[] v = h.group.throwVelocity(rules.maxThrow() * massFactor(h.entity));
		Vector vel = new Vector(v[0], v[1], v[2]);
		if (rules.noFallDamage()) softLanding.put(h.entity.getUniqueId(), now + SOFT_LANDING_MS);
		if (h.entity instanceof Player p) {
			tell(p, vel.lengthSquared() > 0 ? Wire.CARRY_THROW : Wire.CARRY_DROP, h, vel);
			// floating a moment longer while they fall must not kick them for flying
			if (!h.hadFlight) restoreFlight.put(p.getUniqueId(), now + SOFT_LANDING_MS);
		} else {
			h.entity.setGravity(h.hadGravity);
			h.entity.setVelocity(vel);
		}
	}

	/**
	 * Players move themselves: tell them where the hands are, with the server's values.
	 */
	private void tell(Player p, int sub, Held h, Vector v) {
		double[] g = h.group.goal();
		if (g == null) g = new double[]{p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ()};
		UUID carrier = h.group.anyone();
		Wire.Carry out = new Wire.Carry(sub, p.getUniqueId(), g[0], g[1], g[2], (float) v.getX(), (float) v.getY(), (float) v.getZ(),
				h.group.hands(System.currentTimeMillis()));
		plugin.send(p, Wire.encodeCarry(out, carrier != null ? carrier : p.getUniqueId()));
	}

	@EventHandler(ignoreCancelled = true)
	public void onCombat(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
		long now = System.currentTimeMillis();
		if (e.getEntity() instanceof Player victim) lastCombat.put(victim.getUniqueId(), now);
		Entity damager = e.getDamager();
		if (damager instanceof org.bukkit.entity.Projectile p && p.getShooter() instanceof Entity shooter) damager = shooter;
		if (damager instanceof Player attacker) lastCombat.put(attacker.getUniqueId(), now);
	}

	@EventHandler
	public void onFall(EntityDamageEvent e) {
		if (e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
		Long until = softLanding.get(e.getEntity().getUniqueId());
		if (until != null && System.currentTimeMillis() < until) e.setCancelled(true);
	}

	/**
	 * A player left or changed world: they let go of everything, and whatever held them lets go.
	 */
	void drop(UUID player) {
		for (Held h : held.values()) h.group.leave(player);
		Held self = held.get(player);
		// flight is saved with the player: take ours back before they go, or they'd keep it
		Player p = plugin.getServer().getPlayer(player);
		boolean ours = restoreFlight.remove(player) != null || self != null && self.group.isLifted() && !self.hadFlight;
		if (ours && p != null && p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR) {
			p.setFlying(false);
			p.setAllowFlight(false);
		}
		if (self != null) {
			setCollision(self, true);
			// they are gone from under the holders; nothing to put back
			held.remove(player);
		}
	}

	/**
	 * Plugin disabled: put every mob's gravity back and take away flight we gave.
	 */
	void releaseAll() {
		for (Held h : held.values()) setCollision(h, true);
		for (Held h : held.values()) {
			if (!h.group.isLifted()) continue;
			if (h.entity instanceof Player p) {
				if (!h.hadFlight && p.isOnline()) p.setAllowFlight(false);
			} else if (h.entity.isValid()) {
				h.entity.setGravity(h.hadGravity);
			}
		}
		for (Held h : held.values()) setCollision(h, true);
		held.clear();
		for (UUID u : restoreFlight.keySet()) {
			Player p = plugin.getServer().getPlayer(u);
			if (p != null && p.getGameMode() != GameMode.CREATIVE && p.getGameMode() != GameMode.SPECTATOR)
				p.setAllowFlight(false);
		}
		restoreFlight.clear();
	}

	record Rules(boolean mobs, boolean dragMobs, double maxWidth, double maxHeight, double groupMaxWidth,
	             double groupMaxHeight,
	             double holdDistance, double maxThrow, long throwCooldownMs, boolean noFallDamage, Set<String> deny, String hostile, long combatLockMs) {
		static Rules read(ConfigurationSection c) {
			double w = Math.max(0.1, c.getDouble("max-width", 1.0)), h = Math.max(0.1, c.getDouble("max-height", 1.6));
			return new Rules(c.getBoolean("mobs", true), c.getBoolean("drag-mobs", true), w, h,
					Math.max(w, c.getDouble("group-max-width", 1.6)),
					Math.max(h, c.getDouble("group-max-height", 2.0)),
					Math.max(0.5, Math.min(6, c.getDouble("hold-distance", 2.5))),
					Math.max(0, Math.min(Wire.MAX_THROW, c.getDouble("max-throw-speed", 1.6))),
					Math.max(0, c.getLong("throw-cooldown-ms", 1000)),
					c.getBoolean("no-fall-damage", true),
					c.getStringList("deny").stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet()),
					c.getString("hostile", "calm").toLowerCase(Locale.ROOT),
					Math.max(0, c.getLong("combat-lock-ms", 5000)));
		}

		double soloVolume() {
			return maxWidth * maxWidth * maxHeight;
		}
	}

	private static final class Held {
		final Entity entity;
		final CarryGroup group;
		/**
		 * May be lifted at all, or only pulled along on the ground.
		 */
		final boolean canLift, canDrag;
		long angrySince;
		boolean hadGravity, hadFlight;
		boolean wasCollidable = true, collisionOff;

		Held(Entity entity, int required, boolean canDrag) {
			this.entity = entity;
			// only dragged: never off the ground (no number of hands is enough), but a player held by
			// the hand who falls is caught, since in the air one hand holds
			this.group = new CarryGroup(required > 0 ? required : NEVER);
			this.canLift = required > 0;
			this.canDrag = canDrag;
		}
	}
}
