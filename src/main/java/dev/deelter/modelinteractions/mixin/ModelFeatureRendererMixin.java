package dev.deelter.modelinteractions.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.deelter.modelinteractions.vanilla.MiRenderState;
import dev.deelter.modelinteractions.vanilla.VanillaHooks;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;

/**
 * Entity models are posed right before they are drawn, from a deferred submit. Between the
 * pose and the draw is where deformations go in; after the draw the shared model is put back.
 */
@Mixin(ModelFeatureRenderer.class)
public abstract class ModelFeatureRendererMixin {
	@Inject(method = "prepareModel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;setupAnim(Ljava/lang/Object;)V", shift = At.Shift.AFTER))
	private void mi$posed(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
		if (submit.state() instanceof MiRenderState s && s.mi$uuid() != null) {
			if (submit.model() == s.mi$mainModel()) VanillaHooks.onPosed(submit.model(), s, submit.pose().pose());
			else VanillaHooks.onLayerPosed(submit.model(), s, submit.pose().pose());
		}
	}

	@Inject(method = "prepareModel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V", shift = At.Shift.AFTER))
	private void mi$drawn(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
		VanillaHooks.restore();
	}
}
