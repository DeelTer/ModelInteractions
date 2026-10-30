package dev.deelter.modelinteractions.mixin;

import dev.deelter.modelinteractions.input.PcInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The wheel pulls or pushes a held part instead of switching slots.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void mi$scroll(long window, double x, double y, CallbackInfo ci) {
		if (Minecraft.getInstance().gui.screen() == null && PcInput.onScroll(y)) ci.cancel();
	}
}
