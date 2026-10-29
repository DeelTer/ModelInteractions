package dev.deelter.modelinteractions.vanilla;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import dev.deelter.modelinteractions.ModelInteractions;
import dev.deelter.modelinteractions.config.MiConfig;
import dev.deelter.modelinteractions.interaction.InteractionManager;
import dev.deelter.modelinteractions.interaction.PlayerRig;
import dev.deelter.modelinteractions.mixin.ModelPartAccessor;
import dev.deelter.modelinteractions.model.BodyRegion;
import dev.deelter.modelinteractions.model.Bone;
import dev.deelter.modelinteractions.physics.PartType;
import dev.deelter.modelinteractions.physics.SpringJoint;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;

/**
 * Backend for ordinary entity models: every mob, and players without a CPM model. Bones are
 * the model's {@link ModelPart}s, typed by their names ("left_ear", "tail"...).
 * Render thread only.
 */
public final class VanillaHooks {
	/** A model further than this from its entity is drawn somewhere else (inventory, GUI). */
	private static final float MAX_WORLD_OFFSET = 12f;
	/** Entities further than this from the camera get no rig at all. */
	private static final double MAX_DISTANCE_SQ = 48 * 48;

	private record Layout(ModelPart[] parts, String[] names, int[] parents) {}

	private static final Map<Model<?>, Layout> LAYOUTS = new WeakHashMap<>();
	/** layer model -> body model -> body part index for each layer part (-1: none). */
	private static final Map<Model<?>, Map<Model<?>, int[]>> LAYER_MAPS = new WeakHashMap<>();
	/** Parts changed for the draw in progress and their posed values, put back after it. */
	private static final List<Object[]> CHANGED = new ArrayList<>();
	private static final Quaternionf Q = new Quaternionf(), Q2 = new Quaternionf();
	private static final Vector3f V = new Vector3f();

	private VanillaHooks() {}

	public static void onPosed(Model<?> model, MiRenderState s, Matrix4f pose) {
		MiConfig cfg = MiConfig.get();
		if (!cfg.isEnabled() || (!s.mi$player() && !cfg.isMobs())) return;
		try {
			posed(model, s, pose, cfg);
		} catch (RuntimeException e) {
			ModelInteractions.LOG.warn("Failed to apply interactions to {}", model.getClass().getName(), e);
			LAYOUTS.remove(model);
		}
	}

	private static void posed(Model<?> model, MiRenderState s, Matrix4f pose, MiConfig cfg) {
		InteractionManager mgr = InteractionManager.get();
		long now = System.nanoTime();
		var st = (net.minecraft.client.renderer.entity.state.EntityRenderState) s;
		// far crowds of mobs cost nothing; a rig that already exists (held, posed) is kept
		PlayerRig rig = st.distanceToCameraSq > MAX_DISTANCE_SQ ? mgr.existingRig(s.mi$uuid()) : mgr.rig(s.mi$uuid());
		if (rig == null) return;

		// model -> world, relative to where the entity was drawn
		double[] cam = s.mi$camera();
		Matrix4f rel = new Matrix4f().translation((float) (cam[0] - st.x), (float) (cam[1] - st.y), (float) (cam[2] - st.z)).mul(pose);
		boolean world = rel.getTranslation(new Vector3f()).length() < MAX_WORLD_OFFSET;
		if (world) {
			rig.setEntityId(s.mi$entityId());
			rig.getCapturedRel().set(rel);
			rig.setCapturedNanos(now);
		}
		// players wearing a CPM model are handled by CPM's own hook
		if (rig.cpmActive(now)) return;

		Layout layout = LAYOUTS.computeIfAbsent(model, VanillaHooks::layout);
		if (!rig.isFor(model)) rebuild(rig, model, layout);
		rig.markSource(PlayerRig.Source.VANILLA);
		capture(rig, layout);
		if (world) rig.setLastSeenNanos(now);
		if (rig.hasDeformation() && (world || cfg.isDeformInGui())) apply(rig, layout);
	}

