package dev.deelter.modelinteractions.plugin;

/** Allows {@code rate} events per second on average and bursts of up to {@code burst}. */
final class TokenBucket {
	private double rate, burst, tokens;
	private long last = System.nanoTime();

	TokenBucket(double rate, double burst) {
		set(rate, burst);
		tokens = burst;
	}

	void set(double rate, double burst) {
		this.rate = rate;
		this.burst = burst;
		tokens = Math.min(tokens, burst);
	}

	boolean take() {
		long now = System.nanoTime();
		tokens = Math.min(burst, tokens + (now - last) / 1e9 * rate);
		last = now;
		if (tokens < 1) return false;
		tokens -= 1;
		return true;
	}
}
