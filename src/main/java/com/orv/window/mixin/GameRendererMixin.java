package com.orv.window.mixin;

import com.orv.window.CutsceneCam;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Field of view of the cut-scene shots (tight for close-ups, wide for the low shots). */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
	private void orv$fov(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Double> cir) {
		double f = CutsceneCam.fov();
		if (f > 0) {
			cir.setReturnValue(f);
		}
	}
}
