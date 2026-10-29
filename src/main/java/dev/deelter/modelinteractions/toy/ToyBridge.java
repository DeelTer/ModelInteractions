package dev.deelter.modelinteractions.toy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.deelter.modelinteractions.ModelInteractions;
import dev.deelter.modelinteractions.config.MiConfig;
import dev.deelter.modelinteractions.net.RelayNet;
import net.minecraft.client.Minecraft;

/**
 * Drives the player's own external device through the Lovense Remote app running on the same
 * computer (its local "game mode" API). Only touches on the player's own model reach it, and
 * nothing about the device ever goes over the network.
 * <p>
 * Off unless both the server announced adult features and the player turned it on; capped,
 * and it stops by itself a moment after the last touch.
 */
public final class ToyBridge {
	/** Lovense levels are 0..20. */
	public static final int MAX_LEVEL = 20;
	private static final long DECAY_START = 600_000_000L, SEND_INTERVAL = 200_000_000L;

	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "modelinteractions-toy");
		t.setDaemon(true);
		return t;
	});
	private static HttpClient http;

	/** 0..1, raised by touches, falls off on its own. */
	private static float intensity;
	private static long lastTouch, lastSent;
	private static int sentLevel;
	private static boolean warned;

	private ToyBridge() {}

	/** Allowed here and now: the player wants it and this server (or singleplayer) offers it. */
	public static boolean active() {
		MiConfig.Toy t = MiConfig.get().getToy();
		if (!t.isEnabled()) return false;
		Minecraft mc = Minecraft.getInstance();
		return mc.hasSingleplayerServer() || RelayNet.isActive() && RelayNet.isAdultAllowed();
	}

	/** A touch on one of our zones; {@code strength} 0..1. */
	public static void touch(float strength) {
		if (!active() || strength <= 0) return;
		intensity = Math.min(1f, Math.max(intensity, strength));
		lastTouch = System.nanoTime();
	}

	/** Every client tick: fade out, and tell the app when the level changed. */
	public static void tick() {
		long now = System.nanoTime();
		if (intensity > 0 && now - lastTouch > DECAY_START) intensity = Math.max(0, intensity - 0.08f);
		if (!active()) intensity = 0;
		int cap = Math.clamp(MiConfig.get().getToy().getMaxLevel(), 0, MAX_LEVEL);
		int level = Math.round(intensity * cap);
		if (level == sentLevel || now - lastSent < SEND_INTERVAL && level != 0) return;
		lastSent = now;
		sentLevel = level;
		send(level == 0 ? "Stop" : "Vibrate:" + level, level == 0 ? 0 : 1);
	}

	/** Panic: zero now and tell the app at once. */
	public static void stop() {
		intensity = 0;
		sentLevel = 0;
		lastSent = System.nanoTime();
		send("Stop", 0);
	}

	private static void send(String action, int seconds) {
		String url = MiConfig.get().getToy().getUrl();
		// only the local app: never send this anywhere else
		if (!isLocal(url)) {
			if (!warned) ModelInteractions.LOG.warn("Toy url {} is not on this computer; ignoring it", url);
			warned = true;
			return;
		}
		String body = String.format(Locale.ROOT, "{\"command\":\"Function\",\"action\":\"%s\",\"timeSec\":%d,\"apiVer\":1}", action, seconds);
		IO.execute(() -> {
			try {
				if (http == null) http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
				HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2))
						.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
				http.send(req, HttpResponse.BodyHandlers.discarding());
			} catch (Exception e) {
				if (!warned) ModelInteractions.LOG.warn("Couldn't reach the toy app at {}: {}", url, e.toString());
				warned = true;
			}
		});
	}

	static boolean isLocal(String url) {
		try {
			String host = URI.create(url).getHost();
			return host != null && (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1")
					|| host.equals("[::1]") || host.endsWith(".lovense.club") && host.startsWith("127-0-0-1"));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
