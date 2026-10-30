package dev.deelter.modelinteractions.debug;

import dev.deelter.modelinteractions.interaction.InteractionManager;
import dev.deelter.modelinteractions.interaction.PlayerRig;
import dev.deelter.modelinteractions.model.Bone;
import dev.deelter.modelinteractions.physics.PartType;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Debug view: outlines every interactive bone with dust particles colored by part type,
 * using the same model->world transform the hit tests use. If the dots don't sit on the
 * model, grabbing will miss too.
 */
public final class PartVisualizer {
	@Getter
	@Setter
	private static boolean enabled;
	private static int ticks;

	private PartVisualizer() {
	}

	public static int color(PartType t) {
		return switch (t) {
			case EAR -> 0xFF4FA0;
			case TAIL -> 0xFF9A1F;
			case HAIR -> 0xB46BFF;
			case WING -> 0x3FD0FF;
			case JELLY -> 0xFF5555;
			case LIMB -> 0x5BE35B;
			case HEAD -> 0xFFFF55;
			case ACCESSORY -> 0xFFFFFF;
			case STATIC -> 0x606060;
		};
	}

	public static void tick(Minecraft mc) {
		if (!enabled || mc.level == null || ++ticks % 4 != 0) return;
		ClientLevel level = mc.level;
		Matrix4f m = new Matrix4f();
		Vector3f p = new Vector3f();
		for (PlayerRig rig : InteractionManager.get().rigs()) {
			if (!rig.isWorldValid()) continue;
			for (Bone b : rig.bones()) {
				if (!b.isInteractive() || b.getType() == PartType.STATIC) continue;
				DustParticleOptions dust = new DustParticleOptions(color(b.getType()), 0.35f);
				rig.getModelToWorld().mul(b.deformed(), m);
				Vector3f lo = b.getBoxMin(), hi = b.getBoxMax();
				for (int i = 0; i < 8; i++) {
					p.set((i & 1) == 0 ? lo.x : hi.x, (i & 2) == 0 ? lo.y : hi.y, (i & 4) == 0 ? lo.z : hi.z);
					m.transformPosition(p);
					level.addParticle(dust, p.x, p.y, p.z, 0, 0, 0);
				}
			}
		}
	}
}
