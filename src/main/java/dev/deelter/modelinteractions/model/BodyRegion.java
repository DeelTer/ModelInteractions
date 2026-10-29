package dev.deelter.modelinteractions.model;

/** Vanilla root part an element hangs from. */
public enum BodyRegion {
	HEAD, BODY, LEFT_ARM, RIGHT_ARM, LEFT_LEG, RIGHT_LEG, OTHER;

	public static BodyRegion fromCpmName(String name) {
		return switch (name) {
			case "head" -> HEAD;
			case "body" -> BODY;
			case "left_arm" -> LEFT_ARM;
			case "right_arm" -> RIGHT_ARM;
			case "left_leg" -> LEFT_LEG;
			case "right_leg" -> RIGHT_LEG;
			default -> OTHER;
		};
	}

	public boolean isLimb() {
		return this == LEFT_ARM || this == RIGHT_ARM || this == LEFT_LEG || this == RIGHT_LEG;
	}
}
