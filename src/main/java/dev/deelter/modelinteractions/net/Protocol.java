package dev.deelter.modelinteractions.net;

import dev.deelter.modelinteractions.config.PermissionMode;
import dev.deelter.modelinteractions.interaction.PermissionState;
import dev.deelter.modelinteractions.physics.PartType;
import dev.deelter.modelinteractions.wire.Wire;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.*;

/**
 * Conversions between the shared {@link Wire} format and the mod's own types. Only intents
 * and sparse target points are sent; every client runs the physics itself.
 */
public final class Protocol {
	private Protocol() {
	}

	public static Wire.Act grab(UUID target, int bone, int hand, Vector3f anchor, Vector3f point) {
		return new Wire.Act(Wire.OP_GRAB, target, bone, hand, false, arr(anchor), arr(point));
	}

	public static Wire.Act move(UUID target, int bone, int hand, Vector3f anchor, Vector3f point) {
		return new Wire.Act(Wire.OP_MOVE, target, bone, hand, false, arr(anchor), arr(point));
	}

	public static Wire.Act release(UUID target, int bone, int hand, boolean keep) {
		return new Wire.Act(Wire.OP_RELEASE, target, bone, hand, keep, null, null);
	}

	public static Wire.Act push(UUID target, int bone, Vector3f contactModel, Vector3f velocityModel) {
		return new Wire.Act(Wire.OP_PUSH, target, bone, 0, false, arr(contactModel), arr(velocityModel));
	}

	public static Wire.Act act(int op, UUID target) {
		return Wire.Act.simple(op, target);
	}

	public static float[] arr(Vector3f v) {
		return new float[]{clamp(v.x), clamp(v.y), clamp(v.z)};
	}

	private static float clamp(float f) {
		return Float.isFinite(f) ? Math.clamp(f, -Wire.MAX_COORD, Wire.MAX_COORD) : 0f;
	}

	public static Vector3f vec(float[] a, Vector3f dest) {
		return dest.set(a[0], a[1], a[2]);
	}

	// ---- permissions ----

	public static byte[] perm(PermissionState s) {
		return Wire.encodePerm(new Wire.Perm(s.mode().ordinal(), s.flags(), new ArrayList<>(s.whitelist()), new ArrayList<>(s.blacklist())));
	}

	public static PermissionState readPerm(byte[] body) throws IOException {
		Wire.Perm p = Wire.decodePerm(body);
		PermissionMode mode = p.mode() < PermissionMode.VALUES.length ? PermissionMode.VALUES[p.mode()] : PermissionMode.NOBODY;
		return new PermissionState(mode, p.flags(), new java.util.HashSet<>(p.whitelist()), new java.util.HashSet<>(p.blacklist()));
	}

	// ---- owner-picked part types ----

	public static byte[] types(String modelKey, Map<Integer, PartType> types) {
		Map<Integer, Integer> m = new LinkedHashMap<>();
		types.forEach((id, t) -> m.put(id, t.ordinal()));
		return Wire.encodeTypes(new Wire.Types(modelKey, m));
	}

	public static OwnerTypes readTypes(byte[] body) throws IOException {
		Wire.Types t = Wire.decodeTypes(body);
		Map<Integer, PartType> out = new HashMap<>();
		t.types().forEach((id, o) -> {
			if (o < PartType.VALUES.length) out.put(id, PartType.VALUES[o]);
		});
		return new OwnerTypes(t.modelKey(), out);
	}

	public static byte[] pose(Map<Integer, Vector3f> pinned) {
		Map<Integer, int[]> m = new LinkedHashMap<>();
		// milliradians are plenty for a resting pose
		pinned.forEach((id, v) -> m.put(id, new int[]{Math.round(v.x * 1000f), Math.round(v.y * 1000f), Math.round(v.z * 1000f)}));
		return Wire.encodePose(m);
	}

	// ---- saved pose ----

	public static Map<Integer, Vector3f> readPose(byte[] body) throws IOException {
		Map<Integer, Vector3f> out = new HashMap<>();
		Wire.decodePose(body).forEach((id, v) -> out.put(id, new Vector3f(v[0], v[1], v[2]).div(1000f)));
		return out;
	}

	public record OwnerTypes(String modelKey, Map<Integer, PartType> types) {
	}
}
