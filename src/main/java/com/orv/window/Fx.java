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
		final List<Vec3d> trail = new ArrayList<>();
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
	/** When the Berserk cut-scene started (nanoTime), 0 if none: drives the aura that builds up during the roar. */
	private static long introAt = 0;
	private static long flashAt = 0;
	private static float flashPower = 0f;
	private static int flashRgb = 0xFFFFFF;

	// palettes: glow, mid, core
	private static final int[][] PALETTE = {
			{0xD01818, 0xFF5A30, 0xFFEBD8}, // 0 normal: blood red
			{0xE03410, 0xFF8C38, 0xFFF4E0}, // 1 strong: fire
			{0xFF1810, 0xFF6A30, 0xFFFFFF}, // 2 heavy: crimson, white-hot
			{0x9A7A30, 0xFFE0A0, 0xFFFFFF}  // 3 counter: pale gold steel
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
		introAt = 0;
		PoseClient.reset();
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
				startCutscene(mc, b == 1);
				return;
			}
			case FxNet.ANIM -> {
					PoseClient.start(b, Math.round(a));
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
			case FxNet.SHOCK -> b == 2 ? 1.1f : 0.75f;
			case FxNet.GRAB -> 0.7f;
			case FxNet.PARRY -> 0.4f;
			case FxNet.BERSERK_START -> 1.7f;
				case FxNet.CHARGE -> 1.3f;
				case FxNet.LEAP -> a + 0.6f;
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
				pitch = b == 2 ? 0.8f : (b == 3 ? 1.3f : pitch);
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
				if (b == 2 && a >= 5f) {
						s = OrvSounds.CRACK;
						vol = 1.7f;
						pitch = 0.95f;
					} else if (a >= 5f && b == 0) {
					s = OrvSounds.BOOM;
					vol = 1.5f;
				}
			}
			case FxNet.CHARGE -> {
				s = OrvSounds.CHARGE;
				vol = 1.3f;
				pitch = 1f;
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
				mc.world.playSound(p.x, p.y, p.z, OrvSounds.ROAR, SoundCategory.PLAYERS, 2.4f, 1f, false);
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

	/** front = the Berserk cut-scene: the camera looks at the Slayer's face. */
	private static void startCutscene(MinecraftClient mc, boolean front) {
		if (!cutscene) {
			savedView = mc.options.getPerspective();
		}
		cutscene = true;
		cutsceneAt = System.currentTimeMillis();
		if (front) {
			introAt = System.nanoTime();
		}
		mc.options.setPerspective(front ? Perspective.THIRD_PERSON_FRONT : Perspective.THIRD_PERSON_BACK);
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
		private boolean begun = false;

		/** Draws what was collected (the buffer is only opened when the first vertex arrives, so it is never empty). */
		void flush() {
			if (begun) {
				begun = false;
				Tessellator.getInstance().draw();
			}
		}

		/** Flat disc on the ground, solid in the middle and soft at the edge. */
		void disc(Vec3d c, double radius, int rgb, float alpha) {
			if (alpha <= 0.003f || radius <= 0) {
				return;
			}
			int cc = argb(rgb, alpha);
			int ce = argb(rgb, 0f);
			int n = 40;
			for (int i = 0; i < n; i++) {
				double a0 = i * Math.PI * 2 / n;
				double a1 = (i + 1) * Math.PI * 2 / n;
				Vec3d p0 = c.add(Math.cos(a0) * radius, 0, Math.sin(a0) * radius);
				Vec3d p1 = c.add(Math.cos(a1) * radius, 0, Math.sin(a1) * radius);
				quad(c, p0, p1, c, cc, ce, ce, cc);
			}
		}

		Pen(BufferBuilder b, Matrix4f m, Vec3d cam) {
			this.b = b;
			this.m = m;
			this.cam = cam;
		}

		void v(Vec3d p, int argb) {
			if (!begun) {
				begun = true;
				b.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
			}
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

	private static float curTd = 0f;

	private static int pb(int b) {
		return Math.max(0, Math.min(3, b));
	}

	/** How strong the red-black Berserk aura is right now (0 = none). */
	private static float auraStrength() {
		if (berserkTicks > 0) {
			return berserkTicks < 30 ? berserkTicks / 30f : 1f;
		}
		if (introAt > 0) {
			float t = (System.nanoTime() - introAt) / 1_000_000_000f;
			if (t < 3.6f) {
				return clamp01((t - 1.0f) / 0.9f);
			}
		}
		if (OrvWindowClient.slayer && OrvWindowClient.rage >= 70f) {
			return 0.3f;
		}
		return 0f;
	}

	public static void render(WorldRenderContext ctx) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null) {
			return;
		}
		float auraK = auraStrength();
		if (LIST.isEmpty() && auraK <= 0f && !LightClient.any()) {
			return;
		}
		MatrixStack ms = ctx.matrixStack();
		Matrix4f m = ms.peek().getPositionMatrix();
		Vec3d cam = ctx.camera().getPos();
		float td = ctx.tickDelta();
		curTd = td;

		RenderSystem.enableBlend();
		RenderSystem.setShader(GameRenderer::getPositionColorProgram);
		RenderSystem.disableCull();
		RenderSystem.depthMask(false);
		RenderSystem.enableDepthTest();

		long now = System.nanoTime();

		// pass 1: smoke, scorch marks, silhouettes: normal blending (black cannot be made with additive light)
		RenderSystem.defaultBlendFunc();
		Pen dark = new Pen(Tessellator.getInstance().getBuffer(), m, cam);
		for (Effect e : LIST) {
			float age = (now - e.start) / 1_000_000_000f;
			if (age >= e.life) {
				continue;
			}
			float tt = age / e.life;
			switch (e.type) {
				case FxNet.SLASH -> darkSlash(dark, e, tt);
				case FxNet.IMPACT -> darkImpact(dark, e, tt);
				case FxNet.DASH -> darkDash(dark, e, tt);
				case FxNet.SHOCK -> darkShock(dark, e, tt);
				case FxNet.BERSERK_START -> darkBurst(dark, e, tt);
				case FxNet.CHARGE -> darkCharge(dark, e, tt);
				case FxNet.LEAP -> drawLeap(dark, e, age, true);
				default -> {
				}
			}
		}
		if (auraK > 0f) {
			darkAura(dark, mc, td, auraK);
		}
		dark.flush();

		// pass 2: glowing light, additive
		RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE);
		Pen pen = new Pen(Tessellator.getInstance().getBuffer(), m, cam);
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
				case FxNet.CHARGE -> drawCharge(pen, e, tt);
				case FxNet.LEAP -> drawLeap(pen, e, age, false);
				case FxNet.LB_CIRCLE -> LightClient.drawCircle(pen, e.pos, e.dir, e.a, e.b, tt);
				case FxNet.PROJ -> LightClient.drawProj(pen, e.pos, e.dir, age, e.a);
				case FxNet.HEAL_PULSE -> LightClient.drawPulse(pen, e.pos, e.a, e.b, tt);
				case FxNet.ORB_SHOT -> LightClient.drawShot(pen, e.pos, e.dir, tt);
				case FxNet.HALO_BURST -> LightClient.drawBurst(pen, e.pos, e.b, tt);
				default -> {
				}
			}
		}
		if (auraK > 0f) {
			drawAura(pen, mc, td, auraK);
		}
		LightClient.render(pen, mc, td);
		pen.flush();

		RenderSystem.defaultBlendFunc();
		RenderSystem.enableCull();
		RenderSystem.depthMask(true);
		RenderSystem.enableDepthTest();
		RenderSystem.disableBlend();
	}

	// ---- slash: a crescent of blood-red light on a black ink stroke, with echoes and a white cut line

	private static final class SG {
		int[] pal;
		Vec3d f;
		Vec3d p;
		Vec3d q;
		Vec3d origin;
		double radius;
		double sweep;
		double scale;
		double head;
		double tail;
		float fade;
	}

	private static SG sg(Effect e, float tt) {
		SG g = new SG();
		g.pal = PALETTE[pb(e.b)];
		g.f = e.dir.lengthSquared() < 1e-6 ? new Vec3d(0, 0, 1) : e.dir.normalize();
		Vec3d r = Math.abs(g.f.y) > 0.95 ? new Vec3d(1, 0, 0) : new Vec3d(-g.f.z, 0, g.f.x).normalize();
		Vec3d u = r.crossProduct(g.f).normalize();
		double roll = Math.toRadians(e.a);
		g.p = r.multiply(Math.cos(roll)).add(u.multiply(Math.sin(roll)));
		g.q = g.f.crossProduct(g.p).normalize();
		g.scale = e.b == 2 ? 1.35 : (e.b == 3 ? 0.9 : 1.0);
		g.radius = e.b == 2 ? 2.9 : (e.b == 1 ? 2.45 : (e.b == 3 ? 2.0 : 2.2));
		g.sweep = Math.toRadians(e.b == 2 ? 210 : (e.b == 3 ? 125 : 150));
		g.origin = e.pos.subtract(g.f.multiply(0.5));
		g.head = easeOut(tt / 0.40);
		g.tail = Math.pow(Math.max(0, (tt - 0.08) / 0.92), 1.25);
		g.fade = 1f - (float) Math.pow(Math.max(0, (tt - 0.35) / 0.65), 1.4);
		return g;
	}

	private static void strip(Pen pen, SG g, double radius, double tail, double head, double width, int rgb, float alpha, int n) {
		for (int i = 0; i < n; i++) {
			double s0 = i / (double) n;
			double s1 = (i + 1) / (double) n;
			Vec3d[] a = arcPoints(g.origin, g.f, g.p, radius, g.sweep, tail + (head - tail) * s0, width * taper(s0));
			Vec3d[] b = arcPoints(g.origin, g.f, g.p, radius, g.sweep, tail + (head - tail) * s1, width * taper(s1));
			float a0 = (float) Math.pow(s0, 1.2) * g.fade * alpha;
			float a1 = (float) Math.pow(s1, 1.2) * g.fade * alpha;
			int c0 = argb(rgb, a0);
			int c1 = argb(rgb, a1);
			pen.quad(a[0], a[1], b[1], b[0], c0, c0, c1, c1);
		}
	}

	private static void darkSlash(Pen dark, Effect e, float tt) {
		SG g = sg(e, tt);
		if (g.head - g.tail < 0.015) {
			return;
		}
		strip(dark, g, g.radius, g.tail, g.head, 1.25 * g.scale, 0x14000C, 0.62f, 34);
		strip(dark, g, g.radius, g.tail, g.head, 0.7 * g.scale, 0x0A0006, 0.5f, 34);
	}

	private static void drawSlash(Pen pen, Effect e, float tt, float age) {
		SG g = sg(e, tt);
		int[] pal = g.pal;
		if (g.head - g.tail >= 0.015) {
			double w = g.scale;
			strip(pen, g, g.radius, g.tail, g.head, 0.95 * w, pal[0], 0.28f, 34);
			strip(pen, g, g.radius, g.tail, g.head, 0.50 * w, mix(pal[0], pal[1], 0.5f), 0.78f, 34);
			strip(pen, g, g.radius, g.tail, g.head, 0.22 * w, pal[1], 0.92f, 34);
			strip(pen, g, g.radius, g.tail, g.head, 0.07 * w, pal[2], 1.0f, 34);
			// thin echoes that lag behind the blade
			double h2 = easeOut((tt - 0.035) / 0.40);
			double t2 = Math.pow(Math.max(0, (tt - 0.12) / 0.88), 1.25);
			if (h2 - t2 > 0.02) {
				strip(pen, g, g.radius - 0.38 * w, t2, h2, 0.05, pal[1], 0.55f, 24);
				strip(pen, g, g.radius + 0.34 * w, t2, h2, 0.04, pal[0], 0.5f, 24);
			}
			// bright head
			Vec3d tip = arcPoints(g.origin, g.f, g.p, g.radius, g.sweep, g.head, 0)[0];
			pen.glow(tip, 0.62 * w, pal[1], 0.9f * g.fade * (float) (1 - tt));
			pen.glow(tip, 0.22 * w, pal[2], 1f * g.fade);
			// sparks thrown off the head
			long seed = e.seed;
			Vec3d tang = tip.subtract(arcPoints(g.origin, g.f, g.p, g.radius, g.sweep, Math.max(0, g.head - 0.05), 0)[0]);
			tang = tang.lengthSquared() < 1e-8 ? g.f : tang.normalize();
			for (int i = 0; i < 11; i++) {
				float k = rnd(seed, i);
				float life = 0.25f + 0.2f * rnd(seed, i + 20);
				float t2s = (tt * e.life - 0.35f * k) / life;
				if (t2s < 0 || t2s > 1) {
					continue;
				}
				Vec3d v = tang.multiply(1.5 + 2.8 * rnd(seed, i + 40)).add(rndDir(seed, i + 60).multiply(1.7));
				Vec3d sp = tip.add(v.multiply(t2s * life)).add(0, -2.2 * t2s * life * t2s * life, 0);
				Vec3d sp2 = sp.subtract(v.multiply(0.05));
				pen.streak(sp2, sp, 0.032, argb(pal[2], 0f), argb(mix(pal[2], pal[0], t2s), (1 - t2s) * g.fade));
			}
		}
		// the cut: a straight white line appears across the target right after the arc passes
		if (e.b >= 1 && tt > 0.28f && tt < 0.7f) {
			float k = (tt - 0.28f) / 0.42f;
			double len = g.radius * (e.b == 2 ? 1.1 : 0.85) * easeOut(Math.min(1.0, k * 3.0));
			float a = (1f - k) * (1f - k);
			Vec3d c = g.origin.add(g.f.multiply(g.radius * 0.8));
			cutLine(pen, c, g.p, len, 0.09 * (1 - k) + 0.01, pal, a);
			if (e.b == 2) {
				Vec3d d2 = g.p.multiply(Math.cos(1.05)).add(g.q.multiply(Math.sin(1.05)));
				cutLine(pen, c, d2, len * 0.85, 0.07 * (1 - k) + 0.01, pal, a * 0.9f);
			}
		}
	}

	private static void cutLine(Pen pen, Vec3d c, Vec3d dir, double len, double width, int[] pal, float a) {
		Vec3d p0 = c.subtract(dir.multiply(len));
		Vec3d p1 = c.add(dir.multiply(len));
		pen.streak(p0, c, width * 3.0, argb(pal[0], 0f), argb(pal[1], 0.5f * a));
		pen.streak(c, p1, width * 3.0, argb(pal[1], 0.5f * a), argb(pal[0], 0f));
		pen.streak(p0, c, width, argb(pal[2], 0f), argb(pal[2], a));
		pen.streak(c, p1, width, argb(pal[2], a), argb(pal[2], 0f));
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

	// ---- impact: flash, starburst, a ring in the screen plane, sparks and a puff of black smoke

	private static void darkImpact(Pen dark, Effect e, float tt) {
		float size = Math.max(0.6f, e.a);
		if (tt < 0.04f) {
			return;
		}
		for (int i = 0; i < 4; i++) {
			Vec3d d = rndDir(e.seed, i + 100).multiply(0.5 * size * tt);
			Vec3d c = e.pos.add(d).add(0, 0.5 * tt * size, 0);
			dark.glow(c, (0.3 + 0.55 * tt) * size, 0x1A050A, 0.5f * (float) Math.pow(1 - tt, 1.3));
		}
	}

	private static void drawImpact(Pen pen, Effect e, float tt, float age) {
		int[] pal = PALETTE[pb(e.b)];
		float size = Math.max(0.6f, e.a);
		float fl = 1f - clamp01(tt / 0.3f);
		pen.glow(e.pos, 1.1 * size, pal[0], 0.75f * fl);
		pen.glow(e.pos, 0.55 * size, pal[1], 0.95f * fl);
		pen.glow(e.pos, 0.22 * size, pal[2], fl);
		pen.face(e.pos);
		if (tt < 0.45f) {
			float k = tt / 0.45f;
			double grow = easeOut(k * 1.6);
			int spikes = 8 + Math.round(size * 2f);
			for (int i = 0; i < spikes; i++) {
				double ang = i * Math.PI * 2 / spikes + rnd(e.seed, i) * 0.5;
				double len = (0.5 + 1.1 * rnd(e.seed, i + 10)) * size * grow * (1 - k * 0.6);
				Vec3d d = pen.camRight.multiply(Math.cos(ang)).add(pen.camUp.multiply(Math.sin(ang)));
				pen.streak(e.pos.add(d.multiply(0.12 * size)), e.pos.add(d.multiply(0.12 * size + len)), 0.075 * size * (1 - k),
						argb(pal[2], 0.95f * (1 - k)), argb(pal[1], 0f));
			}
		}
		if (tt < 0.5f) {
			float k = tt / 0.5f;
			pen.ringIn(e.pos, pen.camRight, pen.camUp, 0.25 * size + 1.0 * size * easeOut(k), 0.12 * size * (1 - k) + 0.02, pal[1],
					0.8f * (1 - k));
		}
		Vec3d base = e.dir.lengthSquared() < 1e-6 ? new Vec3d(0, 1, 0) : e.dir.normalize();
		int n = Math.round(14 + size * 6);
		for (int i = 0; i < n; i++) {
			float life = 0.3f + 0.22f * rnd(e.seed, i + 30);
			float t2 = age / life;
			if (t2 >= 1f) {
				continue;
			}
			Vec3d dirv = rndDir(e.seed, i + 50).add(base.multiply(0.6)).normalize();
			double speed = (2.5 + 5.5 * rnd(e.seed, i + 80)) * (0.8 + 0.2 * size);
			Vec3d v = dirv.multiply(speed);
			double ag = Math.max(0, age - 0.04);
			Vec3d sp = e.pos.add(v.multiply(age)).add(0, -5.0 * age * age, 0);
			Vec3d prev = e.pos.add(v.multiply(ag)).add(0, -5.0 * ag * ag, 0);
			pen.streak(prev, sp, 0.035, argb(pal[1], 0f), argb(mix(pal[2], pal[0], t2), (1 - t2) * 0.95f));
		}
	}

	// ---- dash: speed lines, black-red after-images, rings at both ends

	private static void darkDash(Pen dark, Effect e, float tt) {
		Vec3d a = e.pos;
		for (int i = 0; i < 5; i++) {
			double k = i / 4.0;
			float f2 = clamp01(1f - tt * 1.5f - (float) (1 - k) * 0.3f) * 0.55f;
			if (f2 <= 0f) {
				continue;
			}
			Vec3d c = a.add(e.dir.multiply(k));
			dark.face(c);
			Vec3d r = dark.camRight.multiply(0.3);
			Vec3d lo = c.add(0, -0.95, 0);
			Vec3d hi = c.add(0, 0.9, 0);
			int col = argb(0x18020A, f2);
			dark.quad(lo.subtract(r), lo.add(r), hi.add(r), hi.subtract(r), col, col, col, col);
		}
	}

	private static void drawDash(Pen pen, Effect e, float tt) {
		Vec3d a = e.pos;
		Vec3d b = e.pos.add(e.dir);
		float fade = (float) Math.pow(1 - tt, 1.3);
		int[] pal = PALETTE[e.b == 1 ? 2 : 0];
		pen.face(a.add(e.dir.multiply(0.5)));
		for (int i = 0; i < 7; i++) {
			double ang = rnd(e.seed, i) * Math.PI * 2;
			double off = 0.15 + 0.55 * rnd(e.seed, i + 9);
			Vec3d o = pen.camRight.multiply(Math.cos(ang) * off).add(pen.camUp.multiply(Math.sin(ang) * off));
			double s0 = 0.1 * rnd(e.seed, i + 20);
			pen.streak(a.add(o).add(e.dir.multiply(s0)), b.add(o), 0.05, argb(pal[1], 0f), argb(pal[2], 0.8f * fade));
		}
		pen.streak(a, b, 0.55, argb(pal[0], 0f), argb(pal[1], 0.5f * fade));
		pen.streak(a, b, 0.16, argb(pal[2], 0f), argb(pal[2], 0.9f * fade));
		for (int i = 0; i < 5; i++) {
			double k = i / 4.0;
			float f2 = clamp01(1f - tt * 1.5f - (float) (1 - k) * 0.3f) * 0.5f;
			if (f2 <= 0f) {
				continue;
			}
			Vec3d c = a.add(e.dir.multiply(k));
			pen.face(c);
			Vec3d r = pen.camRight.multiply(0.3);
			Vec3d lo = c.add(0, -0.95, 0);
			Vec3d hi = c.add(0, 0.9, 0);
			int mid = argb(pal[0], f2 * 0.7f);
			int edge = argb(pal[1], 0f);
			pen.quad(lo.subtract(r), lo.add(r), c.add(r), c.subtract(r), edge, edge, mid, mid);
			pen.quad(c.subtract(r), c.add(r), hi.add(r), hi.subtract(r), mid, mid, edge, edge);
		}
		pen.glow(b, 0.9, pal[1], 0.7f * fade);
		pen.ring(b.add(0, -0.95, 0), 0.3 + 1.5 * easeOut(tt), 0.1, pal[1], 0.7f * fade);
		pen.ring(a.add(0, -0.95, 0), 0.3 + 1.0 * easeOut(tt), 0.08, pal[0], 0.5f * fade);
	}

	// ---- shock: rings, cracks that split the ground, rising embers

	private static void darkShock(Pen dark, Effect e, float tt) {
		double R = Math.max(1.0, e.a);
		if (R < 4.0 && e.b != 2) {
			return;
		}
		float a = 0.62f * (1f - clamp01((tt - 0.5f) / 0.5f));
		dark.disc(e.pos.add(0, 0.03, 0), R * 0.55 * easeOut(Math.min(1.0, tt * 3.0)), 0x100208, a);
	}

	private static void drawShock(Pen pen, Effect e, float tt) {
		double R = Math.max(1.0, e.a);
		boolean heavy = e.b == 2;
		boolean white = e.b == 1;
		int core = heavy ? 0xFFB070 : (white ? 0xFFF0D0 : 0xFF6040);
		int glowc = heavy ? 0xFF1810 : (white ? 0xFFB050 : 0xC01810);
		double r = R * easeOut(tt);
		float fade = (float) Math.pow(1 - tt, 1.2);
		pen.ring(e.pos, r, R * 0.14 * (1 - tt) + 0.08, core, 0.95f * fade);
		pen.ring(e.pos, r * 0.92, R * 0.3 * (1 - tt) + 0.1, glowc, 0.5f * fade);
		if (heavy) {
			pen.ring(e.pos, r * 0.55, R * 0.12 * (1 - tt) + 0.06, core, 0.6f * fade);
		}
		if (tt < 0.5f) {
			pen.glow(e.pos.add(0, 0.2, 0), R * 0.35, core, 0.8f * (1 - tt * 2));
		}
		if (heavy || R >= 5.0) {
			int n = heavy ? 14 : 9;
			double grow = easeOut(Math.min(1.0, tt * 2.2));
			float ca = 0.95f * (1f - clamp01((tt - 0.55f) / 0.45f));
			for (int i = 0; i < n; i++) {
				double ang = i * Math.PI * 2 / n + (rnd(e.seed, i) - 0.5) * 0.45;
				Vec3d dir = new Vec3d(Math.cos(ang), 0, Math.sin(ang));
				Vec3d side = new Vec3d(-dir.z, 0, dir.x);
				double len = R * (0.55 + 0.45 * rnd(e.seed, i + 30)) * grow;
				int segs = 7;
				Vec3d prev = e.pos.add(0, 0.06, 0).add(dir.multiply(0.3));
				for (int k = 1; k <= segs; k++) {
					double dist = 0.3 + len * k / segs;
					double jit = (rnd(e.seed, i * 11 + k) - 0.5) * 0.55 * (0.4 + dist / R);
					Vec3d cur = e.pos.add(0, 0.06, 0).add(dir.multiply(dist)).add(side.multiply(jit));
					float s = k / (float) segs;
					double w = 0.2 * (1 - s * 0.7) * (1 - tt * 0.5);
					pen.streak(prev, cur, w * 2.4, argb(glowc, 0.35f * ca), argb(glowc, 0.1f * ca));
					pen.streak(prev, cur, w, argb(mix(0xFFFFFF, core, s), ca), argb(mix(0xFFFFFF, core, s + 0.15f), ca * 0.8f));
					prev = cur;
				}
			}
			if (tt < 0.7f) {
				pen.glow(e.pos.add(0, 0.15, 0), R * 0.3, core, 0.7f * (1 - tt / 0.7f));
			}
			// embers rising from the cracks
			float age = tt * e.life;
			for (int i = 0; i < 16; i++) {
				float rr = rnd(e.seed, i + 200) * (float) R * 0.6f;
				double ang = rnd(e.seed, i + 220) * Math.PI * 2;
				float life = 0.5f + 0.5f * rnd(e.seed, i + 240);
				float k = age / life;
				if (k >= 1f) {
					continue;
				}
				Vec3d p0 = e.pos.add(Math.cos(ang) * rr, 0.1 + k * (1.5 + 2.0 * rnd(e.seed, i + 260)), Math.sin(ang) * rr);
				pen.glow(p0, 0.07 * (1 - k) + 0.02, mix(0xFFC060, 0xC01808, k), 0.9f * (1 - k));
			}
		}
	}

	private static void drawGrab(Pen pen, Effect e, float tt) {
		float fade = (float) Math.pow(1 - tt, 1.4);
		pen.glow(e.pos, 1.0 + 2.5 * easeOut(tt * 2), 0xFFE0A0, 0.9f * fade);
		pen.glow(e.pos, 0.4, 0xFFFFFF, fade);
		pen.pillar(e.pos.add(0, -1.0, 0), 11.0, 0.55 * (1 - tt * 0.5), 0xFFB060, 0.85f * fade);
		pen.ring(e.pos.add(0, -0.9, 0), 3.0 * (1 - easeOut(tt)) + 0.4, 0.12, 0xFFD090, 0.8f * fade);
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

	// ---- Berserk: charge-up (gathering embers), then the roar (blast, flame spikes, black pillar)

	private static void darkCharge(Pen dark, Effect e, float tt) {
		Vec3d c = e.pos;
		dark.disc(c.add(0, 0.03, 0), 3.2 * easeOut(Math.min(1.0, tt * 1.3)), 0x100208, 0.6f * Math.min(1f, tt * 2f));
		double t = tt * e.life;
		for (int i = 0; i < 6; i++) {
			Vec3d[] pts = new Vec3d[6];
			double a0 = rnd(e.seed, i) * Math.PI * 2;
			for (int j = 0; j < 6; j++) {
				double s = j / 5.0;
				double ang = a0 + t * 2.2 + s * 2.0;
				double rad = 1.8 - 1.0 * s;
				pts[j] = c.add(Math.cos(ang) * rad, 0.1 + s * 2.2 * (0.5 + rnd(e.seed, i + 30)), Math.sin(ang) * rad);
			}
			dark.ribbon(pts, 0.2, 0.7, 0x140208, 0.0f, 0.4f * Math.min(1f, tt * 2f));
		}
	}

	private static void drawCharge(Pen pen, Effect e, float tt) {
		Vec3d c = e.pos;
		float inA = Math.min(1f, tt * 3f);
		float outA = 1f - clamp01((tt - 0.85f) / 0.15f);
		float al = inA * outA;
		for (int i = 0; i < 28; i++) {
			double ang0 = rnd(e.seed, i) * Math.PI * 2;
			double r0 = 3.2 + 3.2 * rnd(e.seed, i + 30);
			float s = clamp01((tt * 1.15f - rnd(e.seed, i + 60) * 0.3f) / 0.85f);
			if (s <= 0f || s >= 1f) {
				continue;
			}
			double rise = 1.8 * rnd(e.seed, i + 90);
			Vec3d cur = embPos(c, ang0, r0, rise, s);
			Vec3d prev = embPos(c, ang0, r0, rise, Math.max(0f, s - 0.07f));
			float a = (float) Math.sin(Math.PI * s) * outA;
			pen.streak(prev, cur, 0.06, argb(0xFF7030, 0f), argb(mix(0xFFC070, 0xFF2010, s), 0.95f * a));
			pen.glow(cur, 0.07, 0xFFB060, 0.8f * a);
		}
		double rg = easeOut(Math.min(1.0, tt * 1.6));
		pen.ring(c.add(0, 0.05, 0), 2.4 * rg, 0.09, 0xFF3020, 0.85f * al);
		pen.ring(c.add(0, 0.05, 0), 1.6 * rg, 0.06, 0xFF8040, 0.65f * al);
		double spin = tt * e.life * 2.0;
		for (int i = 0; i < 6; i++) {
			double ang = spin + i * Math.PI / 3;
			Vec3d dir = new Vec3d(Math.cos(ang), 0, Math.sin(ang));
			Vec3d side = new Vec3d(-dir.z, 0, dir.x);
			pen.petal(c.add(0, 0.06, 0).add(dir.multiply(1.6 * rg)), dir, side, 0.8 * rg, 0.35, 0xFF4020, 0.8f * al);
		}
		pen.glow(c.add(0, 1.0, 0), 0.3 + 1.2 * tt * tt, 0xFF3010, (0.2f + 0.5f * tt) * outA);
		pen.glow(c.add(0, 1.0, 0), 0.1 + 0.4 * tt * tt, 0xFFD090, 0.5f * tt * outA);
	}

	private static Vec3d embPos(Vec3d c, double ang0, double r0, double rise, float s) {
		double rad = r0 * Math.pow(1 - s, 1.3) + 0.25;
		double ang = ang0 + s * 4.0;
		return c.add(Math.cos(ang) * rad, 0.1 + s * rise, Math.sin(ang) * rad);
	}

	private static void darkBurst(Pen dark, Effect e, float tt) {
		Vec3d g = e.pos.add(0, 0.04, 0);
		dark.disc(g, 5.5 * easeOut(Math.min(1.0, tt * 2.5)), 0x100208, 0.6f * (1f - clamp01((tt - 0.5f) / 0.5f)));
		dark.pillar(g, 10.0, 1.2 * (1 - tt * 0.5), 0x0C0206, 0.55f * (1 - tt));
	}

	private static void drawBerserkBurst(Pen pen, Effect e, float tt) {
		float fade = (float) Math.pow(1 - tt, 1.2);
		double r = 10.0 * easeOut(tt);
		Vec3d g = e.pos.add(0, 0.05, 0);
		pen.ring(g, r, 0.5 * (1 - tt) + 0.1, 0xFF5030, 0.9f * fade);
		pen.ring(g, r * 0.6, 0.9 * (1 - tt) + 0.1, 0xC01810, 0.6f * fade);
		pen.ring(g, r * 0.3, 0.6 * (1 - tt) + 0.1, 0xFF9050, 0.5f * fade);
		double grow = easeOut(Math.min(1.0, tt * 1.8));
		for (int i = 0; i < 18; i++) {
			double ang = i * Math.PI * 2 / 18 + rnd(e.seed, i) * 0.3;
			Vec3d dir = new Vec3d(Math.cos(ang), 0, Math.sin(ang));
			Vec3d side = new Vec3d(-dir.z, 0, dir.x);
			double len = (2.0 + 3.5 * rnd(e.seed, i + 20)) * grow;
			pen.petal(g.add(dir.multiply(0.8)), dir, side, len, 0.9, mix(0xFF4020, 0xFFA040, rnd(e.seed, i + 40)), 0.8f * fade);
		}
		pen.pillar(g, 12.0, 0.9 * (1 - tt * 0.6), 0xFF3010, 0.8f * fade);
		pen.pillar(g, 7.0, 0.35 * (1 - tt * 0.6), 0xFFC070, 0.7f * fade);
		pen.glow(e.pos.add(0, 1.0, 0), 3.2 * easeOut(tt * 1.5), 0xFF4020, 0.7f * fade);
		float age = tt * e.life;
		for (int i = 0; i < 34; i++) {
			double ang = rnd(e.seed, i + 100) * Math.PI * 2;
			double rr = 0.4 + 3.0 * rnd(e.seed, i + 130);
			float life = 0.7f + 0.8f * rnd(e.seed, i + 160);
			float k = age / life;
			if (k >= 1f) {
				continue;
			}
			Vec3d p0 = e.pos.add(Math.cos(ang) * rr, 0.1 + k * (2.5 + 4.0 * rnd(e.seed, i + 190)), Math.sin(ang) * rr);
			pen.glow(p0, 0.08 * (1 - k) + 0.02, mix(0xFFC060, 0xB01008, k), 0.9f * (1 - k));
		}
	}

	// ---- leap trail that follows the flying Slayer

	private static void drawLeap(Pen pen, Effect e, float age, boolean dark) {
		MinecraftClient mc = MinecraftClient.getInstance();
		net.minecraft.entity.Entity ent = mc.world == null ? null : mc.world.getEntityById(e.b);
		if (ent == null) {
			return;
		}
		if (!dark && age <= e.a + 0.05f) {
			Vec3d np = ent.getLerpedPos(curTd).add(0, 1.0, 0);
			if (e.trail.isEmpty() || e.trail.get(e.trail.size() - 1).squaredDistanceTo(np) > 0.03) {
				e.trail.add(np);
				if (e.trail.size() > 60) {
					e.trail.remove(0);
				}
			}
		}
		if (e.trail.size() < 2) {
			return;
		}
		float g = age <= e.a ? 1f : 1f - clamp01((age - e.a) / 0.5f);
		Vec3d[] pts = e.trail.toArray(new Vec3d[0]);
		if (dark) {
			pen.ribbon(pts, 0.0, 1.0, 0x140208, 0f, 0.5f * g);
			return;
		}
		pen.ribbon(pts, 0.0, 0.8, 0xD01818, 0f, 0.5f * g);
		pen.ribbon(pts, 0.0, 0.4, 0xFF6A30, 0f, 0.85f * g);
		pen.ribbon(pts, 0.0, 0.12, 0xFFF4E0, 0f, g);
		Vec3d head = pts[pts.length - 1];
		pen.glow(head, 0.8, 0xFF4020, 0.5f * g);
		for (int i = 0; i < 8; i++) {
			int idx = (int) (rnd(e.seed, i) * (pts.length - 1));
			float k = (idx / (float) pts.length);
			Vec3d p0 = pts[idx].add(rndDir(e.seed, i + 10).multiply(0.35 * (1 - k)));
			pen.glow(p0, 0.06, 0xFFB060, 0.8f * k * g);
		}
	}

	// ---- Berserk aura around the local player: black smoke and tongues of fire

	private static void darkAura(Pen dark, MinecraftClient mc, float td, float k) {
		Vec3d base = mc.player.getLerpedPos(td);
		double t = System.nanoTime() / 1_000_000_000.0;
		float vis = mc.options.getPerspective().isFirstPerson() ? 0.5f : 1f;
		dark.disc(base.add(0, 0.03, 0), 2.2, 0x10020A, 0.55f * k);
		int wisps = k > 0.5f ? 14 : 5;
		for (int i = 0; i < wisps; i++) {
			float s1 = rnd(11, i);
			float s2 = rnd(12, i);
			double phase = (t * (0.22 + 0.25 * s2) + s1) % 1.0;
			Vec3d[] pts = new Vec3d[6];
			for (int j = 0; j < 6; j++) {
				double ph = Math.max(0, phase - 0.28 + j * 0.056);
				double ang = s1 * Math.PI * 2 + t * 0.8 + ph * 2.6;
				double rad = 0.7 + 0.55 * ph;
				pts[j] = base.add(Math.cos(ang) * rad, 0.2 + ph * 3.2, Math.sin(ang) * rad);
			}
			float a = (float) Math.sin(Math.PI * phase) * 0.45f * k * vis;
			dark.ribbon(pts, 0.25, 0.95, 0x140208, a * 0.3f, a);
		}
	}

	private static void drawAura(Pen pen, MinecraftClient mc, float td, float k) {
		Vec3d base = mc.player.getLerpedPos(td);
		double t = System.nanoTime() / 1_000_000_000.0;
		float vis = mc.options.getPerspective().isFirstPerson() ? 0.55f : 1f;
		int tongues = k > 0.5f ? 22 : 8;
		for (int i = 0; i < tongues; i++) {
			float s1 = rnd(21, i);
			float s2 = rnd(22, i);
			float s3 = rnd(23, i);
			double ang = i * Math.PI * 2 / tongues + t * 0.35 + s1 * 0.4;
			double rad = 0.55 + 0.4 * s2;
			double flick = 0.75 + 0.25 * Math.sin(t * (5.0 + 4.0 * s3) + i);
			double height = (1.3 + 1.5 * s3) * flick * (0.45 + 0.55 * k);
			int segs = 9;
			Vec3d prev = null;
			for (int j = 0; j <= segs; j++) {
				double s = j / (double) segs;
				double lean = 0.3 * Math.pow(s, 1.5);
				double sway = Math.sin(t * 6.0 + i * 1.7 + s * 5.0) * 0.13 * s;
				double rr = rad + lean;
				Vec3d cur = base.add(Math.cos(ang) * rr + sway * -Math.sin(ang), 0.05 + s * height,
						Math.sin(ang) * rr + sway * Math.cos(ang));
				if (prev != null) {
					float s0 = (j - 1) / (float) segs;
					int c0 = mix(0xFFD070, 0xB00C10, s0);
					int c1 = mix(0xFFD070, 0xB00C10, (float) s);
					float a0 = (float) Math.pow(1 - s0, 0.8) * 0.85f * vis;
					float a1 = (float) Math.pow(1 - s, 0.8) * 0.85f * vis;
					double w0 = 0.3 * Math.pow(1 - s0, 0.7) * (0.6 + 0.6 * s2);
					double w1 = 0.3 * Math.pow(1 - s, 0.7) * (0.6 + 0.6 * s2);
					pen.streak(prev, cur, (w0 + w1) * 0.9, argb(c0, a0 * 0.35f), argb(c1, a1 * 0.35f));
					pen.streak(prev, cur, (w0 + w1) * 0.4, argb(c0, a0), argb(c1, a1));
				}
				prev = cur;
			}
		}
		// heat glow around the body
		pen.glow(base.add(0, 1.0, 0), 1.4, 0xFF2808, (0.16f + 0.05f * (float) Math.sin(t * 9)) * k * vis);
		pen.glow(base.add(0, 0.2, 0), 1.6, 0xFF4010, 0.28f * k * vis);
		// embers
		int embers = k > 0.5f ? 26 : 8;
		for (int i = 0; i < embers; i++) {
			float s1 = rnd(31, i);
			float s2 = rnd(32, i);
			float s3 = rnd(33, i);
			double phase = (t * (0.4 + 0.5 * s2) + s1) % 1.0;
			double ang = s1 * Math.PI * 2 + t * (0.6 + s3);
			double rad = 0.5 + 0.7 * s3;
			Vec3d p = base.add(Math.cos(ang) * rad, 0.1 + phase * 3.2, Math.sin(ang) * rad);
			float a = (float) Math.sin(Math.PI * phase) * k * vis;
			int col = mix(0xFFB050, 0xC01808, (float) phase);
			pen.glow(p, 0.05 + 0.05 * (1 - phase), col, 0.9f * a);
			if (i % 3 == 0) {
				pen.streak(p, p.add(0, 0.4 + 0.3 * s2, 0), 0.025, argb(col, 0.8f * a), argb(col, 0f));
			}
		}
		// sigil on the ground
		float pulse = 0.55f + 0.25f * (float) Math.sin(t * 7.0);
		Vec3d g = base.add(0, 0.04, 0);
		pen.ring(g, 1.3 + 0.08 * Math.sin(t * 5), 0.14, 0xFF3010, pulse * k);
		pen.ring(g, 2.0 + 0.12 * Math.sin(t * 3 + 1), 0.2, 0xC01810, pulse * 0.7f * k);
		int spikes = 8;
		for (int i = 0; i < spikes; i++) {
			double ang = -t * 0.7 + i * Math.PI * 2 / spikes;
			Vec3d dir = new Vec3d(Math.cos(ang), 0, Math.sin(ang));
			Vec3d side = new Vec3d(-dir.z, 0, dir.x);
			pen.petal(g.add(dir.multiply(1.35)), dir, side, 0.8 + 0.2 * Math.sin(t * 6 + i), 0.3, 0xFF4020, 0.75f * k);
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
			vignette(ctx, w, h, 0x000000, k * 1.1f);
			vignette(ctx, w, h, 0xC00800, k);
		} else if (OrvWindowClient.slayer && OrvWindowClient.rage >= 85f) {
			vignette(ctx, w, h, 0xA00800, 0.10f + 0.05f * (float) Math.sin(t * 4.0));
		}

		// Berserk cut-scene: the screen darkens and reddens while the Slayer roars
		if (introAt > 0 && berserkTicks <= 0) {
			float it = (System.nanoTime() - introAt) / 1_000_000_000f;
			if (it < 3.2f) {
				float k = clamp01((it - 0.2f) / 1.0f) * (1f - clamp01((it - 2.7f) / 0.5f));
				vignette(ctx, w, h, 0x000000, 0.5f * k);
				vignette(ctx, w, h, 0xC00800, 0.35f * k * (0.8f + 0.2f * (float) Math.sin(t * 14.0)));
			}
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
