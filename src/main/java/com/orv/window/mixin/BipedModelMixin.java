package com.orv.window.mixin;

import com.orv.window.PoseClient;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets the Slayer skill animations (PoseClient) bend the arms, body and head of the player model. */
@Mixin(BipedEntityModel.class)
public abstract class BipedModelMixin {
	@Inject(method = "setAngles(Lnet/minecraft/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
	private void orv$pose(LivingEntity entity, float limbAngle, float limbDistance, float animationProgress,
						  float headYaw, float headPitch, CallbackInfo ci) {
		PoseClient.apply((BipedEntityModel<?>) (Object) this, entity);
	}
}
