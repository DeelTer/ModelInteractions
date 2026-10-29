package dev.deelter.modelinteractions.toy;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.joml.Vector3f;

import dev.deelter.modelinteractions.config.MiConfig;
import dev.deelter.modelinteractions.interaction.PlayerRig;
import dev.deelter.modelinteractions.model.BodyRegion;
import dev.deelter.modelinteractions.model.Bone;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * Where on your own model a touch reaches the device: by default the space between the tops
 * of the legs, plus any parts you marked yourself. Only touches by players on your list count.
 */
public final class ToyZones {
	/** Half size of the default zone, blocks (about 3 x 4 x 3 px). */
	private static final Vector3f HALF = new Vector3f(1.6f, 2f, 1.8f).div(16f);

	private ToyZones() {}

	/** A touch on our own model by {@code actor}, at a point in model space; strength 0..1. */
	public static void touched(PlayerRig rig, Bone bone, Vector3f contactModel, UUID actor, float strength) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !rig.getUuid().equals(mc.player.getUUID()) || !ToyBridge.active()) return;
		if (!mayTrigger(actor)) return;
		if (inZone(rig, bone, contactModel)) ToyBridge.touch(strength);
	}

	static boolean mayTrigger(UUID actor) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && actor.equals(mc.player.getUUID())) return true;
		Player p = mc.level == null ? null : mc.level.getPlayerByUUID(actor);
		if (p == null) return false;
		String name = p.getName().getString().toLowerCase(Locale.ROOT);
		return MiConfig.get().getToy().getAllowed().stream().anyMatch(n -> n.trim().equalsIgnoreCase(name));
	}

	public static boolean inZone(PlayerRig rig, Bone bone, Vector3f contactModel) {
		MiConfig.Toy cfg = MiConfig.get().getToy();
		List<Integer> own = cfg.getZones().get(rig.getModelKey());
		if (bone != null && own != null && own.contains(bone.getId())) return true;
		if (!cfg.isDefaultZone()) return false;
		Vector3f c = defaultZone(rig);
		return c != null && Math.abs(contactModel.x - c.x) <= HALF.x && Math.abs(contactModel.y - c.y) <= HALF.y
				&& Math.abs(contactModel.z - c.z) <= HALF.z;
	}

	/** Between the tops of the two legs, a little below where they join the body. Model space. */
	public static Vector3f defaultZone(PlayerRig rig) {
		Vector3f l = null, r = null;
		for (Bone b : rig.bones()) {
			boolean top = b.isRoot() || b.getParent() != null && b.getParent().getRegion() == BodyRegion.BODY;
			if (!top) continue;
			if (b.getRegion() == BodyRegion.LEFT_LEG && l == null) l = b.model().getTranslation(new Vector3f());
			if (b.getRegion() == BodyRegion.RIGHT_LEG && r == null) r = b.model().getTranslation(new Vector3f());
		}
		if (l == null || r == null) return null;
		// model y points down: +1.5px is a little lower than the leg pivots
		return l.add(r).mul(0.5f).add(0, 1.5f / 16f, 0);
	}
}
