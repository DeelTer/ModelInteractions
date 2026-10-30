package dev.deelter.modelinteractions.mixin;

import dev.deelter.modelinteractions.input.PcInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * While the mouse holds a part, the camera turns slower and the held point takes the rest of the turn.
 */
@Mixin(Entity.class)
public abstract class EntityTurnMixin {
	@ModifyVariable(method = "turn", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private double mi$yaw(double yaw) {
		return (Object) this instanceof LocalPlayer ? PcInput.onTurn(yaw, true) : yaw;
	}

	@ModifyVariable(method = "turn", at = @At("HEAD"), argsOnly = true, ordinal = 1)
	private double mi$pitch(double pitch) {
		return (Object) this instanceof LocalPlayer ? PcInput.onTurn(pitch, false) : pitch;
	}
}
