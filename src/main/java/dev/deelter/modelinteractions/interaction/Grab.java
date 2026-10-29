package dev.deelter.modelinteractions.interaction;

import java.util.UUID;

import org.joml.Vector3f;

import lombok.Getter;
import lombok.Setter;

/**
 * One hand holding one bone. Points are in the target's model space so they stay valid
 * while the target walks around, and are the only thing sent over the network.
 */
@Getter
public class Grab {
	public record Key(UUID grabber, int hand) {}

	private final Key key;
	private final UUID target;
	private final int boneId;
	/** Grabbed point in the bone's own frame. */
	private final Vector3f anchorLocal;
	/** Latest known hand point, target model space. */
	private final Vector3f goalModel = new Vector3f();
	/** Smoothed hand point actually used by the physics. */
	private final Vector3f smoothModel = new Vector3f();
	private final boolean local;
	/** The owner doesn't allow it (or nobody else would see it): simulated here, never sent. */
	@Setter private boolean privateOnly;
	/** Keep the shape when this grab ends (VR: tapped with the other hand's trigger). */
	@Setter private boolean keepOnRelease;
	/** Carrying: the held thing's feet relative to this hand, fixed when the hand took hold. */
	@Setter private Vector3f carryOffset;
	@Setter private long lastUpdateNanos = System.nanoTime();
	@Setter private long lastSentNanos;
	private final Vector3f lastSent = new Vector3f(Float.NaN);

	public Grab(Key key, UUID target, int boneId, Vector3f anchorLocal, Vector3f pointModel, boolean local) {
		this.key = key;
		this.target = target;
		this.boneId = boneId;
		this.anchorLocal = new Vector3f(anchorLocal);
		this.goalModel.set(pointModel);
		this.smoothModel.set(pointModel);
		this.local = local;
	}
}
