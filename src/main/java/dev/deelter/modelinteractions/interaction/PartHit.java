package dev.deelter.modelinteractions.interaction;

import dev.deelter.modelinteractions.model.Bone;
import org.joml.Vector3f;

/**
 * A bone hit by a ray or a hand.
 *
 * @param pointModel  hit point in the target's model space
 * @param anchorLocal hit point in the bone's own frame
 * @param distance    ray distance or hand-to-surface distance, blocks
 */
public record PartHit(PlayerRig rig, Bone bone, Vector3f pointModel, Vector3f anchorLocal, float distance) {
}
