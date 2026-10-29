package dev.deelter.modelinteractions.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.deelter.modelinteractions.interaction.CarryClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Swinging to throw something must not hit it: no attack on what we hold or just let go of. */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
	@Inject(method = "attack", at = @At("HEAD"), cancellable = true)
	private void mi$noHitWhileHolding(Player player, Entity target, CallbackInfo ci) {
		if (target != null && CarryClient.protects(target.getUUID())) ci.cancel();
	}
}
