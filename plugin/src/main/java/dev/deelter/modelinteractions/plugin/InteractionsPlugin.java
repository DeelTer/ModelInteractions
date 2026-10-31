package dev.deelter.modelinteractions.plugin;

import dev.deelter.modelinteractions.wire.Wire;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Relays Model Interactions messages between nearby players that have the mod.
 * <p>
 * The server never runs physics; it only checks and forwards. What it guarantees:
 * <ul>
 * <li>the sender of a relayed message is the player whose connection it came on</li>
 * <li>every message is size bounded, fully parsed, and dropped if anything is off</li>
 * <li>per-player rate limits for actions, states and requests</li>
 * <li>an action needs its target to exist in the sender's world within reach</li>
 * <li>moves and releases only count for a grab that player really started</li>
 * <li>actions reach only players within {@code range} blocks of the target</li>
 * <li>states are re-encoded from parsed values, so clients only get well formed data</li>
 * </ul>
 * Who may touch a player (their mode, whitelist and blacklist) is enforced here, and the name
 * lists are stripped before permissions go to anyone else. What may be touched (ears, tail...)
 * is checked by every client from the target's published flags, as only clients know which
 * bone is what.
 */
public final class InteractionsPlugin extends JavaPlugin implements PluginMessageListener, Listener {
	private static final int MAX_MESSAGE = Wire.MAX_STATE_BODY + 32;

	private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
	private final Map<UUID, Long> lastHello = new ConcurrentHashMap<>();
	private Settings settings;
	private Carrying carrying;

	private static double clamp(double v, double min, double max) {
		return Math.max(min, Math.min(max, v));
	}

	/**
	 * Permissions as others see them: no name lists. Whitelist mode becomes "everyone" because
	 * this plugin already drops actions from players the owner did not list.
	 */
	static byte[] redacted(Wire.Perm p) {
		int mode = p.mode() == 1 ? 0 : p.mode();
		return Wire.encodePerm(new Wire.Perm(mode, p.flags(), List.of(), List.of()));
	}

	double range() {
		return settings.range();
	}

	@Override
	public void onEnable() {
		saveDefaultConfig();
		loadSettings();
		carrying = new Carrying(this, Carrying.Rules.read(section("carry")));
		getServer().getPluginManager().registerEvents(carrying, this);
		getServer().getScheduler().runTaskTimer(this, () -> carrying.tick(), 1L, 1L);
		getServer().getMessenger().registerIncomingPluginChannel(this, Wire.CHANNEL, this);
		getServer().getMessenger().registerOutgoingPluginChannel(this, Wire.CHANNEL);
		getServer().getPluginManager().registerEvents(this, this);
		// players already online with the mod (plugin reload)
		for (Player p : getServer().getOnlinePlayers()) {
			if (p.getListeningPluginChannels().contains(Wire.CHANNEL)) hello(p);
		}
	}

	private org.bukkit.configuration.ConfigurationSection section(String name) {
		var s = getConfig().getConfigurationSection(name);
		return s != null ? s : getConfig().createSection(name);
	}

	@Override
	public void onDisable() {
		if (carrying != null) carrying.releaseAll();
		getServer().getMessenger().unregisterIncomingPluginChannel(this);
		getServer().getMessenger().unregisterOutgoingPluginChannel(this);
		sessions.clear();
	}

	private void loadSettings() {
		reloadConfig();
		var c = getConfig();
		settings = new Settings(
				clamp(c.getDouble("range", 16), 4, 64),
				clamp(c.getDouble("reach", 6), 2, 16),
				clamp(c.getDouble("state-range", 48), 8, 128),
				c.getBoolean("mobs", true),
				(int) clamp(c.getInt("max-watchers", 32), 1, 256),
				clamp(c.getDouble("rate.actions-per-second", 100), 1, 1000),
				clamp(c.getDouble("rate.actions-burst", 150), 1, 2000),
				clamp(c.getDouble("rate.states-per-second", 2), 0.1, 50),
				clamp(c.getDouble("rate.states-burst", 6), 1, 100),
				clamp(c.getDouble("rate.requests-per-second", 8), 0.1, 100),
				clamp(c.getDouble("rate.requests-burst", 24), 1, 500),
				(long) clamp(c.getLong("cooldown.pet-ms", 250), 0, 10_000),
				(long) clamp(c.getLong("cooldown.hug-ms", 1000), 0, 60_000));
	}

	// ------------------------------------------------------------------ sessions

	@EventHandler
	public void onChannel(PlayerRegisterChannelEvent e) {
		if (Wire.CHANNEL.equals(e.getChannel())) hello(e.getPlayer());
	}

