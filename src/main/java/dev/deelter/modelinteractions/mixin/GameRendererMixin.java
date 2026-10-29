package dev.deelter.modelinteractions.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.deelter.modelinteractions.ModelInteractionsClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;

/**
 * Drives physics once per rendered frame, before any player is drawn.
 * The signature of render differs between versions (26.2 takes a DeltaTracker, 26.3 none),
 * so the handler takes no method arguments and reads the tracker from Minecraft.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void mi$frame(CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		ModelInteractionsClient.onFrame(mc, mc.getDeltaTracker().getGameTimeDeltaPartialTick(true));
	}
}
