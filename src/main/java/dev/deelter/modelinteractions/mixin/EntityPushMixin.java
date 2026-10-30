package dev.deelter.modelinteractions.mixin;

import dev.deelter.modelinteractions.interaction.CarryClient;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Something held up or dragged doesn't shove its holder away, nor the holder it.
 */
@Mixin(Entity.class)
public abstract class EntityPushMixin {
	@Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
	private void mi$noPushWhileHeld(Entity other, CallbackInfo ci) {
		if (CarryClient.noPush((Entity) (Object) this, other)) ci.cancel();
	}
}
