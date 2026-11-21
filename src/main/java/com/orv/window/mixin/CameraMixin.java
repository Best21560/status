package com.orv.window.mixin;

import com.orv.window.CameraAccess;
import com.orv.window.CutsceneCam;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets the Berserk cut-scene place the camera (see CutsceneCam). */
@Mixin(Camera.class)
public abstract class CameraMixin implements CameraAccess {
	@Shadow
	protected abstract void setPos(double x, double y, double z);

	@Shadow
	protected abstract void setRotation(float yaw, float pitch);

	@Inject(method = "update", at = @At("TAIL"))
	private void orv$shots(BlockView area, Entity focused, boolean thirdPerson, boolean inverseView, float tickDelta, CallbackInfo ci) {
		if (CutsceneCam.active()) {
			CutsceneCam.apply((Camera) (Object) this, focused, tickDelta);
		}
	}

	@Override
	public void orv$set(Vec3d pos, float yaw, float pitch) {
		setPos(pos.x, pos.y, pos.z);
		setRotation(yaw, pitch);
	}
}
