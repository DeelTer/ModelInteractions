package dev.deelter.modelinteractions.model;

import dev.deelter.modelinteractions.physics.PartType;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.*;

/**
 * Guesses what an element is from its geometry, because exported CPM models carry no
 * element names. Works in the frame of the vanilla root part (pixels, y down):
 * head box x[-4,4] y[-8,0] z[-4,4]; body box x[-4,4] y[0,12] z[-2,2]. The player faces -Z.
 */
public final class PartClassifier {
	/**
	 * Longest side of a micro detail, and how close it must be to count as sitting on something, px.
	 */
	private static final float DETAIL_PX = 2.5f, CONTACT_PX = 0.3f;

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

	/**
	 * Micro details must not come off what they sit on. They stop being parts of their own: one on a
	 * movable element follows it, one on anything else or on a long lever (a tiny box far from its
	 * pivot, which a small turn throws far away) stays rigid. {@code guessed}: bones typed by
	 * geometry; a type someone picked is left alone. Needs rest frames and types.
	 */
	public static void glueDetails(Collection<Bone> bones, Collection<Bone> guessed) {
		mergeGroups(bones, guessed);
		Set<Bone> parents = new HashSet<>();
		for (Bone b : bones) if (b.getParent() != null) parents.add(b.getParent());
		for (Bone b : guessed) {
			if (parents.contains(b) || !isDetail(b)) continue;
			Bone host = null;
			boolean touches = false;
			for (Bone o : bones) {
				if (o == b || isDetail(o) || o.getRegion() != b.getRegion() || !isTouching(b, o)) continue;
				touches = true;
				if (o.getType().isGrabbable() && (host == null || size(o) > size(host))) host = o;
			}
			if (!touches && b.getLengthPx() < size(b) * 3f) continue;
			b.setType(PartType.STATIC);
			if (host != null && !isAncestor(host, b)) b.setHost(host);
		}
	}

	/**
	 * An empty group of touching elements is one shape built of many boxes (a crown of plates, a
	 * coat): if each swung on its own pivot it would fall apart. The group moves, about its own
	 * pivot, and the boxes stay put in it. Chains are left alone: their joints are meant to bend.
	 */
	private static void mergeGroups(Collection<Bone> bones, Collection<Bone> guessed) {
		Set<Bone> auto = new HashSet<>(guessed);
		Map<Bone, List<Bone>> kids = new HashMap<>();
		for (Bone b : bones) if (b.getParent() != null) kids.computeIfAbsent(b.getParent(), k -> new ArrayList<>()).add(b);
		Set<Bone> merged = new HashSet<>();
		List<Bone> order = new ArrayList<>(bones);
		// bones are parent-first: inner groups go first
		Collections.reverse(order);
		for (Bone group : order) {
			List<Bone> in = kids.get(group);
			if (group.isRoot() || size(group) > 0 || !auto.contains(group) || in == null || in.size() < 2) continue;
			boolean plain = true;
			for (Bone k : in) plain &= auto.contains(k) && (!kids.containsKey(k) || merged.contains(k));
			if (!plain || !isConnected(in, kids)) continue;
			PartType type = commonType(in);
			if (type == null) continue;
			float lever = 0;
			Vector3f pivot = group.model().getTranslation(new Vector3f()), c = new Vector3f();
			for (Bone k : in) {
				k.setType(PartType.STATIC);
				for (Bone leaf : leaves(k, kids, new ArrayList<>()))
					lever = Math.max(lever, leaf.model().transformPosition(leaf.center(c)).distance(pivot));
			}
			group.setType(type);
			group.setLengthPx(lever * 16f);
			merged.add(group);
		}
	}

	private static PartType commonType(List<Bone> bones) {
		EnumMap<PartType, Integer> counts = new EnumMap<>(PartType.class);
		for (Bone b : bones) if (b.getType().isGrabbable()) counts.merge(b.getType(), 1, Integer::sum);
		return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
	}

	private static List<Bone> leaves(Bone b, Map<Bone, List<Bone>> kids, List<Bone> out) {
		if (size(b) > 0) out.add(b);
		for (Bone k : kids.getOrDefault(b, List.of())) leaves(k, kids, out);
		return out;
	}

	private static boolean isConnected(List<Bone> bones, Map<Bone, List<Bone>> kids) {
		Set<Bone> reached = new HashSet<>(List.of(bones.get(0)));
		Deque<Bone> queue = new ArrayDeque<>(reached);
		while (!queue.isEmpty()) {
			List<Bone> from = leaves(queue.pop(), kids, new ArrayList<>());
			for (Bone o : bones) {
				if (reached.contains(o)) continue;
				boolean near = false;
				for (Bone x : from) for (Bone y : leaves(o, kids, new ArrayList<>())) near |= isTouching(x, y);
				if (near && reached.add(o)) queue.add(o);
			}
		}
		return reached.size() == bones.size();
	}

	private static boolean isDetail(Bone b) {
		float s = size(b);
		return !b.isRoot() && s > 0 && s <= DETAIL_PX;
	}

	private static float size(Bone b) {
		Vector3f e = new Vector3f(b.getBoxMax()).sub(b.getBoxMin()).mul(16f);
		return Math.max(e.x, Math.max(e.y, e.z));
	}

	private static boolean isAncestor(Bone a, Bone of) {
		for (Bone p = of.getParent(); p != null; p = p.getParent()) if (p == a) return true;
		return false;
	}

	private static boolean isTouching(Bone a, Bone b) {
		Vector3f minA = new Vector3f(), maxA = new Vector3f(), minB = new Vector3f(), maxB = new Vector3f();
		modelBox(a, minA, maxA);
		modelBox(b, minB, maxB);
		float gap = CONTACT_PX / 16f;
		return minA.x <= maxB.x + gap && minB.x <= maxA.x + gap
				&& minA.y <= maxB.y + gap && minB.y <= maxA.y + gap
				&& minA.z <= maxB.z + gap && minB.z <= maxA.z + gap;
	}

	private static void modelBox(Bone b, Vector3f min, Vector3f max) {
		min.set(Float.MAX_VALUE);
		max.set(-Float.MAX_VALUE);
		Vector3f p = new Vector3f();
		for (int i = 0; i < 8; i++) {
			p.set((i & 1) == 0 ? b.getBoxMin().x : b.getBoxMax().x,
					(i & 2) == 0 ? b.getBoxMin().y : b.getBoxMax().y,
					(i & 4) == 0 ? b.getBoxMin().z : b.getBoxMax().z);
			b.model().transformPosition(p);
			min.min(p);
			max.max(p);
		}
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
