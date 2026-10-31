package dev.deelter.modelinteractions.wire;

/**
 * Allows {@code rate} events per second on average and bursts of up to {@code burst}.
 */
public final class TokenBucket {
	private double rate, burst, tokens;
	private long last = System.nanoTime();

	public TokenBucket(double rate, double burst) {
		set(rate, burst);
		tokens = burst;
	}

	public void set(double rate, double burst) {
		this.rate = rate;
		this.burst = burst;
		tokens = Math.min(tokens, burst);
	}

	public boolean take() {
		long now = System.nanoTime();
		tokens = Math.min(burst, tokens + (now - last) / 1e9 * rate);
		last = now;
		if (tokens < 1) return false;
		tokens -= 1;
		return true;
	}
}
