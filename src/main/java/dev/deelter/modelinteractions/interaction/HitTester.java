package dev.deelter.modelinteractions.interaction;

import java.util.function.Predicate;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import dev.deelter.modelinteractions.model.Bone;

/** Ray and sphere tests against bone boxes. Pure math, all inputs in world space. */
public final class HitTester {
	private HitTester() {}

	public static PartHit raycast(PlayerRig rig, Vector3f origin, Vector3f dir, float maxDist, Predicate<Bone> filter) {
		if (!rig.isWorldValid()) return null;
		PartHit best = null;
		Matrix4f inv = new Matrix4f();
		Vector3f o = new Vector3f(), d = new Vector3f(), p = new Vector3f();
		for (Bone b : rig.bones()) {
			if (!b.isInteractive() || !filter.test(b)) continue;
			// world -> bone frame
			rig.getModelToWorld().mul(b.deformed(), inv).invert();
			inv.transformPosition(origin, o);
			inv.transformDirection(dir, d);
			float t = slab(o, d, b.getBoxMin(), b.getBoxMax());
			if (t < 0) continue;
			// t is in units of the transformed direction; convert to world distance
			Vector3f local = new Vector3f(d).mul(t).add(o);
			Vector3f world = rig.getModelToWorld().mul(b.deformed(), new Matrix4f()).transformPosition(local, p);
			float dist = world.distance(origin);
			if (dist > maxDist || (best != null && dist >= best.distance())) continue;
			Vector3f model = b.deformed().transformPosition(new Vector3f(local));
			best = new PartHit(rig, b, model, local, dist);
		}
		return best;
	}

	/** Closest bone whose box is within {@code radius} of a world point. */
	public static PartHit sphere(PlayerRig rig, Vector3f center, float radius, Predicate<Bone> filter) {
		if (!rig.isWorldValid()) return null;
		PartHit best = null;
		Matrix4f m = new Matrix4f(), inv = new Matrix4f();
		Vector3f c = new Vector3f(), w = new Vector3f();
		for (Bone b : rig.bones()) {
			if (!b.isInteractive() || !filter.test(b)) continue;
			rig.getModelToWorld().mul(b.deformed(), m);
			m.invert(inv);
			inv.transformPosition(center, c);
			Vector3f closest = new Vector3f(c).max(b.getBoxMin()).min(b.getBoxMax());
			float dist = m.transformPosition(closest, w).distance(center);
			if (dist > radius || (best != null && dist >= best.distance())) continue;
			best = new PartHit(rig, b, b.deformed().transformPosition(new Vector3f(closest)), new Vector3f(closest), dist);
		}
		return best;
	}

	/** Ray/AABB slab test, returns entry t (0 if inside) or -1. */
	static float slab(Vector3f o, Vector3f d, Vector3f min, Vector3f max) {
		float tmin = 0, tmax = Float.MAX_VALUE;
		for (int a = 0; a < 3; a++) {
			float oa = o.get(a), da = d.get(a), lo = min.get(a), hi = max.get(a);
			if (Math.abs(da) < 1e-8f) {
				if (oa < lo || oa > hi) return -1;
				continue;
			}
			float t1 = (lo - oa) / da, t2 = (hi - oa) / da;
			if (t1 > t2) {
				float s = t1;
				t1 = t2;
				t2 = s;
			}
			tmin = Math.max(tmin, t1);
			tmax = Math.min(tmax, t2);
			if (tmin > tmax) return -1;
		}
		return tmin;
	}
}
