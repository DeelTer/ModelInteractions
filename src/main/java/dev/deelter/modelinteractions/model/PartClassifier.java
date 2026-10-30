package dev.deelter.modelinteractions.model;

import dev.deelter.modelinteractions.physics.PartType;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Guesses what an element is from its geometry, because exported CPM models carry no
 * element names. Works in the frame of the vanilla root part (pixels, y down):
 * head box x[-4,4] y[-8,0] z[-4,4]; body box x[-4,4] y[0,12] z[-2,2]. The player faces -Z.
 */
public final class PartClassifier {
	private PartClassifier() {
	}

	public static PartType classify(Bone bone, Matrix4f rootToBone) {
		if (bone.isRoot()) {
			return switch (bone.getRegion()) {
				case HEAD -> PartType.HEAD;
				case LEFT_ARM, RIGHT_ARM, LEFT_LEG, RIGHT_LEG -> PartType.LIMB;
				default -> PartType.STATIC;
			};
		}
		Bone parent = bone.getParent();
		if (parent != null && !parent.isRoot()) {
			PartType pt = parent.getType();
			// segments of a chain inherit the chain type
			if (pt == PartType.TAIL || pt == PartType.HAIR || pt == PartType.EAR || pt == PartType.WING) return pt;
			// rigid attachments (horns, hats) stay rigid down the chain, unless the parent is an empty group
			boolean parentEmpty = parent.getBoxMax().equals(parent.getBoxMin());
			if (pt == PartType.ACCESSORY && !parentEmpty) return pt;
		}

		// box in root-part pixels
		Vector3f min = new Vector3f(Float.MAX_VALUE), max = new Vector3f(-Float.MAX_VALUE), p = new Vector3f();
		for (int i = 0; i < 8; i++) {
			p.set((i & 1) == 0 ? bone.getBoxMin().x : bone.getBoxMax().x,
					(i & 2) == 0 ? bone.getBoxMin().y : bone.getBoxMax().y,
					(i & 4) == 0 ? bone.getBoxMin().z : bone.getBoxMax().z);
			rootToBone.transformPosition(p).mul(16f);
			min.min(p);
			max.max(p);
		}
		Vector3f c = new Vector3f(min).add(max).mul(0.5f);
		Vector3f e = new Vector3f(max).sub(min);
		float thin = Math.min(e.x, Math.min(e.y, e.z));
		float longest = Math.max(e.x, Math.max(e.y, e.z));
		float mid = e.x + e.y + e.z - thin - longest;
		boolean elongated = longest >= thin * 2.2f;
		// ears are plates; horns are chains of small chunky boxes
		boolean flat = mid >= 1.5f && thin <= mid * 0.55f;

		return switch (bone.getRegion()) {
			case HEAD -> classifyHead(c, e, min, max, flat, elongated);
			case BODY -> classifyBody(c, e, min, max, thin, elongated);
			case LEFT_ARM, RIGHT_ARM, LEFT_LEG, RIGHT_LEG -> insideBox(min, max, -2.5f, 2.5f, -2.5f, 12.5f, -2.5f, 2.5f)
					? PartType.STATIC : PartType.ACCESSORY;
			default -> PartType.ACCESSORY;
		};
	}

	private static PartType classifyHead(Vector3f c, Vector3f e, Vector3f min, Vector3f max, boolean flat, boolean elongated) {
		if (insideBox(min, max, -4.6f, 4.6f, -8.6f, 0.6f, -4.6f, 4.6f)) return PartType.STATIC;
		// above the head and off-center -> ears, unless chunky (horns)
		if (c.y < -6.5f && Math.abs(c.x) > 1.2f && max.y < -5f) return flat ? PartType.EAR : PartType.ACCESSORY;
		// ears sticking out of the sides (elf / dog ears)
		if (Math.abs(c.x) > 4.2f && c.y < -2f && flat && c.y > -9f) return PartType.EAR;
		// hanging behind or on the sides -> hair
		if ((c.z > 3.5f || Math.abs(c.x) > 3.8f) && max.y > -4f) return PartType.HAIR;
		if (c.y < -8f) return PartType.ACCESSORY; // halo, hat
		return elongated ? PartType.HAIR : PartType.ACCESSORY;
	}

	private static PartType classifyBody(Vector3f c, Vector3f e, Vector3f min, Vector3f max, float thin, boolean elongated) {
		if (insideBox(min, max, -4.6f, 4.6f, -0.6f, 12.6f, -2.6f, 2.6f)) return PartType.STATIC;
		boolean behind = c.z > 2.2f;
		if (behind && c.y >= 7f) return PartType.TAIL;
		if (behind && c.y < 7f && (e.x >= 5f || e.y >= 6f) && thin <= 2.01f) return PartType.WING;
		if (behind && elongated) return PartType.TAIL;
		if (c.y > 12f && elongated) return PartType.TAIL; // tails starting below the body
		return PartType.ACCESSORY;
	}

	private static boolean insideBox(Vector3f min, Vector3f max, float x0, float x1, float y0, float y1, float z0, float z1) {
		return min.x >= x0 && max.x <= x1 && min.y >= y0 && max.y <= y1 && min.z >= z0 && max.z <= z1;
	}
}
