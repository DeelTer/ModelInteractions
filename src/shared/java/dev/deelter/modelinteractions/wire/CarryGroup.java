package dev.deelter.modelinteractions.wire;

import java.util.*;

/**
 * Everyone holding one thing. Hands from any number of players add up; the thing lifts once
 * enough hands held it for a moment (one is enough to catch it in the air), hangs at the hands' (weighted) middle, and when too few
 * hands are left it flies off with the average speed of the hands that just let go.
 * <p>
 * Plain Java, shared by the server plugin and the client (singleplayer, test dummies), so
 * both follow the same rules. Not thread safe.
 */
public final class CarryGroup {
	/**
	 * A participant that hasn't reported for this long has let go.
	 */
	public static final long STALE_MS = 500;
	/**
	 * Enough hands for this long before it lifts, so two people grabbing ears don't lift by accident.
	 */
	public static final long LIFT_DELAY_MS = 300;
	/**
	 * Catching something in the air has to be quick, or it is gone.
	 */
	public static final long CATCH_DELAY_MS = 80;
	/**
	 * Let-go speeds younger than this count for the throw.
	 */
	public static final long THROW_WINDOW_MS = 250;
	private final int baseRequired;
	private final Map<UUID, Grip> grips = new HashMap<>();
	private final List<Release> releases = new ArrayList<>();
	private boolean grounded = true;
	private long enoughSince = -1;
	private boolean lifted;
	public CarryGroup(int required) {
		this.baseRequired = Math.max(1, required);
	}

	/**
	 * Hands needed: 2 up to {@code soloVolume}, one more per half of it beyond, at most 6.
	 */
	public static int handsNeeded(double volume, double soloVolume) {
		if (volume <= soloVolume) return 2;
		return (int) Math.min(6, 2 + Math.ceil((volume - soloVolume) / (soloVolume * 0.5)));
	}

	/**
	 * Hands needed now: all of them to take it off the ground, one to catch or keep it in the air.
	 */
	public int required() {
		return grounded ? baseRequired : 1;
	}

	/**
	 * Whether it stands on something. In the air one hand holds it; set down on the ground, it is let go.
	 */
	public void setGrounded(boolean grounded) {
		this.grounded = grounded;
	}

	public boolean isLifted() {
		return lifted;
	}

	/**
	 * {@code who} holds it with {@code hands} hands around (x, y, z).
	 */
	public void hold(UUID who, int hands, double x, double y, double z, long now) {
		if (hands <= 0) {
			grips.remove(who);
			return;
		}
		grips.put(who, new Grip(Math.min(hands, Wire.MAX_HAND + 1), x, y, z, now));
	}

	/**
	 * {@code who} let go of some hands ({@code handsLeft} remain), moving at the given speed (blocks/tick).
	 */
	public void letGo(UUID who, int handsLeft, boolean thrown, float vx, float vy, float vz, long now) {
		Grip g = grips.get(who);
		if (g == null) return;
		if (handsLeft <= 0) grips.remove(who);
		else grips.put(who, new Grip(handsLeft, g.x(), g.y(), g.z(), now));
		if (thrown) releases.add(new Release(vx, vy, vz, now));
	}

	public void leave(UUID who) {
		grips.remove(who);
	}

	public boolean isEmpty() {
		return grips.isEmpty();
	}

	public boolean has(UUID who) {
		return grips.containsKey(who);
	}

	public int hands(long now) {
		grips.values().removeIf(g -> now - g.ms() > STALE_MS);
		int n = 0;
		for (Grip g : grips.values()) n += g.hands();
		return n;
	}

	/**
	 * Who holds it right now (a copy).
	 */
	public java.util.Set<UUID> holders() {
		return new java.util.HashSet<>(grips.keySet());
	}

	public int participants() {
		return grips.size();
	}

	/**
	 * Some participant, to name as "the carrier".
	 */
	public UUID anyone() {
		return grips.isEmpty() ? null : grips.keySet().iterator().next();
	}

	/**
	 * Call every tick: whether it starts to hang in the hands, or falls out of them now.
	 */
	public Change update(long now) {
		releases.removeIf(r -> now - r.ms() > THROW_WINDOW_MS);
		boolean enough = hands(now) >= required();
		if (lifted) {
			if (enough) return Change.NONE;
			lifted = false;
			enoughSince = -1;
			return Change.FALL;
		}
		if (!enough) {
			enoughSince = -1;
			return Change.NONE;
		}
		if (enoughSince < 0) enoughSince = now;
		if (now - enoughSince < (grounded ? LIFT_DELAY_MS : CATCH_DELAY_MS)) return Change.NONE;
		lifted = true;
		return Change.LIFT;
	}

	/**
	 * Where the hands are, weighted by how many hands each holder uses. Null when nobody holds it.
	 */
	public double[] goal() {
		double x = 0, y = 0, z = 0;
		int n = 0;
		for (Grip g : grips.values()) {
			x += g.x() * g.hands();
			y += g.y() * g.hands();
			z += g.z() * g.hands();
			n += g.hands();
		}
		return n == 0 ? null : new double[]{x / n, y / n, z / n};
	}

	/**
	 * Average of the throws that just happened, capped at {@code max}; zero for a plain drop.
	 */
	public float[] throwVelocity(double max) {
		if (releases.isEmpty()) return new float[3];
		float x = 0, y = 0, z = 0;
		for (Release r : releases) {
			x += r.vx();
			y += r.vy();
			z += r.vz();
		}
		int n = releases.size();
		x /= n;
		y /= n;
		z /= n;
		// several people throwing together throw a little further than one
		double scale = Math.min(1.5, 1 + 0.15 * (n - 1));
		double len = Math.sqrt(x * x + y * y + z * z) * scale;
		if (len > max && len > 0) scale *= max / len;
		releases.clear();
		return new float[]{(float) (x * scale), (float) (y * scale), (float) (z * scale)};
	}

	public enum Change {NONE, LIFT, FALL}

	private record Grip(int hands, double x, double y, double z, long ms) {
	}

	private record Release(float vx, float vy, float vz, long ms) {
	}
}
