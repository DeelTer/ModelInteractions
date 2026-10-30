package dev.deelter.modelinteractions.mixin.cpm;

import com.tom.cpm.shared.animation.AnimationState;
import com.tom.cpm.shared.config.Player;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.model.render.ModelRenderManager;
import dev.deelter.modelinteractions.cpm.CpmHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * CPM resets and re-animates every element in bindFirstSetup, right before the parts are
 * drawn. Adding our offsets at its tail layers them on top of CPM animations for exactly
 * one render pass.
 */
@Mixin(value = ModelRenderManager.RedirectHolder.class, remap = false)
public abstract class RedirectHolderMixin {
	@Shadow
	public ModelDefinition def;
	@Shadow
	public Player<?> playerObj;
	@Shadow
	private AnimationState animState;

	@Inject(method = "bindFirstSetup", at = @At("TAIL"))
	private void mi$afterAnimate(boolean isPoseSetup, CallbackInfo ci) {
		if (playerObj != null && def != null) CpmHooks.onPoseReady(def, playerObj, animState);
	}
}
