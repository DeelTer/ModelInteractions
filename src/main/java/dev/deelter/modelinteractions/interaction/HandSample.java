package dev.deelter.modelinteractions.interaction;

import java.util.UUID;

import org.joml.Vector3f;

/** A tracked hand in world space this frame, used for passive touch physics. */
public record HandSample(UUID owner, int hand, Vector3f pos, Vector3f velocity) {}
