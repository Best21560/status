package com.orv.window;

import net.minecraft.util.math.Vec3d;

/** Implemented by the Camera mixin so the cut-scene can place the camera. */
public interface CameraAccess {
	void orv$set(Vec3d pos, float yaw, float pitch);
}
