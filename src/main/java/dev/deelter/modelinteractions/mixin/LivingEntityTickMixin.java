package dev.deelter.modelinteractions.mixin;

import dev.deelter.modelinteractions.debug.DummyManager;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A following dummy steps inside its own tick, so the game sees it walk and moves its legs.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityTickMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void mi$beforeTick(CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.level() instanceof net.minecraft.client.multiplayer.ClientLevel) DummyManager.beforeTick(self);
	}
}
