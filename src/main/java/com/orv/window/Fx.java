package com.orv.window;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Effects of the Slayer skills, drawn from scratch (no Minecraft particles): slash arcs, sparks,
 * after-images, shock rings, light pillars, the Berserk aura and the screen overlays.
 * Everything is additive light so it reads as glowing energy.
 */
public final class Fx {
	private Fx() {
	}

	private static final class Effect {
		int type;
		Vec3d pos;
		Vec3d dir;
		float a;
		int b;
		long start;
		float life;
		long seed;
	}

	private static final List<Effect> LIST = new ArrayList<>();
	private static final Random RAND = new Random();

	// ---- state shared with the HUD / movement code
	public static long clientTick = 0;
	public static int berserkTicks = 0;
	public static boolean locked = false;

	private static boolean cutscene = false;
	private static long cutsceneAt = 0;
	private static long cutsceneEndAt = 0;
	private static Perspective savedView = null;
	private static long flashAt = 0;
	private static float flashPower = 0f;
	private static int flashRgb = 0xFFFFFF;

	// palettes: glow, mid, core
	private static final int[][] PALETTE = {
			{0xC81810, 0xFF5530, 0xFFEBD8},
			{0xD01C10, 0xFF6034, 0xFFEFE0},
			{0xFF2010, 0xFFB030, 0xFFFFF0},
			{0xB07010, 0xFFD060, 0xFFFFF4}
	};

	// ------------------------------------------------------------------ client tick

	public static void tick(MinecraftClient mc) {
		clientTick++;
		if (berserkTicks > 0) {
			berserkTicks--;
		}
		// safety: the server says the cutscene lock is over but the end packet got lost
		if (cutscene && !locked && System.currentTimeMillis() - cutsceneAt > 3000) {
			endCutscene(mc);
		}
	}

	public static void reset() {
		LIST.clear();
		LightClient.reset();
		berserkTicks = 0;
		locked = false;
		cutscene = false;
		savedView = null;
	}

	// ------------------------------------------------------------------ spawning (from the FX packet)

