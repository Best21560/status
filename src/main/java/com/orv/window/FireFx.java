package com.orv.window;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * Realistic fire: tongues of flame drawn as upright camera-facing sprites. The sprites are generated here with
 * fractal noise (a teardrop shape torn by turbulence, coloured by heat: white-yellow core, orange body, deep red edge,
 * transparent tip), so no texture files are needed. Everything is additive, so overlapping flames really add up
 * like fire does. Each tongue lives a short cycle (born low, grows, rises and sways, thins out), driven by time only.
 */
final class FireFx {
	private FireFx() {
	}

	private static final int TW = 64;
	private static final int TH = 128;
	private static final int VARIANTS = 4;
	private static final Identifier ID = new Identifier("orvwindow", "dynamic/flame");
	private static boolean made = false;
	private static boolean failed = false;

	// ---- noise
	private static float hash(int x, int y, int s) {
		int h = x * 374761393 + y * 668265263 + s * 1274126177;
		h = (h ^ (h >>> 13)) * 1274126177;
		h ^= h >>> 16;
		return (h & 0xFFFFFF) / (float) 0x1000000;
	}

	private static float vnoise(float x, float y, int s) {
		int xi = (int) Math.floor(x);
		int yi = (int) Math.floor(y);
		float fx = x - xi;
		float fy = y - yi;
		float ux = fx * fx * (3 - 2 * fx);
		float uy = fy * fy * (3 - 2 * fy);
		float a = hash(xi, yi, s);
		float b = hash(xi + 1, yi, s);
		float c = hash(xi, yi + 1, s);
		float d = hash(xi + 1, yi + 1, s);
		return (a + (b - a) * ux) + ((c + (d - c) * ux) - (a + (b - a) * ux)) * uy;
	}

	private static float fbm(float x, float y, int s) {
		float sum = 0;
		float amp = 0.5f;
		for (int i = 0; i < 4; i++) {
			sum += amp * vnoise(x, y, s + i * 17);
			x = x * 2.03f + 7.1f;
			y = y * 2.03f + 3.7f;
			amp *= 0.5f;
		}
		return sum;
	}

	private static float smooth(float a, float b, float x) {
		float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
		return t * t * (3 - 2 * t);
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	/** Flame colour for a heat value 0..1 as {r,g,b} 0..255. */
	private static void ramp(float h, float[] out) {
		float[][] c = {
				{22, 0, 2}, {95, 0, 6}, {170, 6, 12}, {230, 22, 22}, {255, 70, 52}, {255, 150, 128}
		};
		float[] at = {0f, 0.2f, 0.42f, 0.62f, 0.82f, 1f};
		for (int i = 0; i < c.length - 1; i++) {
			if (h <= at[i + 1] || i == c.length - 2) {
				float t = Math.max(0f, Math.min(1f, (h - at[i]) / (at[i + 1] - at[i])));
				out[0] = lerp(c[i][0], c[i + 1][0], t);
				out[1] = lerp(c[i][1], c[i + 1][1], t);
				out[2] = lerp(c[i][2], c[i + 1][2], t);
				return;
			}
		}
	}

	private static void make() {
		made = true;
		try {
			NativeImage img = new NativeImage(NativeImage.Format.RGBA, TW * VARIANTS * 2, TH, true);
			float[] rgb = new float[3];
			for (int var2 = 0; var2 < VARIANTS * 2; var2++) {
				int var = var2 % VARIANTS;
				boolean darkLayer = var2 >= VARIANTS;
				int seed = 101 + var * 53;
				for (int py = 0; py < TH; py++) {
					float v = 1f - py / (float) (TH - 1); // 0 root .. 1 tip
					for (int px = 0; px < TW; px++) {
						float u = px / (float) (TW - 1) * 2f - 1f;
						float bend = (vnoise(v * 2.2f, 3.3f, seed) - 0.5f) * 0.7f * v;
						float width = 0.82f * (float) Math.pow(1f - v, 0.75) * (float) Math.sqrt(smooth(0f, 0.12f, v));
						float n = fbm(u * 2.4f + var * 5f, v * 3.2f, seed);
						float n2 = fbm(u * 5f - 2f, v * 6f + 9f, seed + 7);
						float d = width - Math.abs(u - bend) + (n - 0.5f) * 0.9f * (0.25f + v) + (n2 - 0.5f) * 0.25f * v;
						d -= smooth(0.8f, 1f, v) * 0.4f;
						float alpha = darkLayer ? smooth(-0.12f, 0.2f, d) * 0.9f : smooth(0f, 0.16f, d);
						alpha *= 1f - smooth(0.7f, 1f, v);
						float core = Math.max(0f, Math.min(1f, d / (width * 0.95f + 0.05f)));
						float heat = (float) Math.pow(core, 0.8) * (1f - 0.62f * v) + 0.12f * (1f - v) * n;
						heat = Math.max(0f, Math.min(1f, heat + (n2 - 0.5f) * 0.2f)) * 0.9f;
						ramp(heat, rgb);
						if (darkLayer) {
							// black smoke-fire with a little deep red where it is thin
							float red = Math.max(0f, 0.5f - core) * 40f;
							rgb[0] = 6f + red;
							rgb[1] = 0f;
							rgb[2] = 2f;
						}
						int a = Math.round(alpha * 255f);
						int abgr = (a << 24) | (Math.round(rgb[2]) << 16) | (Math.round(rgb[1]) << 8) | Math.round(rgb[0]);
						img.setColor(var2 * TW + px, py, abgr);
					}
				}
			}
			NativeImageBackedTexture tex = new NativeImageBackedTexture(img);
			MinecraftClient.getInstance().getTextureManager().registerTexture(ID, tex);
			tex.setFilter(true, false);
		} catch (Throwable t) {
			failed = true;
		}
	}

	// ---- drawing

	static final class Brush {
		final BufferBuilder b;
		final Matrix4f m;
		final Vec3d cam;
		boolean begun = false;
		boolean dark = false;

		Brush(BufferBuilder b, Matrix4f m, Vec3d cam) {
			this.b = b;
			this.m = m;
			this.cam = cam;
		}

		void vert(Vec3d p, float u, float v, float alpha) {
			if (!begun) {
				begun = true;
				b.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE);
			}
			b.vertex(m, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z))
					.color(255, 255, 255, Math.max(0, Math.min(255, Math.round(alpha * 255f)))).texture(u, v).next();
		}

