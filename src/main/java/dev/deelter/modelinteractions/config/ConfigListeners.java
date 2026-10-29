package dev.deelter.modelinteractions.config;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Notified after the config is saved (e.g. to republish permissions). */
public final class ConfigListeners {
	private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

	private ConfigListeners() {}

	public static void add(Runnable r) {
		LISTENERS.add(r);
	}

	static void fire() {
		LISTENERS.forEach(Runnable::run);
	}
}
