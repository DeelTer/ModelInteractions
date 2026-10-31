package dev.deelter.modelinteractions.mixin;

import dev.deelter.modelinteractions.input.PcInput;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shift pressed to keep a shape must not make the player sneak.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void mi$hideSneak(CallbackInfo ci) {
		if (!PcInput.hidesSneak()) return;
		ClientInput self = (ClientInput) (Object) this;
		Input k = self.keyPresses;
		self.keyPresses = new Input(k.forward(), k.backward(), k.left(), k.right(), k.jump(), false, k.sprint());
	}
}
