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

	/** Script 0 = Berserk (5.2 s), script 1 = Relentless Pursuit (3.8 s). */
	private static final float[][] CUT_TABLE = {
			{0f, 0.9f, 1.8f, 2.6f, 2.7f, 3.7f, 4.5f},
			{0f, 0.5f, 0.85f, 1.1f, 1.35f, 1.6f, 1.9f, 2.25f, 3.0f}
	};
	private static final float[] TOTALS = {5.2f, 3.8f};
	private static int script = 0;
	private static int targetId = -1;

	private static long t0 = 0;
	private static double fov = -1;
	private static boolean logged = false;

	public static void start(int sc, int target) {
		script = Math.max(0, Math.min(1, sc));
		targetId = target;
		logged = false;
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
		return t >= 0f && t < TOTALS[script];
	}

	public static int shot(float t) {
		int s = 0;
		float[] cuts = CUT_TABLE[script];
		for (int i = 0; i < cuts.length; i++) {
			if (t >= cuts[i]) {
				s = i;
			}
		}
		return s;
	}

	/** 1 right at a cut, fading to 0 within a short moment: used for the white "frame flash" of every cut. */
	public static float cutFlash(float t) {
		float best = 0f;
		float[] cuts = CUT_TABLE[script];
		for (int i = 1; i < cuts.length; i++) {
			float d = t - cuts[i];
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
		if (t < 0f || t >= TOTALS[script] || !(focus instanceof net.minecraft.entity.player.PlayerEntity)) {
			fov = -1;
			return;
		}
		int s = shot(t);
		float[] cuts = CUT_TABLE[script];
		float s0 = cuts[s];
		float s1 = s + 1 < cuts.length ? cuts[s + 1] : TOTALS[script];
		float k = Math.max(0f, Math.min(1f, (t - s0) / (s1 - s0)));

		Vec3d p = focus.getLerpedPos(td);
		double yaw = Math.toRadians(MathHelper.lerpAngleDegrees(td, ((net.minecraft.entity.LivingEntity) focus).prevBodyYaw,
				((net.minecraft.entity.LivingEntity) focus).bodyYaw));
		Vec3d f = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw)); // where the body faces
		Vec3d r = new Vec3d(-f.z, 0, f.x);

		if (script == 1) {
			applyPursuit(cam, p, f, s, k, t, td);
			return;
		}
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
		if (!logged) {
			logged = true;
			org.slf4j.LoggerFactory.getLogger("orvwindow").info("[orvwindow] cut-scene camera is active (shot {})", s);
		}
		if (cam instanceof CameraAccess acc) {
			acc.orv$set(pos, yw, pt);
			fov = fv;
		}
	}

	private static Vec3d rot(Vec3d v, double ang) {
		double c = Math.cos(ang);
		double sn = Math.sin(ang);
		return new Vec3d(v.x * c - v.z * sn, 0, v.x * sn + v.z * c);
	}

	/** Relentless Pursuit: camera cuts around the Slayer (P) and the target (Q). */
	private static void applyPursuit(Camera cam, Vec3d p, Vec3d f, int s, float k, float t, float td) {
		Vec3d q = p.add(f.multiply(3.0));
		net.minecraft.client.world.ClientWorld w = MinecraftClient.getInstance().world;
		if (w != null && targetId >= 0) {
			Entity te = w.getEntityById(targetId);
			if (te != null) {
				q = te.getLerpedPos(td);
			}
		}
		Vec3d a = new Vec3d(q.x - p.x, 0, q.z - p.z);
		a = a.lengthSquared() < 1e-4 ? f : a.normalize(); // from the Slayer to the target
		Vec3d side = new Vec3d(-a.z, 0, a.x);
		Vec3d m = new Vec3d((p.x + q.x) / 2, Math.min(p.y, q.y), (p.z + q.z) / 2);
		Vec3d focus;
		Vec3d fwd;
		double dist;
		double h;
		double ty;
		float fv;
		switch (s) {
			case 0 -> { // face close-up
				focus = p;
				fwd = a;
				dist = lerp(1.25f, 0.9f, k);
				h = 1.68;
				ty = 1.66;
				fv = 36f;
			}
			case 1 -> { // wide low side shot
				focus = m;
				fwd = side;
				dist = lerp(7.5f, 6.0f, k);
				h = 0.3;
				ty = 1.3;
				fv = 76f;
			}
			case 2 -> {
				focus = m;
				fwd = rot(a, 1.0);
				dist = 3.2;
				h = 1.0;
				ty = 1.3;
				fv = 60f;
			}
			case 3 -> { // over the shoulder
				focus = m;
				fwd = a.multiply(-1).add(side.multiply(0.35)).normalize();
				dist = 2.6;
				h = 1.75;
				ty = 1.3;
				fv = 56f;
			}
			case 4 -> {
				focus = m;
				fwd = side.multiply(-1);
				dist = 2.8;
				h = 0.45;
				ty = 1.3;
				fv = 64f;
			}
			case 5 -> {
				focus = p;
				fwd = rot(a, -0.9);
				dist = 2.1;
				h = 1.2;
				ty = 1.4;
				fv = 52f;
			}
			case 6 -> { // the charge: face again, even closer
				focus = p;
				fwd = a;
				dist = lerp(0.85f, 0.68f, k);
				h = 1.7;
				ty = 1.66;
				fv = 32f;
			}
			case 7 -> { // the final slash, seen from far away
				focus = m;
				fwd = rot(side, 0.5);
				dist = lerp(9.0f, 10.5f, k);
				h = 0.25;
				ty = 1.5;
				fv = 70f;
			}
			default -> { // hero pose
				focus = p;
				fwd = rot(a, 0.35);
				dist = lerp(3.8f, 3.1f, k);
				h = 0.3;
				ty = 1.6;
				fv = 62f;
			}
		}
		Vec3d pos = focus.add(fwd.x * dist, h, fwd.z * dist);
		Vec3d target = new Vec3d(focus.x, focus.y + ty, focus.z);
		double sh = s == 6 ? 0.03 : 0.008;
		double tt = t * 31.0;
		pos = pos.add(Math.sin(tt) * sh, Math.cos(tt * 1.3) * sh, Math.sin(tt * 0.7 + 1) * sh);
		Vec3d d = target.subtract(pos);
		double len = d.length();
		if (len < 1e-4) {
			return;
		}
		float yw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pt = (float) -Math.toDegrees(Math.asin(d.y / len));
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
		if (t < 0f || t >= TOTALS[script]) {
			return;
		}
		int s = shot(t);
		boolean lines = script == 0 ? (s == 2 || s == 3 || s == 5) : (s == 1 || s == 4 || s == 7);
		boolean close = script == 0 ? (s == 0 || s == 4) : (s == 0 || s == 6);
		if (lines) {
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
		if (close) {
			Fx.vignettePublic(ctx, w, h, 0x000000, 0.55f);
			Fx.vignettePublic(ctx, w, h, 0xC00800, 0.25f + 0.1f * (float) Math.sin(t * 16.0));
		}
		float fl = cutFlash(t);
		if (fl > 0f) {
			ctx.fill(0, 0, w, h, Fx.argb(script == 0 && s == 4 ? 0xFF4030 : 0xFFFFFF, 0.55f * fl));
		}
	}
}