		/**
		 * One upright tongue: root at base, rises h, width w; lean = horizontal offset of the tip.
		 */
		void tongue(Vec3d base, double h, double w, Vec3d lean, int variant, float alpha, boolean flip) {
			if (dark) {
				variant += VARIANTS;
				h *= 1.12;
				w *= 1.2;
				alpha *= 0.85f;
			}
			if (alpha <= 0.01f || h <= 0.01) {
				return;
			}
			Vec3d view = base.add(0, h * 0.5, 0).subtract(cam);
			Vec3d r = new Vec3d(-view.z, 0, view.x);
			if (r.lengthSquared() < 1e-8) {
				return;
			}
			r = r.normalize().multiply(w * 0.5);
			float u0 = variant / (float) (VARIANTS * 2);
			float u1 = (variant + 1) / (float) (VARIANTS * 2);
			if (flip) {
				float t = u0;
				u0 = u1;
				u1 = t;
			}
			Vec3d top = base.add(0, h, 0).add(lean);
			vert(base.subtract(r), u0, 1f, alpha);
			vert(base.add(r), u1, 1f, alpha);
			vert(top.add(r), u1, 0f, alpha);
			vert(top.subtract(r), u0, 0f, alpha);
		}

		void flush() {
			if (begun) {
				begun = false;
				Tessellator.getInstance().draw();
			}
		}
	}

	private static float rnd(long seed, int i) {
		return Fx.rnd(seed, i);
	}

	/** Runs one flame-cycle: ph 0..1 and a per-cycle seed. */
	private static final class Cyc {
		float ph;
		long seed;
	}

	private static Cyc cyc(double t, double len, int i, long salt, Cyc c) {
		double x = t / len + rnd(salt, i + 5000);
		long k = (long) Math.floor(x);
		c.ph = (float) (x - k);
		c.seed = k * 7919L + i * 131L + salt;
		return c;
	}

	/** Called after the other effects: draws the Berserk fire on the local player and the burst fires of the effects. */
	static void render(MinecraftClient mc, Matrix4f m, Vec3d cam, float td, float auraK, Iterable<Fx.FireSpot> spots) {
		if (failed) {
			return;
		}
		if (!made) {
			make();
			if (failed) {
				return;
			}
		}
		boolean any = auraK > 0f;
		if (!any && !spots.iterator().hasNext()) {
			return;
		}
		RenderSystem.setShader(GameRenderer::getPositionColorTexProgram);
		RenderSystem.setShaderTexture(0, ID);
		RenderSystem.enableBlend();
		RenderSystem.disableCull();
		RenderSystem.depthMask(false);
		RenderSystem.enableDepthTest();
		Brush br = new Brush(Tessellator.getInstance().getBuffer(), m, cam);
		double t = System.nanoTime() / 1_000_000_000.0;
		for (int pass = 0; pass < 2; pass++) {
			br.dark = pass == 0;
			if (pass == 0) {
				RenderSystem.defaultBlendFunc(); // black body of the fire: normal blending
			} else {
				RenderSystem.blendFunc(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE); // glowing red
			}
			if (auraK > 0f) {
				aura(br, mc, td, auraK, t);
			}
			for (Fx.FireSpot s : spots) {
				burst(br, s, t);
			}
			br.flush();
		}
		RenderSystem.defaultBlendFunc();
		RenderSystem.depthMask(true);
	}

	private static void aura(Brush br, MinecraftClient mc, float td, float k, double t) {
		Vec3d base = mc.player.getLerpedPos(td);
		boolean first = mc.options.getPerspective().isFirstPerson();
		float vis = first ? 0.4f : 1f;
		Cyc c = new Cyc();
		int n = k > 0.5f ? 46 : 14;
		for (int i = 0; i < n; i++) {
			cyc(t, 0.55 + 0.5 * rnd(41, i), i, 41, c);
			float ph = c.ph;
			double ang = rnd(c.seed, 1) * Math.PI * 2;
			double rad = 0.2 + 0.5 * rnd(c.seed, 2);
			double y0 = 1.6 * rnd(c.seed, 3) * rnd(c.seed, 3);
			double grow = Math.sin(Math.PI * Math.min(1f, ph * 1.05f));
			double h = (0.9 + 1.1 * rnd(c.seed, 4)) * (0.35 + 0.65 * grow) * (0.5 + 0.5 * k);
			double w = (0.55 + 0.45 * rnd(c.seed, 5)) * (0.5 + 0.5 * grow);
			Vec3d p = base.add(Math.cos(ang) * rad, y0 + ph * 0.9, Math.sin(ang) * rad);
			Vec3d lean = new Vec3d(Math.cos(ang) * 0.25 * ph + Math.sin(t * 3 + i) * 0.12, 0, Math.sin(ang) * 0.25 * ph + Math.cos(t * 2.6 + i) * 0.12);
			float a = (float) Math.pow(Math.sin(Math.PI * ph), 0.7) * k * vis * 0.85f;
			br.tongue(p, h, w, lean, (int) (rnd(c.seed, 6) * VARIANTS) % VARIANTS, a, rnd(c.seed, 7) > 0.5f);
		}
		// fire licking along the ground
		int g = k > 0.5f ? 20 : 6;
		for (int i = 0; i < g; i++) {
			cyc(t, 0.5 + 0.4 * rnd(42, i), i, 42, c);
			double ang = rnd(c.seed, 1) * Math.PI * 2;
			double rad = 0.7 + 0.9 * rnd(c.seed, 2);
			double h = (0.5 + 0.7 * rnd(c.seed, 3)) * Math.sin(Math.PI * c.ph) * (0.5 + 0.5 * k);
			Vec3d p = base.add(Math.cos(ang) * rad, 0.0, Math.sin(ang) * rad);
			Vec3d lean = new Vec3d(-Math.cos(ang) * 0.2 * c.ph, 0, -Math.sin(ang) * 0.2 * c.ph);
			float a = (float) Math.sin(Math.PI * c.ph) * k * vis * 0.8f;
			br.tongue(p, h, 0.6 + 0.4 * rnd(c.seed, 4), lean, (int) (rnd(c.seed, 6) * VARIANTS) % VARIANTS, a, rnd(c.seed, 7) > 0.5f);
		}
	}

	private static void burst(Brush br, Fx.FireSpot s, double t) {
		float tt = s.tt;
		float grow = (float) Math.min(1.0, tt * 3.0) * (1f - Math.max(0f, (tt - 0.6f) / 0.4f));
		if (grow <= 0.02f) {
			return;
		}
		Cyc c = new Cyc();
		int n = (int) (s.count * 1f);
		for (int i = 0; i < n; i++) {
			cyc(t, 0.45 + 0.4 * rnd(s.seed, i), i, s.seed, c);
			double ang = rnd(c.seed, 1) * Math.PI * 2;
			double rad = s.radius * Math.sqrt(rnd(c.seed, 2));
			double h = s.height * (0.5 + 0.5 * rnd(c.seed, 3)) * Math.sin(Math.PI * c.ph) * grow;
			Vec3d p = s.pos.add(Math.cos(ang) * rad, 0.0, Math.sin(ang) * rad);
			Vec3d lean = new Vec3d(Math.cos(ang) * 0.3 * c.ph, 0, Math.sin(ang) * 0.3 * c.ph);
			float a = (float) Math.sin(Math.PI * c.ph) * grow * s.alpha;
			br.tongue(p, h, h * (0.35 + 0.2 * rnd(c.seed, 4)), lean, (int) (rnd(c.seed, 6) * VARIANTS) % VARIANTS, a, rnd(c.seed, 7) > 0.5f);
		}
	}
}