	/** The body model was just posed for the layers drawn straight away (held items, heads, capes). */
	public static void onLayersPosed(Model<?> body, MiRenderState s, Matrix4f pose) {
		MiConfig cfg = MiConfig.get();
		if (!cfg.isEnabled() || body == null) return;
		PlayerRig rig = InteractionManager.get().existingRig(s.mi$uuid());
		if (rig == null || !rig.hasDeformation() || rig.cpmActive(System.nanoTime()) || !rig.isFor(body)) return;
		var st = (net.minecraft.client.renderer.entity.state.EntityRenderState) s;
		double[] cam = s.mi$camera();
		Matrix4f rel = new Matrix4f().translation((float) (cam[0] - st.x), (float) (cam[1] - st.y), (float) (cam[2] - st.z)).mul(pose);
		if (rel.getTranslation(new Vector3f()).length() >= MAX_WORLD_OFFSET && !cfg.isDeformInGui()) return;
		Layout l = LAYOUTS.get(body);
		if (l != null) apply(rig, l);
	}

	/**
	 * Armour, sheep wool, saddles and other layers are separate models posed from the same
	 * state. Their parts carry the body model's names, so the same deformation goes onto the
	 * part with the same name path.
	 */
	public static void onLayerPosed(Model<?> layer, MiRenderState s, Matrix4f pose) {
		MiConfig cfg = MiConfig.get();
		if (!cfg.isEnabled() || !cfg.isDeformLayers()) return;
		Model<?> body = s.mi$mainModel();
		if (body == null || body == layer) return;
		PlayerRig rig = InteractionManager.get().existingRig(s.mi$uuid());
		long now = System.nanoTime();
		if (rig == null || !rig.hasDeformation() || rig.cpmActive(now) || !rig.isFor(body)) return;
		var st = (net.minecraft.client.renderer.entity.state.EntityRenderState) s;
		double[] cam = s.mi$camera();
		Matrix4f rel = new Matrix4f().translation((float) (cam[0] - st.x), (float) (cam[1] - st.y), (float) (cam[2] - st.z)).mul(pose);
		boolean world = rel.getTranslation(new Vector3f()).length() < MAX_WORLD_OFFSET;
		if (!world && !cfg.isDeformInGui()) return;
		try {
			Layout bodyLayout = LAYOUTS.get(body);
			if (bodyLayout == null) return;
			Layout l = LAYOUTS.computeIfAbsent(layer, VanillaHooks::layout);
			int[] map = LAYER_MAPS.computeIfAbsent(layer, k -> new WeakHashMap<>()).computeIfAbsent(body, k -> mapByName(l, bodyLayout));
			apply(rig, l, map);
		} catch (RuntimeException e) {
			ModelInteractions.LOG.warn("Failed to deform layer {}", layer.getClass().getName(), e);
			LAYOUTS.remove(layer);
		}
	}

	private static int[] mapByName(Layout layer, Layout body) {
		Map<String, Integer> byPath = new java.util.HashMap<>();
		for (int i = 0; i < body.parts().length; i++) byPath.put(path(body, i), i);
		int[] map = new int[layer.parts().length];
		for (int i = 0; i < map.length; i++) map[i] = byPath.getOrDefault(path(layer, i), -1);
		return map;
	}

	private static String path(Layout l, int i) {
		StringBuilder sb = new StringBuilder();
		for (int p = i; p >= 0; p = l.parents()[p]) sb.insert(0, '/').insert(0, l.names()[p]);
		return sb.toString();
	}

	private static Layout layout(Model<?> model) {
		List<ModelPart> parts = new ArrayList<>();
		List<String> names = new ArrayList<>();
		List<Integer> parents = new ArrayList<>();
		walk(model.root(), "root", -1, parts, names, parents);
		int[] p = new int[parents.size()];
		for (int i = 0; i < p.length; i++) p[i] = parents.get(i);
		return new Layout(parts.toArray(new ModelPart[0]), names.toArray(new String[0]), p);
	}

	private static void walk(ModelPart part, String name, int parent, List<ModelPart> parts, List<String> names, List<Integer> parents) {
		int id = parts.size();
		parts.add(part);
		names.add(name);
		parents.add(parent);
		for (Map.Entry<String, ModelPart> e : ((ModelPartAccessor) (Object) part).mi$children().entrySet()) {
			walk(e.getValue(), e.getKey(), id, parts, names, parents);
		}
	}

