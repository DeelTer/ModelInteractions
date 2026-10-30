package dev.deelter.modelinteractions.interaction;

import org.joml.Vector3f;

import java.util.UUID;

/**
 * A tracked hand in world space this frame, used for passive touch physics.
 */
public record HandSample(UUID owner, int hand, Vector3f pos, Vector3f velocity) {
}
