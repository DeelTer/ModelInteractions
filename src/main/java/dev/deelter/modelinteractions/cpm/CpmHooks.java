package dev.deelter.modelinteractions.cpm;

import com.tom.cpl.math.Rotation;
import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.animation.AnimationEngine.AnimationMode;
import com.tom.cpm.shared.animation.AnimationState;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.definition.ModelDefinition.ModelLoadingState;
import com.tom.cpm.shared.model.*;
import dev.deelter.modelinteractions.ModelInteractions;
import dev.deelter.modelinteractions.config.MiConfig;
import dev.deelter.modelinteractions.interaction.InteractionManager;
import dev.deelter.modelinteractions.interaction.PlayerRig;
import dev.deelter.modelinteractions.mixin.cpm.ModelDefinitionAccessor;
import dev.deelter.modelinteractions.model.BodyRegion;
import dev.deelter.modelinteractions.model.Bone;
import dev.deelter.modelinteractions.model.PartClassifier;
import dev.deelter.modelinteractions.physics.PartType;
import dev.deelter.modelinteractions.physics.SpringJoint;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bridges CPM's render pipeline and the interaction rigs. Render thread only.
 */
public final class CpmHooks {
	private static final Quaternionf Q = new Quaternionf(), Q2 = new Quaternionf();
	private static final Vector3f V = new Vector3f();

	private CpmHooks() {
	}

	public static void onPoseReady(ModelDefinition def, Player<?> player, AnimationState state) {
		if (!MiConfig.get().isEnabled()) return;
		if (def.getResolveState() != ModelLoadingState.LOADED) return;
		try {
			PlayerRig rig = InteractionManager.get().rig(player.getUUID());
			if (!rig.isFor(def)) rebuild(rig, def);

			boolean worldPass = state != null && state.animationMode == AnimationMode.PLAYER && !state.inGui && !state.firstPersonMod;
			if (worldPass) {
				capture(rig, def);
				rig.setLastSeenNanos(System.nanoTime());
				rig.markSource(PlayerRig.Source.CPM);
			}
			if (rig.hasDeformation() && (worldPass || MiConfig.get().isDeformInGui())) apply(rig, def);
		} catch (RuntimeException e) {
			ModelInteractions.LOG.warn("Failed to apply interactions for {}", player.getUUID(), e);
		}
	}

	private static void rebuild(PlayerRig rig, ModelDefinition def) {
		List<RenderedCube> cubes = ((ModelDefinitionAccessor) def).mi$getCubes();
		List<Bone> out = new ArrayList<>();
		StringBuilder key = new StringBuilder();
		boolean[] seen = new boolean[PlayerModelParts.VALUES.length];
		for (RenderedCube c : cubes) {
			if (!(c instanceof RootModelElement root) || !(root.getPart() instanceof PlayerModelParts p)) continue;
			// only the main root of each vanilla part; CPM may create duplicated roots
			if (p == PlayerModelParts.CUSTOM_PART || seen[p.ordinal()]) continue;
			seen[p.ordinal()] = true;
			Bone b = new Bone(root.getId(), null, BodyRegion.fromCpmName(p.getName()), true);
			PartValues pv = p.getDefaultSize(def.getSkinType());
			Vec3f o = pv.getOffset(), s = pv.getSize();
			b.setBox(new Vector3f(o.x, o.y, o.z).div(16f), new Vector3f(o.x + s.x, o.y + s.y, o.z + s.z).div(16f));
			out.add(b);
			walk(root, b, out, key);
		}
		rig.rebuild(def, out, Integer.toHexString(key.toString().hashCode()));
		// classification needs rest frames
		capture(rig, def);
		// precedence: your local fixes > types the owner picked > geometry guess
		InteractionManager mgr = InteractionManager.get();
		Map<String, String> overrides = MiConfig.get().getOverrides().getOrDefault(rig.getModelKey(), Map.of());
		Map<Integer, PartType> owner = mgr.ownerTypes(rig.getUuid(), rig.getModelKey());
		for (Bone b : out) {
			PartType t = parse(overrides.get(Integer.toString(b.getId())));
			if (t == null) t = owner.get(b.getId());
			if (t == null) {
				Bone root = b;
				while (root.getParent() != null) root = root.getParent();
				Matrix4f rootToBone = new Matrix4f(root.model()).invert().mul(b.model());
				t = PartClassifier.classify(b, rootToBone);
			}
			b.setType(t);
		}
		rig.computeMasses();
		// torso turns about the bottom of the vanilla body box
		for (Bone b : out) {
			if (b.isRoot() && b.getRegion() == BodyRegion.BODY) {
				b.model().transformPosition(rig.getBodyPivot().set((b.getBoxMin().x + b.getBoxMax().x) / 2, b.getBoxMax().y,
						(b.getBoxMin().z + b.getBoxMax().z) / 2));
				b.model().transformPosition(b.center(rig.getBodyCenter()));
				break;
			}
		}
		rig.refreshProfiles();
		mgr.onRigRebuilt(rig);
	}

