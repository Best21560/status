package com.orv.window;

import net.minecraft.util.math.Vec3d;

/** Shared geometry of the Lightbender orbs (used by the server for contact checks and by the client to draw them). */
public final class LightGeo {
	private LightGeo() {
	}

	/** Orb i (0..7) circles the caster: two rings of four at different heights, turning in opposite directions. t is in seconds. */
	public static Vec3d orbPos(Vec3d base, float yawDeg, int i, double t) {
		int row = i / 4;
		int col = i % 4;
		double dir = row == 0 ? 1.0 : -1.0;
		double a = t * 1.1 * dir + col * Math.PI / 2.0 + row * Math.PI / 4.0;
		double rad = row == 0 ? 2.1 : 1.7;
		double y = (row == 0 ? 1.7 : 1.0) + 0.1 * Math.sin(t * 2.0 + i);
		return new Vec3d(base.x + Math.cos(a) * rad, base.y + y, base.z + Math.sin(a) * rad);
	}

	/** The four free-flying orbs of the Seraph passive: a wide circle (radius 10) around the caster. */
	public static Vec3d extraPos(Vec3d base, int k, double t) {
		double a = t * 1.1 + k * Math.PI / 2.0;
		return new Vec3d(base.x + Math.cos(a) * 10.0, base.y + 1.4 + 0.4 * Math.sin(t * 2.0 + k), base.z + Math.sin(a) * 10.0);
	}

	/** Position of ring light j of n on the flat circle around the caster. */
	public static Vec3d ringPos(Vec3d base, int j, int n, double t) {
		double a = t * 1.5 + j * Math.PI * 2.0 / Math.max(1, n);
		return new Vec3d(base.x + Math.cos(a) * 2.4, base.y + 0.9, base.z + Math.sin(a) * 2.4);
	}
}
