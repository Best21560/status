package com.orv.window;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * The Berserk cut-scene as an anime-style sequence of camera cuts (see the shot list in SHOTS):
 * face close-up, fists, low wide shot with speed lines, white cut, screaming close-up, orbit while the fire erupts,
 * hero pose. The camera is placed by a mixin (CameraMixin) and the field of view by GameRendererMixin.
 */
public final class CutsceneCam {
	private CutsceneCam() {
	}

	public static final float TOTAL = 5.2f;
	/** Cut times (seconds): the start of each shot. */
	private static final float[] CUTS = {0f, 0.9f, 1.8f, 2.6f, 2.7f, 3.7f, 4.5f};

	private static long t0 = 0;
	private static double fov = -1;

	public static void start() {
		t0 = System.nanoTime();
	}

	public static void stop() {
		t0 = 0;
		fov = -1;
	}

	public static float time() {
		return t0 == 0 ? -1f : (System.nanoTime() - t0) / 1_000_000_000f;
	}

	public static boolean active() {
		float t = time();
		return t >= 0f && t < TOTAL;
	}

	public static int shot(float t) {
		int s = 0;
		for (int i = 0; i < CUTS.length; i++) {
			if (t >= CUTS[i]) {
				s = i;
			}
		}
		return s;
	}

	/** 1 right at a cut, fading to 0 within a short moment: used for the white "frame flash" of every cut. */
	public static float cutFlash(float t) {
		float best = 0f;
		for (int i = 1; i < CUTS.length; i++) {
			float d = t - CUTS[i];
			if (d >= 0f && d < 0.14f) {
				best = Math.max(best, 1f - d / 0.14f);
			}
		}
		return best;
	}

	private static float lerp(float a, float b, float k) {
		return a + (b - a) * k;
	}

	/** Called by the camera mixin after the normal camera was set up. */
	public static void apply(Camera cam, Entity focus, float td) {
		float t = time();
		if (t < 0f || t >= TOTAL || !(focus instanceof net.minecraft.entity.player.PlayerEntity)) {
			fov = -1;
			return;
		}
		int s = shot(t);
		float s0 = CUTS[s];
		float s1 = s + 1 < CUTS.length ? CUTS[s + 1] : TOTAL;
		float k = Math.max(0f, Math.min(1f, (t - s0) / (s1 - s0)));

		Vec3d p = focus.getLerpedPos(td);
		double yaw = Math.toRadians(MathHelper.lerpAngleDegrees(td, ((net.minecraft.entity.LivingEntity) focus).prevBodyYaw,
				((net.minecraft.entity.LivingEntity) focus).bodyYaw));
		Vec3d f = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw)); // where the body faces
		Vec3d r = new Vec3d(-f.z, 0, f.x);

		double dist;
		double side;
		double h;
		double ty;
		float fv;
		double orbit = 0;
		switch (s) {
			case 0 -> { // face close-up, slow push in
				dist = lerp(1.25f, 0.85f, k);
				side = lerp(0.0f, 0.06f, k);
				h = 1.68;
				ty = 1.66;
				fv = 36f;
			}
			case 1 -> { // fists / chest
				dist = lerp(2.1f, 1.6f, k);
				side = lerp(0.7f, 0.4f, k);
				h = lerp(0.9f, 1.15f, k);
				ty = 1.1;
				fv = 52f;
			}
			case 2 -> { // low wide shot looking up
				dist = lerp(4.4f, 3.3f, k);
				side = -1.1;
				h = lerp(0.1f, 0.35f, k);
				ty = 1.55;
				fv = lerp(78f, 66f, k);
			}
			case 3 -> { // the cut: a short blink of the previous shot, pushed in hard
				dist = lerp(3.3f, 2.4f, k);
				side = -1.1;
				h = 0.35;
				ty = 1.6;
				fv = 60f;
			}
			case 4 -> { // screaming face
				dist = lerp(1.05f, 0.72f, k);
				side = lerp(0.12f, -0.05f, k);
				h = 1.7;
				ty = 1.66;
				fv = lerp(40f, 33f, k);
			}
			case 5 -> { // orbit while the fire erupts
				dist = lerp(5.2f, 7.2f, k);
				side = 0;
				h = lerp(1.0f, 3.2f, k);
				ty = 1.5;
				fv = 72f;
				orbit = lerp(0f, 1.9f, k);
			}
			default -> { // hero pose: low angle, slow push
				dist = lerp(4.0f, 3.2f, k);
				side = 0.4;
				h = lerp(0.25f, 0.5f, k);
				ty = lerp(1.55f, 1.75f, k);
				fv = 62f;
			}
		}
		// camera position in the body's frame, rotated by the orbit angle
		double cx = f.x * dist + r.x * side;
		double cz = f.z * dist + r.z * side;
		double ca = Math.cos(orbit);
		double sa = Math.sin(orbit);
		double ox = cx * ca - cz * sa;
		double oz = cx * sa + cz * ca;
		Vec3d pos = p.add(ox, h, oz);
		Vec3d target = p.add(0, ty, 0);

		// shaking: gentle breathing in the close-ups, hard in the scream
		double sh = s == 4 ? 0.035 : (s == 5 ? 0.02 : 0.006);
		double tt = t * 31.0;
		pos = pos.add(Math.sin(tt) * sh, Math.cos(tt * 1.3) * sh, Math.sin(tt * 0.7 + 1) * sh);

		Vec3d d = target.subtract(pos);
		double len = d.length();
		if (len < 1e-4) {
			return;
		}
		float yw = (float) (Math.toDegrees(Math.atan2(-d.x, d.z)));
		float pt = (float) (-Math.toDegrees(Math.asin(d.y / len)));
		if (cam instanceof CameraAccess acc) {
			acc.orv$set(pos, yw, pt);
			fov = fv;
		}
	}

	/** Field of view override (degrees) or -1. */
	public static double fov() {
		return active() ? fov : -1;
	}

	/** Screen effects of the shots: horizontal speed lines on the wide shots and a pulsing dark red edge on the close-ups. */
	public static void hud(net.minecraft.client.gui.DrawContext ctx, int w, int h) {
		float t = time();
		if (t < 0f || t >= TOTAL) {
			return;
		}
		int s = shot(t);
		if (s == 2 || s == 3 || s == 5) {
			double now = System.nanoTime() / 1_000_000_000.0;
			int n = 26;
			for (int i = 0; i < n; i++) {
				float a = Fx.rnd(900 + (long) Math.floor(now * 14.0), i);
				float b = Fx.rnd(901 + (long) Math.floor(now * 14.0), i);
				float c = Fx.rnd(902 + (long) Math.floor(now * 14.0), i);
				int y = (int) (a * h);
				int len = (int) (w * (0.25f + 0.5f * b));
				int x = (int) (c * (w + len)) - len;
				// keep the middle of the screen a little clearer
				float mid = 1f - Math.abs(y / (float) h - 0.5f) * 2f;
				int al = (int) (70 + 80 * (1f - mid));
				ctx.fill(x, y, x + len, y + 1 + (i % 3 == 0 ? 1 : 0), (al << 24) | 0xFFFFFF);
			}
		}
		if (s == 0 || s == 4) {
			Fx.vignettePublic(ctx, w, h, 0x000000, 0.55f);
			Fx.vignettePublic(ctx, w, h, 0xC00800, 0.25f + 0.1f * (float) Math.sin(t * 16.0));
		}
		float fl = cutFlash(t);
		if (fl > 0f) {
			ctx.fill(0, 0, w, h, Fx.argb(s == 4 ? 0xFF4030 : 0xFFFFFF, 0.55f * fl));
		}
	}
}
