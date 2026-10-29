package dev.deelter.modelinteractions.config;

/** When holding your head may turn your camera. */
public enum LookMode {
	/** Only after 2 seconds without moving the mouse: an idle player can be looked at, an active one keeps control. */
	IDLE,
	ALWAYS,
	NEVER
}