	private static void rebuild(PlayerRig rig, Model<?> model, Layout l) {
		Bone[] bones = new Bone[l.parts().length];
		List<Bone> out = new ArrayList<>(bones.length);
		for (int i = 0; i < bones.length; i++) {
			Bone parent = l.parents()[i] < 0 ? null : bones[l.parents()[i]];
			String name = l.names()[i];
			BodyRegion region = parent == null ? BodyRegion.BODY : regionOf(name, parent.getRegion());
			Bone b = new Bone(i, parent, region, parent == null);
			b.setName(name);
			Vector3f min = new Vector3f(Float.MAX_VALUE), max = new Vector3f(-Float.MAX_VALUE);
			for (ModelPart.Cube c : ((ModelPartAccessor) (Object) l.parts()[i]).mi$cubes()) {
				min.min(new Vector3f(c.minX, c.minY, c.minZ));
				max.max(new Vector3f(c.maxX, c.maxY, c.maxZ));
			}
			if (min.x > max.x) min.set(max.zero());
			b.setBox(min.div(16f), max.div(16f));
			bones[i] = b;
			out.add(b);
		}
		String key = model.getClass().getSimpleName() + ":" + bones.length;
		rig.rebuild(model, out, key);
		capture(rig, l);

		InteractionManager mgr = InteractionManager.get();
		Map<String, String> overrides = MiConfig.get().getOverrides().getOrDefault(key, Map.of());
		Map<Integer, PartType> owner = mgr.ownerTypes(rig.getUuid(), key);
		for (Bone b : out) {
			PartType t = parse(overrides.get(Integer.toString(b.getId())));
			if (t == null) t = owner.get(b.getId());
			if (t == null) t = classify(b);
			b.setType(t);
		}
		rig.computeMasses();
		pivot(rig, out);
		rig.refreshProfiles();
		mgr.onRigRebuilt(rig);
	}