	static PartType parse(String name) {
		if (name == null) return null;
		try {
			return PartType.valueOf(name);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	static void walk(RenderedCube cube, Bone bone, List<Bone> out, StringBuilder key) {
		if (cube.children == null) return;
		for (RenderedCube c : cube.children) {
			Cube cc = c.getCube();
			if (cc == null) continue;
			Bone b = new Bone(c.getId(), bone, bone.getRegion(), false);
			Vector3f min = new Vector3f(cc.offset.x, cc.offset.y, cc.offset.z);
			Vector3f max = new Vector3f(cc.size.x, cc.size.y, cc.size.z);
			if (cc.meshScale != null) max.mul(cc.meshScale.x, cc.meshScale.y, cc.meshScale.z);
			max.add(min);
			if (c.itemRenderer != null) max.set(min); // item slots are not physical
			b.setBox(min.div(16f), max.div(16f));
			key.append(c.getId()).append(':').append(Math.round(cc.size.x)).append(',')
					.append(Math.round(cc.size.y)).append(',').append(Math.round(cc.size.z)).append(';');
			out.add(b);
			walk(c, b, out, key);
		}
	}

	/**
	 * Records model-space rest frames of all bones for this frame. Bones are parent-first.
	 */
	private static void capture(PlayerRig rig, ModelDefinition def) {
		for (Bone b : rig.bones()) {
			RenderedCube c = def.getElementById(b.getId());
			if (c == null) continue;
			Vec3f p;
			Rotation r;
			Vec3f s = null;
			if (b.isRoot()) {
				p = c.getTransformPosition();
				r = c.getTransformRotation();
			} else {
				p = c.pos;
				r = c.rotation;
				s = c.renderScale;
			}
			setRest(b, p, r, s);
			b.setVisible(b.isRoot() || (c.display && b.getParent().visible()));
		}
		// the rest pose just changed: the drawn pose (for hits) follows it, with our offsets on top
		rig.updateDeformed();
	}

	/**
	 * Sets the bone's local rest transform and derives its model frame from the parent's.
	 */
	static void setRest(Bone b, Vec3f p, Rotation r, Vec3f s) {
		Matrix4f local = b.localRest().identity()
				.translate(p.x / 16f, p.y / 16f, p.z / 16f)
				.rotateZYX(r.z, r.y, r.x);
		if (s != null && (s.x != 1 || s.y != 1 || s.z != 1))
			local.scale(Math.max(s.x, 0.01f), Math.max(s.y, 0.01f), Math.max(s.z, 0.01f));
		if (b.getParent() == null) b.parentModel().identity();
		else b.parentModel().set(b.getParent().model());
		b.parentModel().mul(local, b.model());
		b.deformed().set(b.model());
	}

	/**
	 * Writes spring offsets into the CPM elements as extra rotation, and the torso turn into the roots.
	 */
	private static void apply(PlayerRig rig, ModelDefinition def) {
		Matrix4f body = rig.bodyTransform(new Matrix4f());
		boolean turned = (body.properties() & Matrix4f.PROPERTY_IDENTITY) == 0;
		for (Bone b : rig.bones()) {
			SpringJoint j = rig.joints().get(b.getId());
			boolean bend = j != null && j.getOffset().lengthSquared() >= 1e-8f;
			if (!bend && !(turned && b.isRoot())) continue;
			RenderedCube c = def.getElementById(b.getId());
			if (c == null) continue;
			boolean root = c instanceof RootModelElement;
			Rotation cur = root ? c.getTransformRotation() : c.rotation;
			Q2.rotationZYX(cur.z, cur.y, cur.x);
			if (bend) j.rotation(Q).mul(Q2);
			else Q.set(Q2);
			if (turned && b.isRoot()) {
				// root part about the torso pivot: R_body * (pos, rot)
				Vec3f p = c.getTransformPosition();
				Vector3f np = body.transformPosition(new Vector3f(p.x, p.y, p.z).div(16f)).mul(16f);
				c.setPosition(true, np.x - p.x, np.y - p.y, np.z - p.z);
				body.getNormalizedRotation(Q2).mul(Q, Q);
			}
			Q.getEulerAnglesZYX(V);
			c.setRotation(true, wrap(V.x - cur.x), wrap(V.y - cur.y), wrap(V.z - cur.z));
		}
	}

	private static float wrap(float a) {
		final float pi = (float) Math.PI;
		while (a > pi) a -= 2 * pi;
		while (a < -pi) a += 2 * pi;
		return a;
	}
}
