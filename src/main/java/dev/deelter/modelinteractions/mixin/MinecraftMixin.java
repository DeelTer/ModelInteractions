package dev.deelter.modelinteractions.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.deelter.modelinteractions.input.PcInput;
import net.minecraft.client.Minecraft;

/** Right mouse on a model part grabs or pets it; an empty-handed hit knocks it and digs nothing behind it. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void mi$punch(CallbackInfoReturnable<Boolean> cir) {
		// the punch landed on a model: don't also start digging the block behind it
		if (PcInput.onAttack((Minecraft) (Object) this)) cir.setReturnValue(false);
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void mi$noDigThroughModels(boolean down, CallbackInfo ci) {
		if (down && PcInput.modelInFrontOfBlock((Minecraft) (Object) this)) ci.cancel();
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void mi$useOnPart(CallbackInfo ci) {
		if (PcInput.onUseKey((Minecraft) (Object) this)) ci.cancel();
	}
}
