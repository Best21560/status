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

	/** Script 0 = Berserk (5.2 s), script 1 = Relentless Pursuit (3.8 s), script 2 = Final Judgment (16 s, same clock as SlayerEngine.tickJudgment). */
	private static final float[][] CUT_TABLE = {
			{0f, 0.9f, 1.8f, 2.6f, 2.7f, 3.7f, 4.5f},
			{0f, 0.5f, 0.85f, 1.1f, 1.35f, 1.6f, 1.9f, 2.25f, 3.0f},
			{0f, 4f, 4.8f, 5.5f, 6.5f, 9f, 9.5f, 11.5f, 12.5f, 13f, 14.5f}
	};
	private static final float[] TOTALS = {5.2f, 3.8f, 16f};
	private static int script = 0;
	private static int targetId = -1;

	private static long t0 = 0;
	private static double fov = -1;
	private static boolean logged = false;

	/** Final Judgment: the fixed direction from the Slayer to the target when the skill started. */
	private static Vec3d axis = new Vec3d(0, 0, 1);

	public static void setAxis(Vec3d d) {
		Vec3d f = new Vec3d(d.x, 0, d.z);
		if (f.lengthSquared() > 1e-4) {
			axis = f.normalize();
		}
	}

	public static void start(int sc, int target) {
		script = Math.max(0, Math.min(2, sc));
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

	/** True while the Final Judgment cut-scene runs (its own sound set is used then). */
	public static boolean judgment() {
		return script == 2 && active();
	}

	/** The camera script was started and has played to its end. */
	public static boolean finished() {
		return t0 != 0 && time() >= TOTALS[script] + 0.3f;
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
		if (!logged) {
			logged = true;
			org.slf4j.LoggerFactory.getLogger("orvwindow").info("[orvwindow] cut-scene camera running: script {} shot {} (camera mixin applied: {})", script, s, cam instanceof CameraAccess);
		}
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
		if (script == 2) {
			applyJudgment(cam, p, f, s, k, t, td);
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
		a = a.lengthSquared() < 1e-4 ? f : a.normalize();
		Vec3d side = new Vec3d(-a.z, 0, a.x);
		Vec3d m = new Vec3d((p.x + q.x) / 2, Math.min(p.y, q.y), (p.z + q.z) / 2);
		Vec3d focus;
		Vec3d fwd;
		double dist;
		double h;
		double ty;
		float fv;
		switch (s) {
			case 0 -> {
				focus = p;
				fwd = a;
				dist = lerp(1.25f, 0.9f, k);
				h = 1.68;
				ty = 1.66;
				fv = 36f;
			}
			case 1 -> {
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
			case 3 -> {
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
			case 6 -> {
				focus = p;
				fwd = a;
				dist = lerp(0.85f, 0.68f, k);
				h = 1.7;
				ty = 1.66;
				fv = 32f;
			}
			case 7 -> {
				focus = m;
				fwd = rot(side, 0.5);
				dist = lerp(9.0f, 10.5f, k);
				h = 0.25;
				ty = 1.5;
				fv = 70f;
			}
			default -> {
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

	/** Final Judgment: 11 shots on the ground, following SlayerEngine.tickJudgment (P = the Slayer, Q = the target). */
	private static void applyJudgment(Camera cam, Vec3d p, Vec3d f, int s, float k, float t, float td) {
		Vec3d a = axis;
		Vec3d q = p.add(a.multiply(3.0));
		net.minecraft.client.world.ClientWorld w = MinecraftClient.getInstance().world;
		if (w != null && targetId >= 0) {
			Entity te = w.getEntityById(targetId);
			if (te != null) {
				q = te.getLerpedPos(td);
			}
		}
		Vec3d side = new Vec3d(-a.z, 0, a.x);
		Vec3d m = new Vec3d((p.x + q.x) / 2, Math.min(p.y, q.y), (p.z + q.z) / 2);
		Vec3d focus;
		Vec3d fwd;
		double dist;
		double h;
		double ty;
		float fv;
		switch (s) {
			case 0 -> { // the cut-in: game view from behind and above, slowly creeping in
				focus = p;
				fwd = a.multiply(-1).add(side.multiply(0.25)).normalize();
				dist = lerp(5.5f, 4.6f, k);
				h = 2.2;
				ty = 1.2;
				fv = 70f;
			}
			case 1 -> { // the hand closes on the hilt: low and close from the side
				focus = p;
				fwd = rot(side, 0.5);
				dist = lerp(1.6f, 1.3f, k);
				h = 1.0;
				ty = 1.15;
				fv = 40f;
			}
			case 2 -> { // the face, lunging at the camera
				focus = p;
				fwd = a;
				dist = lerp(1.6f, 0.7f, k * k);
				h = 1.6;
				ty = 1.62;
				fv = lerp(40f, 56f, k);
			}
			case 3 -> { // the first great cut, seen from the side
				focus = q;
				fwd = rot(side, -0.35);
				dist = 4.8;
				h = 0.9;
				ty = 1.2;
				fv = 66f;
			}
			case 4 -> { // the passes: a low camera skimming the ground around them
				focus = m;
				fwd = rot(side, k * 2.4 - 0.6);
				dist = lerp(6.5f, 5.0f, k);
				h = 0.25;
				ty = 0.9;
				fv = 78f;
			}
			case 5 -> { // sword up: looking up at the Slayer from close
				focus = p;
				fwd = rot(a, 0.6);
				dist = 1.9;
				h = 0.3;
				ty = 2.2;
				fv = 60f;
			}
			case 6 -> { // the great cuts from behind the Slayer
				focus = p;
				fwd = a.multiply(-1).add(side.multiply(lerp(-0.6f, 0.6f, k))).normalize();
				dist = 3.0;
				h = 1.5;
				ty = 1.3;
				fv = 64f;
			}
			case 7 -> { // the pose: sword held back, the Slayer from the front
				focus = p;
				fwd = rot(a, -0.4);
				dist = lerp(3.4f, 3.0f, k);
				h = 1.1;
				ty = 1.3;
				fv = 50f;
			}
			case 8 -> { // the hand comes forward: side shot
				focus = m;
				fwd = side;
				dist = 4.6;
				h = 1.0;
				ty = 1.3;
				fv = 60f;
			}
			case 9 -> { // the blast: wide, along the beam
				focus = q;
				fwd = rot(side, 0.55);
				dist = lerp(8.0f, 10.0f, k);
				h = 1.2;
				ty = 1.3;
				fv = 74f;
			}
			default -> { // the aftermath: pull back and up over the smoke
				focus = q;
				fwd = rot(a.multiply(-1), 0.3);
				dist = lerp(9.0f, 14.0f, k);
				h = lerp(2.0f, 5.0f, k);
				ty = 1.0;
				fv = 66f;
			}
		}
		Vec3d pos = focus.add(fwd.x * dist, h, fwd.z * dist);
		Vec3d target = new Vec3d(focus.x, focus.y + ty, focus.z);
		double sh = s == 2 ? 0.02 : (s == 3 || s == 6 || s == 9 ? 0.05 * Math.max(0, 1 - k * 2) + 0.01 : 0.008);
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

	/** The ultimate cut-in (0 - 4 s): a dark slanted band across the screen with the Slayer's face, red lightning crawling along it and the skill name. */
	private static void cutIn(net.minecraft.client.gui.DrawContext ctx, int w, int h, float t) {
		float in = Math.min(1f, t / 0.25f);
		float out = t < 3.7f ? 1f : Math.max(0f, 1f - (t - 3.7f) / 0.4f);
		float g = in * out;
		int bh = (int) (h * 0.2f * (0.4f + 0.6f * in));
		int cy = (int) (h * 0.36f);
		int slant = (int) (h * 0.06f);
		// the band: drawn as thin vertical slices so it can lean
		int wide = (int) (w * (0.15f + 0.85f * Math.min(1f, t / 0.35f)));
		int x0 = (w - wide) / 2;
		for (int x = x0; x < x0 + wide; x += 2) {
			float u = (x - x0) / (float) Math.max(1, wide);
			int off = (int) ((0.5f - u) * slant);
			int top = cy - bh / 2 + off;
			ctx.fillGradient(x, top, x + 2, top + bh, Fx.argb(0x1A0306, 0.92f * g), Fx.argb(0x040105, 0.95f * g));
			ctx.fill(x, top - 1, x + 2, top, Fx.argb(0x8A0A0A, 0.9f * g));
			ctx.fill(x, top + bh, x + 2, top + bh + 1, Fx.argb(0x8A0A0A, 0.9f * g));
		}
		// red lightning crawling along the band
		long frame = (long) (t * 24);
		for (int bolt = 0; bolt < 3; bolt++) {
			int y = cy + (int) ((Fx.rnd(frame * 7 + bolt, 1) - 0.5f) * bh * 0.6f);
			int x = x0;
			while (x < x0 + wide) {
				int nx = x + 6 + (int) (Fx.rnd(frame * 7 + bolt, x) * 14);
				int ny = cy + (int) ((Fx.rnd(frame * 7 + bolt, x + 3) - 0.5f) * bh * 0.7f);
				int steps = Math.max(1, Math.abs(ny - y));
				for (int k = 0; k <= steps; k++) {
					int px = x + (nx - x) * k / steps;
					int py = y + (ny - y) * k / steps;
					ctx.fill(px, py, px + 1, py + 1, Fx.argb(0xFF3A2A, 0.85f * g));
					ctx.fill(px - 1, py - 1, px + 2, py + 2, Fx.argb(0xA00808, 0.18f * g));
				}
				x = nx;
				y = ny;
			}
		}
		// the face of the Slayer, sliding in from the left
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player != null) {
			int size = (int) (bh * 0.86f);
			float slide = 1f - (float) Math.pow(1f - Math.min(1f, (t - 0.15f) / 0.45f), 3);
			int fx = (int) (w * 0.12f + (1f - slide) * -w * 0.3f);
			int fy = cy - size / 2 + (int) (0.3f * slant);
			if (slide > 0f) {
				try {
					com.mojang.blaze3d.systems.RenderSystem.setShaderColor(0.75f, 0.62f, 0.62f, g);
					net.minecraft.client.gui.PlayerSkinDrawer.draw(ctx, mc.player.getSkinTexture(), fx, fy, size);
				} finally {
					com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
				}
				// red glint in the eyes, a shadow over the upper face
				ctx.fill(fx, fy, fx + size, fy + size / 3, Fx.argb(0x000000, 0.45f * g));
				float pulse = 0.6f + 0.4f * (float) Math.sin(t * 9);
				int ey = fy + size * 9 / 16;
				ctx.fill(fx + size * 2 / 8, ey, fx + size * 3 / 8, ey + Math.max(1, size / 16), Fx.argb(0xFF2010, pulse * g));
				ctx.fill(fx + size * 5 / 8, ey, fx + size * 6 / 8, ey + Math.max(1, size / 16), Fx.argb(0xFF2010, pulse * g));
			}
		}
		// the skill name
		net.minecraft.client.font.TextRenderer tr = mc.textRenderer;
		float ts = Math.max(1f, bh / 34f);
		String name = "FINAL  JUDGMENT";
		float typed = Math.min(1f, Math.max(0f, (t - 0.6f) / 0.8f));
		String shown = name.substring(0, Math.round(name.length() * typed));
		ctx.getMatrices().push();
		ctx.getMatrices().translate(w * 0.12f + bh * 1.05f, cy - 4 * ts, 0);
		ctx.getMatrices().scale(ts, ts, 1f);
		ctx.drawText(tr, shown, 1, 1, Fx.argb(0x000000, g), false);
		ctx.drawText(tr, shown, 0, 0, Fx.argb(0xD8C8C8, g), false);
		ctx.getMatrices().pop();
	}

	/** Night sky: gradient, many dim stars with twinkle and slow drift, one shooting star and a single bright star that blooms. */
	private static void starSky(net.minecraft.client.gui.DrawContext ctx, int w, int h, float t) {
		float fade = t < 3f ? 1f : Math.max(0f, 1f - (t - 3f) / 0.25f);
		float in = Math.min(1f, t / 0.6f);
		float g = fade * in;
		// vertical gradient: near black on top, deep blue towards the horizon
		int bands = 24;
		for (int i = 0; i < bands; i++) {
			float k = i / (float) (bands - 1);
			int rgb = ((int) (2 + 10 * k * k) << 16) | ((int) (4 + 18 * k * k) << 8) | (int) (10 + 40 * k * k);
			ctx.fill(0, h * i / bands, w, h * (i + 1) / bands, Fx.argb(rgb, 0.93f * g));
		}
		// faint milky band
		for (int i = 0; i < 60; i++) {
			float u = Fx.rnd(7100, i);
			float x = u * w;
			float y = h * (0.75f - 0.55f * u) + (Fx.rnd(7101, i) - 0.5f) * h * 0.12f;
			int rad = 14 + (int) (Fx.rnd(7102, i) * 26);
			ctx.fill((int) x - rad, (int) y - rad / 3, (int) x + rad, (int) y + rad / 3, Fx.argb(0x8FA6D8, 0.025f * g));
		}
		// stars: mostly tiny and dim, a few brighter; the whole sky drifts slowly
		float drift = t * 3f;
		for (int i = 0; i < 260; i++) {
			float b = Fx.rnd(7001, i);
			float mag = b * b * b;
			float sx = ((Fx.rnd(7002, i) * w + drift * (0.4f + b)) % w + w) % w;
			float sy = Fx.rnd(7003, i) * h * 0.92f;
			float tw = 0.75f + 0.25f * (float) Math.sin(t * (2 + Fx.rnd(7004, i) * 5) + i * 1.7f);
			float al = (0.18f + 0.82f * mag) * tw * g;
			int tint = Fx.rnd(7005, i) > 0.5f ? 0xFFEBD6 : 0xCFE0FF;
			int sz = mag > 0.55f ? 2 : 1;
			ctx.fill((int) sx, (int) sy, (int) sx + sz, (int) sy + sz, Fx.argb(tint, al));
			if (mag > 0.8f) {
				ctx.fill((int) sx - 2, (int) sy, (int) sx + 3, (int) sy + 1, Fx.argb(tint, al * 0.3f));
				ctx.fill((int) sx, (int) sy - 2, (int) sx + 1, (int) sy + 3, Fx.argb(tint, al * 0.3f));
			}
		}
		// one shooting star around 1.2 - 1.6 s
		float ss = (t - 1.2f) / 0.4f;
		if (ss > 0f && ss < 1f) {
			float hx = w * (0.15f + 0.35f * ss);
			float hy = h * (0.10f + 0.16f * ss);
			for (int j = 0; j < 26; j++) {
				float kk = j / 26f;
				ctx.fill((int) (hx - j * 5), (int) (hy - j * 1.9f), (int) (hx - j * 5) + 3, (int) (hy - j * 1.9f) + 1, Fx.argb(0xFFFFFF, (1f - kk) * 0.8f * g));
			}
		}
		// the bright star: grows from a dim point; soft round glow, thin spikes, slight warm core
		float grow = Math.min(1f, Math.max(0f, (t - 0.4f) / 2.6f));
		float e = grow * grow * (3 - 2 * grow);
		int cx = w / 2 + (int) (Math.sin(t * 0.4) * 2);
		int cy = (int) (h * 0.30f);
		float sc = Math.max(1f, h / 240f);
		float maxR = (12 + 230 * e) * sc;
		for (int i = 18; i >= 1; i--) {
			float k = i / 18f;
			float rad = maxR * k * k;
			float al = (0.012f + 0.05f * e) * (1.2f - k) * g;
			int rr = (int) rad;
			for (int dy = -rr; dy <= rr; dy += 2) {
				int hw = (int) Math.sqrt(Math.max(0, rad * rad - dy * dy));
				ctx.fill(cx - hw, cy + dy, cx + hw, cy + dy + 2, Fx.argb(0xBFD4FF, al));
			}
		}
		int spike = (int) ((30 + 330 * e) * sc);
		for (int j = 1; j < spike; j++) {
			float al = (1f - j / (float) spike);
			al = al * al * (0.45f + 0.4f * e) * g;
			int th = j < spike * 0.25f ? 2 : 1;
			ctx.fill(cx + j, cy - th / 2, cx + j + 1, cy + 1 + th / 2, Fx.argb(0xFFFFFF, al));
			ctx.fill(cx - j - 1, cy - th / 2, cx - j, cy + 1 + th / 2, Fx.argb(0xFFFFFF, al));
			if (j < spike * 0.6f) {
				ctx.fill(cx - th / 2, cy + j, cx + 1 + th / 2, cy + j + 1, Fx.argb(0xFFFFFF, al * 0.8f));
				ctx.fill(cx - th / 2, cy - j - 1, cx + 1 + th / 2, cy - j, Fx.argb(0xFFFFFF, al * 0.8f));
			}
			if (j < spike * 0.3f) { // fainter diagonal spikes
				int dj = (int) (j * 0.7f);
				float ad = al * 0.35f;
				ctx.fill(cx + dj, cy + dj, cx + dj + 1, cy + dj + 1, Fx.argb(0xDDE6FF, ad));
				ctx.fill(cx - dj, cy - dj, cx - dj + 1, cy - dj + 1, Fx.argb(0xDDE6FF, ad));
				ctx.fill(cx + dj, cy - dj, cx + dj + 1, cy - dj + 1, Fx.argb(0xDDE6FF, ad));
				ctx.fill(cx - dj, cy + dj, cx - dj + 1, cy + dj + 1, Fx.argb(0xDDE6FF, ad));
			}
		}
		int core = (int) ((2 + 10 * e) * sc);
		for (int i = core; i >= 1; i--) {
			ctx.fill(cx - i, cy - i, cx + i, cy + i, Fx.argb(i == 1 ? 0xFFFFFF : 0xFFF3DC, (0.4f + 0.6f * (1f - i / (float) (core + 1))) * g));
		}
		// the blink of white right before the cut
		if (t > 2.75f) {
			ctx.fill(0, 0, w, h, Fx.argb(0xFFFFFF, Math.min(1f, (t - 2.75f) / 0.25f) * (t < 3f ? 1f : fade)));
		}
	}

	/** Screen effects of the shots: horizontal speed lines on the wide shots and a pulsing dark red edge on the close-ups. */
	public static void hud(net.minecraft.client.gui.DrawContext ctx, int w, int h) {
		float t = time();
		if (t < 0f || t >= TOTALS[script]) {
			return;
		}
		int s = shot(t);
		boolean lines = script == 0 ? (s == 2 || s == 3 || s == 5) : script == 1 ? (s == 1 || s == 4 || s == 7) : (s == 4 || s == 9);
		boolean close = script == 0 ? (s == 0 || s == 4) : script == 1 ? (s == 0 || s == 6) : false;
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
		if (script == 2) {
			if (t < 4.2f) {
				cutIn(ctx, w, h, t);
			}
			// the whole Final Judgment is graded dark: crushed edges, a cold dim tint, and black at the very end
			Fx.vignettePublic(ctx, w, h, 0x000000, 0.5f);
			ctx.fill(0, 0, w, h, Fx.argb(0x0A0610, 0.22f));
			if (t > 15.2f) {
				ctx.fill(0, 0, w, h, Fx.argb(0x000000, Math.min(1f, (t - 15.2f) / 0.6f)));
			}
		}
		if (close) {
			Fx.vignettePublic(ctx, w, h, 0x000000, 0.55f);
			Fx.vignettePublic(ctx, w, h, 0xC00800, 0.25f + 0.1f * (float) Math.sin(t * 16.0));
		}
		float fl = cutFlash(t);
		if (fl > 0f) {
			ctx.fill(0, 0, w, h, Fx.argb(script == 0 && s == 4 ? 0xFF4030 : (script == 2 ? 0xD8D0D4 : 0xFFFFFF), (script == 2 ? 0.35f : 0.55f) * fl));
		}
	}
}