	private static PartType parse(String s) {
		if (s == null) return null;
		try {
			return PartType.valueOf(s);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	static BodyRegion regionOf(String name, BodyRegion parent) {
		String n = name.toLowerCase(Locale.ROOT);
		if (parent != BodyRegion.BODY) return parent;
		if (n.equals("head") || n.equals("hat")) return BodyRegion.HEAD;
		if (n.startsWith("left_arm") || n.startsWith("left_sleeve")) return BodyRegion.LEFT_ARM;
		if (n.startsWith("right_arm") || n.startsWith("right_sleeve")) return BodyRegion.RIGHT_ARM;
		if (n.startsWith("left") && n.contains("leg")) return BodyRegion.LEFT_LEG;
		if (n.startsWith("right") && n.contains("leg")) return BodyRegion.RIGHT_LEG;
		return BodyRegion.BODY;
	}

	/** Type from the part name; vanilla names are consistent enough ("left_ear", "tail1", "right_wing_tip"). */
	static PartType classify(Bone b) {
		if (b.isRoot()) return PartType.STATIC;
		String n = b.getName() == null ? "" : b.getName().toLowerCase(Locale.ROOT);
		Bone p = b.getParent();
		PartType pt = p == null ? PartType.STATIC : p.getType();
		// whole words of the name ("left_hind_leg2" -> left, hind, leg), so "beard" is no ear and "armor" no arm
		java.util.Set<String> w = new java.util.HashSet<>(java.util.Arrays.asList(n.replaceAll("[0-9]", "").split("_")));
		// skin overlay layers ride along with their part
		if (w.contains("hat") || w.contains("jacket") || w.contains("sleeve") || w.contains("pants") || w.contains("cloak")) return PartType.STATIC;
		if (w.contains("ear") || w.contains("ears")) return PartType.EAR;
		if (w.contains("tail") || w.contains("tentacle") || w.contains("tentacles")) return PartType.TAIL;
		if (w.contains("wing") || w.contains("wings")) return PartType.WING;
		if (w.contains("hair") || w.contains("mane") || w.contains("fringe")) return PartType.HAIR;
		if (w.contains("leg") || w.contains("legs") || w.contains("arm") || w.contains("arms") || w.contains("foot") || w.contains("paw")
				|| w.contains("fin") || w.contains("flipper") || w.contains("hand")) return PartType.LIMB;
		if (n.equals("head")) return PartType.HEAD;
		if (w.contains("body") || w.contains("torso") || n.equals("cube") || w.contains("chest") || n.equals("bone")) return PartType.STATIC;
		// segments keep the type of their chain (tail1 -> tail2 named "segment")
		if (pt == PartType.TAIL || pt == PartType.HAIR || pt == PartType.EAR || pt == PartType.WING) return pt;
		return b.getMass() <= 0 ? PartType.STATIC : PartType.ACCESSORY;
	}

	/** Humanoids turn about the bottom of the body; anything else about its middle. */
	private static void pivot(PlayerRig rig, List<Bone> bones) {
		Vector3f p = rig.getBodyPivot();
		for (Bone b : bones) {
			if ("body".equals(b.getName()) && b.getMass() > 0) {
				b.model().transformPosition(p.set((b.getBoxMin().x + b.getBoxMax().x) / 2, b.getBoxMax().y, (b.getBoxMin().z + b.getBoxMax().z) / 2));
				b.model().transformPosition(b.center(rig.getBodyCenter()));
				return;
			}
		}
		Vector3f min = new Vector3f(Float.MAX_VALUE), max = new Vector3f(-Float.MAX_VALUE), c = new Vector3f();
		for (Bone b : bones) {
			if (b.getMass() <= 0) continue;
			b.model().transformPosition(b.center(c));
			min.min(c);
			max.max(c);
		}
		if (min.x <= max.x) p.set(min).add(max).mul(0.5f);
		else p.zero();
		rig.getBodyCenter().set(p);
	}

	private static void capture(PlayerRig rig, Layout l) {
		for (Bone b : rig.bones()) {
			if (b.getId() >= l.parts().length) continue;
			ModelPart part = l.parts()[b.getId()];
			Matrix4f local = b.localRest().identity()
					.translate(part.x / 16f, part.y / 16f, part.z / 16f)
					.rotateZYX(part.zRot, part.yRot, part.xRot);
			if (part.xScale != 1 || part.yScale != 1 || part.zScale != 1) {
				local.scale(Math.max(part.xScale, 0.01f), Math.max(part.yScale, 0.01f), Math.max(part.zScale, 0.01f));
			}
			if (b.getParent() == null) b.parentModel().identity();
			else b.parentModel().set(b.getParent().model());
			b.parentModel().mul(local, b.model());
			b.deformed().set(b.model());
			b.setVisible(part.visible && !part.skipDraw && (b.getParent() == null || b.getParent().visible()));
		}
		// the rest pose just changed: the drawn pose (for hits) follows it, with our offsets on top
		rig.updateDeformed();
	}

	private static void apply(PlayerRig rig, Layout l) {
		apply(rig, l, null);
	}

	/** {@code map}: for a layer, body part index of each of its parts; null for the body model itself. */
	private static void apply(PlayerRig rig, Layout l, int[] map) {
		for (int i = 0; i < l.parts().length; i++) {
			int id = map == null ? i : map[i];
			SpringJoint j = id < 0 ? null : rig.joints().get(id);
			if (j == null || j.getOffset().lengthSquared() < 1e-8f) continue;
			ModelPart part = l.parts()[i];
			remember(part);
			Q2.rotationZYX(part.zRot, part.yRot, part.xRot);
			j.rotation(Q).mul(Q2);
			Q.getEulerAnglesZYX(V);
			part.xRot = V.x;
			part.yRot = V.y;
			part.zRot = V.z;
		}
		// the torso turn goes onto the root, which carries every other part
		Matrix4f body = rig.bodyTransform(new Matrix4f());
		if ((body.properties() & Matrix4f.PROPERTY_IDENTITY) == 0 && (map == null || map[0] == 0)) {
			ModelPart root = l.parts()[0];
			remember(root);
			Matrix4f m = new Matrix4f(body).translate(root.x / 16f, root.y / 16f, root.z / 16f)
					.rotateZYX(root.zRot, root.yRot, root.xRot);
			m.getTranslation(V);
			root.x = V.x * 16f;
			root.y = V.y * 16f;
			root.z = V.z * 16f;
			m.getNormalizedRotation(Q).getEulerAnglesZYX(V);
			root.xRot = V.x;
			root.yRot = V.y;
			root.zRot = V.z;
		}
	}

	private static void remember(ModelPart p) {
		for (Object[] o : CHANGED) if (o[0] == p) return;
		CHANGED.add(new Object[] { p, new float[] { p.x, p.y, p.z, p.xRot, p.yRot, p.zRot } });
	}

	/** Shared models must not keep one entity's deformation for the next one drawn. */
	public static void restore() {
		if (CHANGED.isEmpty()) return;
		for (Object[] o : CHANGED) {
			ModelPart p = (ModelPart) o[0];
			float[] v = (float[]) o[1];
			p.x = v[0];
			p.y = v[1];
			p.z = v[2];
			p.xRot = v[3];
			p.yRot = v[4];
			p.zRot = v[5];
		}
		CHANGED.clear();
	}
}