	public static void spawn(int type, Vec3d pos, Vec3d dir, float a, int b) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null) {
			return;
		}
		switch (type) {
			case FxNet.CUTSCENE_START -> {
				startCutscene(mc);
				return;
			}
			case FxNet.CUTSCENE_END -> {
				endCutscene(mc);
				return;
			}
			case FxNet.FLASH -> {
				flash(a, b == 1 ? 0xFFD0A0 : 0xFFFFFF);
				return;
			}
			default -> {
			}
		}
		Effect e = new Effect();
		e.type = type;
		e.pos = pos;
		e.dir = dir;
		e.a = a;
		e.b = b;
		e.start = System.nanoTime();
		e.seed = RAND.nextLong();
		e.life = switch (type) {
			case FxNet.SLASH -> b == 2 ? 0.62f : 0.46f;
			case FxNet.IMPACT -> 0.5f;
			case FxNet.DASH -> 0.4f;
			case FxNet.SHOCK -> 0.75f;
			case FxNet.GRAB -> 0.7f;
			case FxNet.PARRY -> 0.4f;
			case FxNet.BERSERK_START -> 1.2f;
			case FxNet.LB_CIRCLE -> 1.4f;
			case FxNet.PROJ -> Math.max(0.2f, a) + 0.1f;
			case FxNet.HEAL_PULSE -> 1.0f;
			case FxNet.ORB_SHOT -> 0.25f;
			case FxNet.HALO_BURST -> 0.9f;
			default -> 0.4f;
		};
		if (LIST.size() > 160) {
			LIST.remove(0);
		}
		LIST.add(e);
		playSound(mc, type, pos, a, b);
		if (type == FxNet.BERSERK_START && mc.player.squaredDistanceTo(pos) < 100.0) {
			flash(0.7f, 0xFF3010);
		}
	}

	private static void playSound(MinecraftClient mc, int type, Vec3d p, float a, int b) {
		SoundEvent s = null;
		float vol = 1f;
		float pitch = 0.95f + RAND.nextFloat() * 0.2f;
		switch (type) {
			case FxNet.SLASH -> {
				s = OrvSounds.SLASH;
				vol = 0.9f;
				pitch = b == 2 ? 0.8f : pitch;
			}
			case FxNet.IMPACT -> {
				s = OrvSounds.IMPACT;
				vol = Math.min(1.4f, 0.6f + a * 0.3f);
				pitch = a > 2f ? 0.7f : pitch;
			}
			case FxNet.DASH -> {
				s = OrvSounds.DASH;
				vol = 0.8f;
			}
			case FxNet.SHOCK -> {
				if (a >= 5f && b == 0) {
					s = OrvSounds.BOOM;
					vol = 1.5f;
				}
			}
			case FxNet.GRAB -> {
				s = OrvSounds.BOOM;
				vol = 1.0f;
				pitch = 1.2f;
			}
			case FxNet.PARRY -> {
				s = OrvSounds.PARRY;
				vol = 1.0f;
			}
			case FxNet.LB_CIRCLE, FxNet.HEAL_PULSE -> {
				s = OrvSounds.PARRY;
				vol = 0.6f;
				pitch = 1.5f;
			}
			case FxNet.ORB_SHOT -> {
				s = OrvSounds.DASH;
				vol = 0.5f;
				pitch = 1.6f;
			}
			case FxNet.HALO_BURST -> {
				s = b == 1 ? OrvSounds.BOOM : OrvSounds.PARRY;
				vol = 1.0f;
				pitch = b == 1 ? 1.3f : 1.1f;
			}
			case FxNet.BERSERK_START -> {
				s = OrvSounds.BERSERK;
				vol = 1.6f;
				pitch = 1f;
			}
			default -> {
			}
		}
		if (s != null) {
			mc.world.playSound(p.x, p.y, p.z, s, SoundCategory.PLAYERS, vol, pitch, false);
		}
	}

	private static void flash(float power, int rgb) {
		flashAt = System.nanoTime();
		flashPower = power;
		flashRgb = rgb;
	}

	private static void startCutscene(MinecraftClient mc) {
		if (!cutscene) {
			savedView = mc.options.getPerspective();
		}
		cutscene = true;
		cutsceneAt = System.currentTimeMillis();
		mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
	}

	private static void endCutscene(MinecraftClient mc) {
		if (!cutscene) {
			return;
		}
		cutscene = false;
		cutsceneEndAt = System.currentTimeMillis();
		if (savedView != null) {
			mc.options.setPerspective(savedView);
			savedView = null;
		}
	}

	// ------------------------------------------------------------------ small helpers

	static float clamp01(float x) {
		return x < 0f ? 0f : (x > 1f ? 1f : x);
	}

	static double easeOut(double x) {
		x = Math.max(0, Math.min(1, x));
		double k = 1 - x;
		return 1 - k * k * k;
	}

	static int argb(int rgb, float alpha) {
		int a = Math.round(255f * clamp01(alpha));
		return (a << 24) | (rgb & 0xFFFFFF);
	}

	static int mix(int rgbA, int rgbB, float t) {
		t = clamp01(t);
		int r = Math.round(((rgbA >> 16) & 255) * (1 - t) + ((rgbB >> 16) & 255) * t);
		int g = Math.round(((rgbA >> 8) & 255) * (1 - t) + ((rgbB >> 8) & 255) * t);
		int b = Math.round((rgbA & 255) * (1 - t) + (rgbB & 255) * t);
		return (r << 16) | (g << 8) | b;
	}

	/** Pseudo random number in [0,1) from a seed and an index. */
	static float rnd(long seed, int i) {
		long z = seed + 0x9E3779B97F4A7C15L * (i + 1);
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		z = z ^ (z >>> 31);
		return (float) ((z >>> 40) / (double) (1L << 24));
	}

	/** Drawing helper: a buffer plus the camera offset. */
	static final class Pen {
		final BufferBuilder b;
		final Matrix4f m;
		final Vec3d cam;
		Vec3d camRight = new Vec3d(1, 0, 0);
		Vec3d camUp = new Vec3d(0, 1, 0);

		Pen(BufferBuilder b, Matrix4f m, Vec3d cam) {
			this.b = b;
			this.m = m;
			this.cam = cam;
		}

		void v(Vec3d p, int argb) {
			b.vertex(m, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z))
					.color((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, (argb >>> 24) & 255).next();
		}

		void quad(Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, int c0, int c1, int c2, int c3) {
			v(p0, c0);
			v(p1, c1);
			v(p2, c2);
			v(p3, c3);
		}

		/** Camera-facing axes for a point. */
		void face(Vec3d p) {
			Vec3d view = p.subtract(cam);
			if (view.lengthSquared() < 1e-6) {
				return;
			}
			view = view.normalize();
			Vec3d r = view.crossProduct(new Vec3d(0, 1, 0));
			if (r.lengthSquared() < 1e-6) {
				r = new Vec3d(1, 0, 0);
			}
			camRight = r.normalize();
			camUp = camRight.crossProduct(view).normalize();
		}

		/** Radial glow: bright in the middle, fading to nothing at the edge. */
		void glow(Vec3d p, double radius, int rgb, float alpha) {
			if (alpha <= 0.003f || radius <= 0) {
				return;
			}
			face(p);
			int center = argb(rgb, alpha);
			int edge = argb(rgb, 0f);
			int n = 16;
			for (int i = 0; i < n; i++) {
				double a0 = i * Math.PI * 2 / n;
				double a1 = (i + 1) * Math.PI * 2 / n;
				Vec3d p0 = p.add(camRight.multiply(Math.cos(a0) * radius)).add(camUp.multiply(Math.sin(a0) * radius));
				Vec3d p1 = p.add(camRight.multiply(Math.cos(a1) * radius)).add(camUp.multiply(Math.sin(a1) * radius));
				quad(p, p0, p1, p, center, edge, edge, center);
			}
		}

		/** A thin glowing line (camera-facing ribbon) from p0 to p1. */
		void streak(Vec3d p0, Vec3d p1, double width, int c0, int c1) {
			Vec3d d = p1.subtract(p0);
			if (d.lengthSquared() < 1e-8) {
				return;
			}
			Vec3d mid = p0.add(p1).multiply(0.5);
			Vec3d side = d.crossProduct(mid.subtract(cam));
			if (side.lengthSquared() < 1e-10) {
				return;
			}
			side = side.normalize().multiply(width * 0.5);
			quad(p0.subtract(side), p0.add(side), p1.add(side), p1.subtract(side), c0, c0, c1, c1);
		}

		/** Flat ring on the ground plane with soft edges. */
		void ring(Vec3d c, double radius, double thickness, int rgb, float alpha) {
			if (alpha <= 0.003f) {
				return;
			}
			int hot = argb(rgb, alpha);
			int cold = argb(rgb, 0f);
			int n = 56;
			double r0 = Math.max(0.01, radius - thickness);
			double r2 = radius + thickness * 0.6;
			for (int i = 0; i < n; i++) {
				double a0 = i * Math.PI * 2 / n;
				double a1 = (i + 1) * Math.PI * 2 / n;
				Vec3d i0 = c.add(Math.cos(a0) * r0, 0, Math.sin(a0) * r0);
				Vec3d i1 = c.add(Math.cos(a1) * r0, 0, Math.sin(a1) * r0);
				Vec3d m0 = c.add(Math.cos(a0) * radius, 0, Math.sin(a0) * radius);
				Vec3d m1 = c.add(Math.cos(a1) * radius, 0, Math.sin(a1) * radius);
				Vec3d o0 = c.add(Math.cos(a0) * r2, 0, Math.sin(a0) * r2);
				Vec3d o1 = c.add(Math.cos(a1) * r2, 0, Math.sin(a1) * r2);
				quad(i0, m0, m1, i1, cold, hot, hot, cold);
				quad(m0, o0, o1, m1, hot, cold, cold, hot);
			}
		}

		/** A leaf-shaped petal: bright at the root, fading to the tip. dir and side are unit vectors in the petal plane. */
		void petal(Vec3d root, Vec3d dir, Vec3d side, double len, double width, int rgb, float alpha) {
			if (alpha <= 0.003f) {
				return;
			}
			Vec3d tip = root.add(dir.multiply(len));
			Vec3d mid = root.add(dir.multiply(len * 0.45));
			Vec3d l = mid.add(side.multiply(width * 0.5));
			Vec3d r = mid.subtract(side.multiply(width * 0.5));
			int cRoot = argb(rgb, alpha * 0.9f);
			int cMid = argb(rgb, alpha * 0.55f);
			int cTip = argb(rgb, alpha * 0.1f);
			quad(root, l, tip, r, cRoot, cMid, cTip, cMid);
		}

		/** A flowing ribbon through the given points (camera-facing), tapering in width and opacity. */
		void ribbon(Vec3d[] pts, double w0, double w1, int rgb, float a0, float a1) {
			int n = pts.length;
			for (int i = 0; i < n - 1; i++) {
				float s0 = i / (float) (n - 1);
				float s1 = (i + 1) / (float) (n - 1);
				double w = w0 + (w1 - w0) * (s0 + s1) * 0.5;
				streak(pts[i], pts[i + 1], w, argb(rgb, a0 + (a1 - a0) * s0), argb(rgb, a0 + (a1 - a0) * s1));
			}
		}

		/** Ring in an arbitrary plane given by two unit axes u and v. */
		void ringIn(Vec3d c, Vec3d u, Vec3d v, double radius, double thickness, int rgb, float alpha) {
			if (alpha <= 0.003f) {
				return;
			}
			int hot = argb(rgb, alpha);
			int cold = argb(rgb, 0f);
			int n = 48;
			double r0 = Math.max(0.01, radius - thickness);
			double r2 = radius + thickness * 0.6;
			for (int i = 0; i < n; i++) {
				double a0 = i * Math.PI * 2 / n;
				double a1 = (i + 1) * Math.PI * 2 / n;
				Vec3d i0 = c.add(u.multiply(Math.cos(a0) * r0)).add(v.multiply(Math.sin(a0) * r0));
				Vec3d i1 = c.add(u.multiply(Math.cos(a1) * r0)).add(v.multiply(Math.sin(a1) * r0));
				Vec3d m0 = c.add(u.multiply(Math.cos(a0) * radius)).add(v.multiply(Math.sin(a0) * radius));
				Vec3d m1 = c.add(u.multiply(Math.cos(a1) * radius)).add(v.multiply(Math.sin(a1) * radius));
				Vec3d o0 = c.add(u.multiply(Math.cos(a0) * r2)).add(v.multiply(Math.sin(a0) * r2));
				Vec3d o1 = c.add(u.multiply(Math.cos(a1) * r2)).add(v.multiply(Math.sin(a1) * r2));
				quad(i0, m0, m1, i1, cold, hot, hot, cold);
				quad(m0, o0, o1, m1, hot, cold, cold, hot);
			}
		}

		/** Vertical beam of light that always faces the camera. */
		void pillar(Vec3d base, double height, double halfWidth, int rgb, float alpha) {
			if (alpha <= 0.003f) {
				return;
			}
			face(base.add(0, height * 0.5, 0));
			Vec3d r = new Vec3d(camRight.x, 0, camRight.z);
			if (r.lengthSquared() < 1e-6) {
				return;
			}
			r = r.normalize().multiply(halfWidth);
			Vec3d top = base.add(0, height, 0);
			int hot = argb(rgb, alpha);
			int cold = argb(rgb, 0f);
			quad(base.subtract(r), base, top, top.subtract(r), cold, hot, cold, cold);
			quad(base, base.add(r), top.add(r), top, hot, cold, cold, cold);
		}
	}

	// ------------------------------------------------------------------ rendering

	public static void render(WorldRenderContext ctx) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null) {
			return;
		}
		boolean aura = berserkTicks > 0 || (OrvWindowClient.slayer && OrvWindowClient.rage >= 70f);
		if (LIST.isEmpty() && !aura && !LightClient.any()) {
			return;
		}
		MatrixStack ms = ctx.matrixStack();
		Matrix4f m = ms.peek().getPositionMatrix();
		Vec3d cam = ctx.camera().getPos();
		float td = ctx.tickDelta();

		RenderSystem.enableBlend();
		RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
		RenderSystem.setShader(GameRenderer::getPositionColorProgram);
		RenderSystem.disableCull();
		RenderSystem.depthMask(false);
		RenderSystem.enableDepthTest();

		BufferBuilder bb = Tessellator.getInstance().getBuffer();
		bb.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
		Pen pen = new Pen(bb, m, cam);

		long now = System.nanoTime();
		Iterator<Effect> it = LIST.iterator();
		while (it.hasNext()) {
			Effect e = it.next();
			float age = (now - e.start) / 1_000_000_000f;
			if (age >= e.life) {
				it.remove();
				continue;
			}
			float tt = age / e.life;
			switch (e.type) {
				case FxNet.SLASH -> drawSlash(pen, e, tt, age);
				case FxNet.IMPACT -> drawImpact(pen, e, tt, age);
				case FxNet.DASH -> drawDash(pen, e, tt);
				case FxNet.SHOCK -> drawShock(pen, e, tt);
				case FxNet.GRAB -> drawGrab(pen, e, tt);
				case FxNet.PARRY -> drawParry(pen, e, tt, age);
				case FxNet.BERSERK_START -> drawBerserkBurst(pen, e, tt);
				case FxNet.LB_CIRCLE -> LightClient.drawCircle(pen, e.pos, e.dir, e.a, e.b, tt);
				case FxNet.PROJ -> LightClient.drawProj(pen, e.pos, e.dir, age, e.a);
				case FxNet.HEAL_PULSE -> LightClient.drawPulse(pen, e.pos, e.a, e.b, tt);
				case FxNet.ORB_SHOT -> LightClient.drawShot(pen, e.pos, e.dir, tt);
				case FxNet.HALO_BURST -> LightClient.drawBurst(pen, e.pos, e.b, tt);
				default -> {
				}
			}
		}
		if (aura) {
			drawAura(pen, mc, td);
		}
		LightClient.render(pen, mc, td);

		Tessellator.getInstance().draw();

		RenderSystem.defaultBlendFunc();
		RenderSystem.enableCull();
		RenderSystem.depthMask(true);
		RenderSystem.enableDepthTest();
		RenderSystem.disableBlend();
	}

	// ---- slash arc

	private static void drawSlash(Pen pen, Effect e, float tt, float age) {
		int[] pal = PALETTE[Math.max(0, Math.min(3, e.b))];
		Vec3d f = e.dir.lengthSquared() < 1e-6 ? new Vec3d(0, 0, 1) : e.dir.normalize();
		Vec3d r = Math.abs(f.y) > 0.95 ? new Vec3d(1, 0, 0) : new Vec3d(-f.z, 0, f.x).normalize();
		Vec3d u = r.crossProduct(f).normalize();
		double roll = Math.toRadians(e.a);
		Vec3d p = r.multiply(Math.cos(roll)).add(u.multiply(Math.sin(roll)));
		double radius = e.b == 2 ? 2.9 : (e.b == 1 ? 2.45 : 2.2);
		double sweep = Math.toRadians(e.b == 2 ? 200 : 150);
		Vec3d origin = e.pos.subtract(f.multiply(0.5));

		double head = easeOut(tt / 0.42);
		double tail = Math.pow(Math.max(0, (tt - 0.1) / 0.9), 1.3);
		if (head - tail < 0.02) {
			return;
		}
		float fade = 1f - (float) Math.pow(Math.max(0, (tt - 0.4) / 0.6), 1.5);
		int n = 34;
		double[] widths = {0.62, 0.30, 0.10};
		float[] alphas = {0.30f, 0.80f, 1.0f};
		double wScale = e.b == 2 ? 1.35 : 1.0;
		for (int layer = 0; layer < 3; layer++) {
			double w = widths[layer] * wScale;
			for (int i = 0; i < n; i++) {
				double s0 = i / (double) n;
				double s1 = (i + 1) / (double) n;
				Vec3d[] a = arcPoints(origin, f, p, radius, sweep, tail + (head - tail) * s0, w * taper(s0));
				Vec3d[] b = arcPoints(origin, f, p, radius, sweep, tail + (head - tail) * s1, w * taper(s1));
				float a0 = (float) Math.pow(s0, 1.2) * fade * alphas[layer];
				float a1 = (float) Math.pow(s1, 1.2) * fade * alphas[layer];
				int c0 = argb(pal[layer], a0);
				int c1 = argb(pal[layer], a1);
				pen.quad(a[0], a[1], b[1], b[0], c0, c0, c1, c1);
			}
		}
		// bright head
		Vec3d tip = arcPoints(origin, f, p, radius, sweep, head, 0)[0];
		pen.glow(tip, 0.55 * wScale, pal[1], 0.9f * fade * (float) (1 - tt));
		pen.glow(tip, 0.2 * wScale, pal[2], 1f * fade);
		// sparks thrown off the head
		long seed = e.seed;
		Vec3d tang = tip.subtract(arcPoints(origin, f, p, radius, sweep, Math.max(0, head - 0.05), 0)[0]);
		tang = tang.lengthSquared() < 1e-8 ? f : tang.normalize();
		for (int i = 0; i < 9; i++) {
			float k = rnd(seed, i);
			float life = 0.25f + 0.2f * rnd(seed, i + 20);
			float t2 = (tt * e.life - 0.35f * k) / life;
			if (t2 < 0 || t2 > 1) {
				continue;
			}
			Vec3d v = tang.multiply(1.5 + 2.5 * rnd(seed, i + 40)).add(rndDir(seed, i + 60).multiply(1.6));
			Vec3d sp = tip.add(v.multiply(t2 * life)).add(0, -2.2 * t2 * life * t2 * life, 0);
			Vec3d sp2 = sp.subtract(v.multiply(0.05));
			pen.streak(sp2, sp, 0.03, argb(pal[2], 0f), argb(mix(pal[2], pal[0], t2), (1 - t2) * fade));
		}
	}

	private static double taper(double s) {
		return Math.pow(Math.sin(Math.PI * Math.min(1.0, 0.85 * s + 0.1)), 0.7);
	}

	/** Point on the arc at fraction u. Returns {inner edge, outer edge}; with width 0 both are the centre line. */
	private static Vec3d[] arcPoints(Vec3d origin, Vec3d f, Vec3d p, double radius, double sweep, double u, double width) {
		double th = -sweep / 2 + sweep * u;
		Vec3d out = f.multiply(Math.cos(th)).add(p.multiply(Math.sin(th)));
		Vec3d center = origin.add(out.multiply(radius));
		return new Vec3d[]{center.subtract(out.multiply(width * 0.75)), center.add(out.multiply(width * 0.25))};
	}

	private static Vec3d rndDir(long seed, int i) {
		double z = rnd(seed, i) * 2 - 1;
		double a = rnd(seed, i + 7) * Math.PI * 2;
		double r = Math.sqrt(Math.max(0, 1 - z * z));
		return new Vec3d(r * Math.cos(a), z, r * Math.sin(a));
	}

	// ---- impact

	private static void drawImpact(Pen pen, Effect e, float tt, float age) {
		int[] pal = PALETTE[Math.max(0, Math.min(3, e.b))];
		float size = Math.max(0.6f, e.a);
		// flash
		float fl = 1f - clamp01(tt / 0.35f);
		pen.glow(e.pos, 0.9 * size, pal[1], 0.9f * fl);
		pen.glow(e.pos, 0.35 * size, pal[2], 1.0f * fl);
		// scratch lines in the camera plane
		pen.face(e.pos);
		if (tt < 0.4f) {
			float k = 1f - tt / 0.4f;
			for (int i = 0; i < 2; i++) {
				double ang = rnd(e.seed, i) * Math.PI;
				Vec3d d = pen.camRight.multiply(Math.cos(ang)).add(pen.camUp.multiply(Math.sin(ang))).multiply(0.55 * size);
				pen.streak(e.pos.subtract(d), e.pos.add(d), 0.05 * size, argb(pal[2], 0f), argb(pal[2], 0.9f * k));
				pen.streak(e.pos, e.pos.add(d), 0.05 * size, argb(pal[2], 0.9f * k), argb(pal[1], 0f));
			}
		}
		// sparks
		Vec3d base = e.dir.lengthSquared() < 1e-6 ? new Vec3d(0, 1, 0) : e.dir.normalize();
		int n = Math.round(12 + size * 5);
		for (int i = 0; i < n; i++) {
			float life = 0.3f + 0.2f * rnd(e.seed, i + 30);
			float t2 = age / life;
			if (t2 >= 1f) {
				continue;
			}
			Vec3d dirv = rndDir(e.seed, i + 50).multiply(1.0).add(base.multiply(0.6)).normalize();
			double speed = (2.5 + 5.5 * rnd(e.seed, i + 80)) * (0.8 + 0.2 * size);
			Vec3d v = dirv.multiply(speed);
			Vec3d sp = e.pos.add(v.multiply(age)).add(0, -5.0 * age * age, 0);
			Vec3d prev = e.pos.add(v.multiply(Math.max(0, age - 0.04))).add(0, -5.0 * Math.max(0, age - 0.04) * Math.max(0, age - 0.04), 0);
			pen.streak(prev, sp, 0.035, argb(pal[1], 0f), argb(mix(pal[2], pal[0], t2), (1 - t2) * 0.95f));
		}
	}

	// ---- dash trail

	private static void drawDash(Pen pen, Effect e, float tt) {
		Vec3d a = e.pos;
		Vec3d b = e.pos.add(e.dir);
		float fade = (float) Math.pow(1 - tt, 1.3);
		int[] pal = PALETTE[e.b == 1 ? 2 : 0];
		// long streak, thick at the end point
		pen.streak(a, b, 0.55, argb(pal[0], 0f), argb(pal[1], 0.55f * fade));
		pen.streak(a, b, 0.18, argb(pal[2], 0f), argb(pal[2], 0.9f * fade));
		// after-images
		pen.face(a);
		for (int i = 0; i < 6; i++) {
			double k = i / 5.0;
			Vec3d c = a.add(e.dir.multiply(k));
			float f2 = clamp01(1f - tt * 1.4f - (float) (1 - k) * 0.25f) * 0.55f;
			if (f2 <= 0f) {
				continue;
			}
			pen.face(c);
			Vec3d r = pen.camRight.multiply(0.28);
			Vec3d lo = c.add(0, -0.9, 0);
			Vec3d hi = c.add(0, 0.9, 0);
			int mid = argb(pal[1], f2);
			int edge = argb(pal[0], 0f);
			pen.quad(lo.subtract(r), lo.add(r), c.add(r), c.subtract(r), edge, edge, mid, mid);
			pen.quad(c.subtract(r), c.add(r), hi.add(r), hi.subtract(r), mid, mid, edge, edge);
		}
		pen.glow(b, 0.9, pal[1], 0.7f * fade);
	}

	// ---- shock ring

	private static void drawShock(Pen pen, Effect e, float tt) {
		double R = Math.max(1.0, e.a);
		boolean white = e.b == 1;
		int core = white ? 0xFFF0D0 : 0xFF6040;
		int glowc = white ? 0xFFB050 : 0xC01810;
		double r = R * easeOut(tt);
		float fade = (float) Math.pow(1 - tt, 1.2);
		pen.ring(e.pos, r, R * 0.14 * (1 - tt) + 0.08, core, 0.95f * fade);
		pen.ring(e.pos, r * 0.92, R * 0.3 * (1 - tt) + 0.1, glowc, 0.5f * fade);
		if (tt < 0.5f) {
			pen.glow(e.pos.add(0, 0.2, 0), R * 0.35, core, 0.8f * (1 - tt * 2));
		}
	}

	private static void drawGrab(Pen pen, Effect e, float tt) {
		float fade = (float) Math.pow(1 - tt, 1.4);
		pen.glow(e.pos, 1.0 + 2.5 * easeOut(tt * 2), 0xFFE0A0, 0.9f * fade);
		pen.glow(e.pos, 0.4, 0xFFFFFF, fade);
		pen.pillar(e.pos.add(0, -1.0, 0), 11.0, 0.55 * (1 - tt * 0.5), 0xFFB060, 0.85f * fade);
	}

	private static void drawParry(Pen pen, Effect e, float tt, float age) {
		float fade = 1f - tt;
		pen.glow(e.pos, 0.7, 0xFFD870, 0.9f * fade);
		pen.glow(e.pos, 0.25, 0xFFFFFF, fade);
		for (int i = 0; i < 12; i++) {
			Vec3d d = rndDir(e.seed, i).multiply(0.5 + 2.8 * rnd(e.seed, i + 20));
			Vec3d sp = e.pos.add(d.multiply(age * 2.0));
			Vec3d pr = e.pos.add(d.multiply(Math.max(0, age - 0.04) * 2.0));
			pen.streak(pr, sp, 0.03, argb(0xFFD870, 0f), argb(0xFFF0B0, fade));
		}
	}

	private static void drawBerserkBurst(Pen pen, Effect e, float tt) {
		float fade = (float) Math.pow(1 - tt, 1.2);
		double r = 7.0 * easeOut(tt);
		Vec3d g = e.pos.add(0, 0.05, 0);
		pen.ring(g, r, 0.5 * (1 - tt) + 0.1, 0xFF5030, 0.9f * fade);
		pen.ring(g, r * 0.6, 0.9 * (1 - tt) + 0.1, 0xC01810, 0.6f * fade);
		pen.pillar(g, 9.0, 0.9 * (1 - tt * 0.6), 0xFF3010, 0.8f * fade);
		pen.glow(e.pos.add(0, 1.0, 0), 2.6 * easeOut(tt * 1.5), 0xFF4020, 0.7f * fade);
	}

	// ---- Berserk / rage aura around the local player

	private static void drawAura(Pen pen, MinecraftClient mc, float td) {
		Vec3d base = mc.player.getLerpedPos(td);
		double t = System.nanoTime() / 1_000_000_000.0;
		boolean berserk = berserkTicks > 0;
		int count = berserk ? 34 : 12;
		float power = berserk ? 1f : 0.55f;
		for (int i = 0; i < count; i++) {
			float s1 = rnd(7, i);
			float s2 = rnd(77, i);
			float s3 = rnd(777, i);
			double phase = (t * (0.35 + 0.5 * s2) + s1) % 1.0;
			double ang = s1 * Math.PI * 2 + t * (0.6 + s3);
			double rad = 0.45 + 0.4 * s3;
			Vec3d p = base.add(Math.cos(ang) * rad, 0.1 + phase * 2.4, Math.sin(ang) * rad);
			float a = (float) Math.sin(Math.PI * phase) * power;
			int col = mix(0xFFA040, 0xC01808, (float) phase);
			pen.glow(p, 0.05 + 0.05 * (1 - phase), col, 0.9f * a);
			if (berserk && i % 3 == 0) {
				Vec3d p2 = p.add(0, 0.45 + 0.3 * s2, 0);
				pen.streak(p, p2, 0.025, argb(col, 0.8f * a), argb(col, 0f));
			}
		}
		if (berserk) {
			// pulsing red ring on the ground and a faint body glow
			float pulse = 0.55f + 0.25f * (float) Math.sin(t * 7.0);
			pen.ring(base.add(0, 0.04, 0), 1.0 + 0.1 * Math.sin(t * 5), 0.25, 0xFF3010, pulse);
			pen.ring(base.add(0, 0.04, 0), 1.6 + 0.2 * Math.sin(t * 3 + 1), 0.4, 0xC01810, pulse * 0.6f);
			pen.glow(base.add(0, 1.0, 0), 1.3, 0xFF2808, 0.18f + 0.06f * (float) Math.sin(t * 9));
		}
	}

	// ------------------------------------------------------------------ screen overlays

	public static void renderHud(DrawContext ctx, float td) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) {
			return;
		}
		int w = ctx.getScaledWindowWidth();
		int h = ctx.getScaledWindowHeight();
		double t = System.nanoTime() / 1_000_000_000.0;

		// berserk: red vignette that pulses
		if (berserkTicks > 0) {
			float k = 0.30f + 0.10f * (float) Math.sin(t * 6.0);
			if (berserkTicks < 40) {
				k *= berserkTicks / 40f;
			}
			vignette(ctx, w, h, 0xC00800, k);
		} else if (OrvWindowClient.slayer && OrvWindowClient.rage >= 85f) {
			vignette(ctx, w, h, 0xA00800, 0.10f + 0.05f * (float) Math.sin(t * 4.0));
		}

		// cutscene: letterbox bars
		long ms = System.currentTimeMillis();
		float bar = 0f;
		if (cutscene) {
			bar = clamp01((ms - cutsceneAt) / 400f);
		} else if (ms - cutsceneEndAt < 400) {
			bar = 1f - clamp01((ms - cutsceneEndAt) / 400f);
		}
		if (bar > 0f) {
			int bh = Math.round(h * 0.12f * (float) easeOut(bar));
			ctx.fill(0, 0, w, bh, 0xFF000000);
			ctx.fill(0, h - bh, w, h, 0xFF000000);
		}

		// flash
		float age = (System.nanoTime() - flashAt) / 1_000_000_000f;
		if (age < 0.35f && flashPower > 0f) {
			float a = flashPower * (1f - age / 0.35f);
			ctx.fill(0, 0, w, h, argb(flashRgb, a * 0.8f));
		}
	}

	private static void vignette(DrawContext ctx, int w, int h, int rgb, float strength) {
		int steps = 12;
		int sideW = Math.max(20, w / 5);
		int sideH = Math.max(16, h / 4);
		for (int i = 0; i < steps; i++) {
			float k = (float) Math.pow(1f - i / (float) steps, 2.0) * strength;
			int c = argb(rgb, k);
			int x0 = sideW * i / steps;
			int x1 = sideW * (i + 1) / steps;
			ctx.fill(x0, 0, x1, h, c);
			ctx.fill(w - x1, 0, w - x0, h, c);
			int y0 = sideH * i / steps;
			int y1 = sideH * (i + 1) / steps;
			ctx.fill(0, y0, w, y1, c);
			ctx.fill(0, h - y1, w, h - y0, c);
		}
	}
}
