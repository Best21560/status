package com.orv.window.mixin;

import com.orv.window.PoseClient;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.RotationAxis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Spins the whole body of a Slayer in the middle of the jump slam (the camera keeps looking the same way). */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingRendererMixin {
	@Inject(method = "setupTransforms(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/util/math/MatrixStack;FFF)V", at = @At("TAIL"))
	private void orv$spin(LivingEntity entity, MatrixStack matrices, float animationProgress, float bodyYaw, float tickDelta,
						  CallbackInfo ci) {
		float y = PoseClient.rootYaw(entity);
		if (y != 0f) {
			matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(y));
		}
	}
}