	private void hello(Player p) {
		sessions.computeIfAbsent(p.getUniqueId(), u -> new Session(settings));
		int flags = (settings.mobs() && p.hasPermission("modelinteractions.mobs") ? Wire.HELLO_MOBS : 0)
				| (carrying != null && carrying.mobsAllowedFor(p) ? Wire.HELLO_CARRY : 0)
				| (getConfig().getBoolean("adult-features", false) ? Wire.HELLO_ADULT : 0);
		send(p, Wire.encodeHello((int) settings.range(), flags));
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent e) {
		sessions.remove(e.getPlayer().getUniqueId());
		lastHello.remove(e.getPlayer().getUniqueId());
		carrying.drop(e.getPlayer().getUniqueId());
	}

	@EventHandler
	public void onWorld(PlayerChangedWorldEvent e) {
		Session s = sessions.get(e.getPlayer().getUniqueId());
		if (s != null) s.dropGrabs();
		carrying.drop(e.getPlayer().getUniqueId());
	}

	// ------------------------------------------------------------------ incoming

	@Override
	public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
		if (!Wire.CHANNEL.equals(channel) || message.length > MAX_MESSAGE) return;
		// the client says hello once it is in the world (and again until we answer): only then can it hear us
		if (message.length == 2 && message[0] == Wire.VERSION && message[1] == Wire.K_HELLO) {
			Long last = lastHello.get(player.getUniqueId());
			long now = System.currentTimeMillis();
			if (last == null || now - last >= 1000) {
				lastHello.put(player.getUniqueId(), now);
				hello(player);
			}
			return;
		}
		Session s = sessions.get(player.getUniqueId());
		if (s == null || !player.hasPermission("modelinteractions.use")) return;
		try {
			DataInputStream in = Wire.in(message);
			switch (Wire.kind(in)) {
				case Wire.K_ACT -> {
					if (!s.actions.take()) return;
					Wire.Act act = Wire.readAct(in);
					Wire.end(in);
					onAct(player, s, act);
				}
				case Wire.K_STATE -> {
					if (!s.states.take()) return;
					int state = in.readUnsignedByte();
					if (state >= Wire.S_COUNT) return;
					byte[] body = Wire.canonicalState(state, Wire.readStateBody(in));
					Wire.end(in);
					onState(player, s, state, body);
				}
				case Wire.K_CARRY -> {
					if (!s.actions.take()) return;
					Wire.Carry c = Wire.readCarry(in);
					Wire.end(in);
					Entity t = getServer().getEntity(c.target());
					if (!canAct(player) || !reachable(player, t)) return;
					carrying.onCarry(player, c, t, sessions.get(c.target()));
				}
				case Wire.K_LOOK -> {
					if (!s.actions.take()) return;
					Wire.Look l = Wire.readLook(in);
					Wire.end(in);
					onLook(player, l);
				}
				case Wire.K_REQ -> {
					if (!s.requests.take()) return;
					UUID target = Wire.readUuid(in);
					Wire.end(in);
					onRequest(player, target);
				}
				default -> {
				}
			}
		} catch (IOException | RuntimeException e) {
			s.strikes++;
			if (s.strikes == 20)
				getLogger().warning(player.getName() + " keeps sending malformed interaction messages");
		}
	}

	private void onAct(Player sender, Session s, Wire.Act act) {
		UUID target = act.target();
		// letting go always counts, however far the target got: otherwise everyone sees it held on
		if (act.op() == Wire.OP_RELEASE) {
			if (!s.holds(act.hand(), target, act.bone())) return;
			s.release(act.hand());
			relayRelease(sender, act, getServer().getEntity(target));
			return;
		}
		if (!canAct(sender)) return;
		Entity t;
		if (act.op() == Wire.OP_RESET) {
			// only about yourself
			if (!target.equals(sender.getUniqueId())) return;
			s.dropGrabs();
			t = sender;
		} else {
			t = getServer().getEntity(target);
			if (!reachable(sender, t)) return;
		}

		// the target's own choice of who may touch them
		if (t instanceof Player tp && tp != sender) {
			Session ts = sessions.get(tp.getUniqueId());
			if (ts != null && !ts.allowsActor(sender.getName())) return;
		}

		switch (act.op()) {
			case Wire.OP_GRAB -> s.grab(act.hand(), target, act.bone());
			case Wire.OP_MOVE -> {
				if (!s.holds(act.hand(), target, act.bone())) return;
			}
			case Wire.OP_PET -> {
				if (!s.cooldown("pet", target, settings.petCooldownMs())) return;
			}
			case Wire.OP_PUSH -> {
				if (target.equals(sender.getUniqueId()) || !s.cooldown("push", target, 100)) return;
			}
			case Wire.OP_HUG -> {
				if (target.equals(sender.getUniqueId()) || !s.cooldown("hug", target, settings.hugCooldownMs())) return;
			}
			default -> {
			}
		}
		relay(Wire.encodeAct(act, sender.getUniqueId()), t.getLocation(), settings.range(), sender);
	}

	/**
	 * Turning someone's head: only a player who allows it; everyone near sees their model's head turn.
	 */
	private void onLook(Player sender, Wire.Look l) {
		if (!canAct(sender)) return;
		Player tp = getServer().getPlayer(l.target());
		Session ts = tp == null ? null : sessions.get(tp.getUniqueId());
		if (ts == null || tp == sender || !reachable(sender, tp)) return;
		if (ts.perm == null || (ts.perm.flags() & Wire.FLAG_LOOK) == 0 || !ts.allowsActor(sender.getName())) return;
		// the point to look at is the sender's own eyes, whatever the message says
		Location eye = sender.getEyeLocation();
		byte[] msg = Wire.encodeLook(new Wire.Look(l.active(), tp.getUniqueId(), eye.getX(), eye.getY(), eye.getZ()), sender.getUniqueId());
		// the target is near itself, so the relay reaches it too
		relay(msg, tp.getLocation(), settings.range(), sender);
	}

	private void relayRelease(Player sender, Wire.Act act, Entity target) {
		Location at = target != null && target.isValid() ? target.getLocation() : sender.getLocation();
		relay(Wire.encodeAct(act, sender.getUniqueId()), at, settings.range(), sender);
	}

	private boolean canAct(Player p) {
		return !p.isDead() && p.getGameMode() != GameMode.SPECTATOR;
	}

	/**
	 * The target exists, is alive, in the sender's world and within reach of the sender.
	 */
	private boolean reachable(Player sender, Entity t) {
		if (!(t instanceof LivingEntity le) || !t.isValid() || le.isDead()) return false;
		if (!t.getWorld().equals(sender.getWorld())) return false;
		if (t instanceof Player tp) {
			if (tp.getGameMode() == GameMode.SPECTATOR || !sender.canSee(tp)) return false;
		} else if (!settings.mobs() || !sender.hasPermission("modelinteractions.mobs")) {
			return false;
		}
		// distance from the eyes to the target's box, so big mobs can be reached at their edge
		var box = t.getBoundingBox();
		Location eye = sender.getEyeLocation();
		double dx = Math.max(Math.max(box.getMinX() - eye.getX(), 0), eye.getX() - box.getMaxX());
		double dy = Math.max(Math.max(box.getMinY() - eye.getY(), 0), eye.getY() - box.getMaxY());
		double dz = Math.max(Math.max(box.getMinZ() - eye.getZ(), 0), eye.getZ() - box.getMaxZ());
		return dx * dx + dy * dy + dz * dz <= settings.reach() * settings.reach();
	}

	private void onState(Player owner, Session s, int state, byte[] body) throws IOException {
		if (state == Wire.S_PERM) {
			s.perm = Wire.decodePerm(body);
			body = redacted(s.perm);
		}
		s.stored[state] = body;
		relay(Wire.encodeState(state, owner.getUniqueId(), body), owner.getLocation(), settings.stateRange(), owner);
	}

	private void onRequest(Player asker, UUID target) {
		Player tp = getServer().getPlayer(target);
		Session ts = tp == null ? null : sessions.get(target);
		if (ts == null || !tp.getWorld().equals(asker.getWorld())) return;
		if (tp.getLocation().distanceSquared(asker.getLocation()) > settings.stateRange() * settings.stateRange())
			return;
		for (int i = 0; i < Wire.S_COUNT; i++) {
			if (ts.stored[i] != null) send(asker, Wire.encodeState(i, target, ts.stored[i]));
		}
	}

	/**
	 * To the nearest players with the mod around {@code at}, except the sender.
	 */
	void relay(byte[] msg, Location at, double range, Player except) {
		World w = at.getWorld();
		if (w == null) return;
		Collection<Player> near = at.getNearbyPlayers(range);
		List<Player> out = new ArrayList<>(Math.min(near.size(), settings.maxWatchers()));
		for (Player p : near) {
			if (p != except && sessions.containsKey(p.getUniqueId())) out.add(p);
		}
		if (out.size() > settings.maxWatchers()) {
			out.sort(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(at)));
			out = out.subList(0, settings.maxWatchers());
		}
		for (Player p : out) send(p, msg);
	}

	// ------------------------------------------------------------------ outgoing

	void send(Player p, byte[] msg) {
		p.sendPluginMessage(this, Wire.CHANNEL, msg);
	}

	@Override
	public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
		if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
			loadSettings();
			carrying.setRules(Carrying.Rules.read(section("carry")));
			sessions.values().forEach(s -> s.reconfigure(settings));
			sender.sendMessage("Model Interactions: reloaded, relay range " + (int) settings.range() + " blocks");
			return true;
		}
		if (args.length == 1 && args[0].equalsIgnoreCase("stats")) {
			sender.sendMessage("Model Interactions: " + sessions.size() + " players with the mod");
			return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ admin

	record Settings(double range, double reach, double stateRange, boolean mobs, int maxWatchers,
	                double actRate, double actBurst, double stateRate, double stateBurst, double reqRate,
	                double reqBurst,
	                long petCooldownMs, long hugCooldownMs) {
	}
}
