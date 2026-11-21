package com.orv.window;

import net.minecraft.util.math.Vec3d;

import java.util.Collection;

/** Draws the Slayer's greatsword (dark steel, a gold guard, red runes) and the light trail of its swings. */
final class GreatSword {
	private GreatSword() {
	}

	/** Distance from the hand to the tip, and where the light trail starts on the blade. */
	static final double TIP = 2.3;
	static final double TRAIL_BASE = 0.7;

	private static Vec3d p(Vec3d h, Vec3d d, Vec3d w, double s, double x) {
		return h.add(d.multiply(s)).add(w.multiply(x));
	}

	private static void strip(Fx.Pen pen, Vec3d h, Vec3d d, Vec3d w, double s0, double x0, double s1, double x1, int c0, int c1, float a) {
		int ca = Fx.argb(c0, a);
		int cb = Fx.argb(c1, a);
		pen.quad(p(h, d, w, s0, -x0), p(h, d, w, s0, x0), p(h, d, w, s1, x1), p(h, d, w, s1, -x1), ca, ca, cb, cb);
	}

	/** Solid parts, drawn with normal blending. */
	static void body(Fx.Pen pen, Vec3d h, Vec3d d, Vec3d w, float a) {
		float al = 0.96f * a;
		// grip and pommel
		strip(pen, h, d, w, -0.36, 0.035, 0.14, 0.035, 0x1D1511, 0x1D1511, al);
		strip(pen, h, d, w, -0.44, 0.06, -0.34, 0.06, 0x6B5528, 0x6B5528, al);
		// cross guard
		strip(pen, h, d, w, 0.12, 0.36, 0.22, 0.36, 0x7A6130, 0x5A4620, al);
		strip(pen, h, d, w, 0.10, 0.10, 0.24, 0.10, 0x9A7C3A, 0x9A7C3A, al);
		// blade: wide, thick and long
		strip(pen, h, d, w, 0.22, 0.14, 0.30, 0.17, 0x5A616E, 0x646B78, al);
		strip(pen, h, d, w, 0.30, 0.17, 1.90, 0.17, 0x646B78, 0x8E97A8, al);
		strip(pen, h, d, w, 1.90, 0.17, TIP, 0.0, 0x8E97A8, 0xC8D0E0, al);
		// the groove in the middle
		strip(pen, h, d, w, 0.36, 0.04, 1.85, 0.04, 0x2E323B, 0x3A3F4A, al);
		// bright edges
		strip(pen, h, d, w, 0.30, 0.17, 1.90, 0.17, 0xAAB3C6, 0xDDE4F2, al * 0.0f);
		for (int side = -1; side <= 1; side += 2) {
			Vec3d a0 = p(h, d, w, 0.30, 0.17 * side);
			Vec3d a1 = p(h, d, w, 0.30, 0.145 * side);
			Vec3d b0 = p(h, d, w, 1.90, 0.17 * side);
			Vec3d b1 = p(h, d, w, 1.90, 0.145 * side);
			Vec3d t0 = p(h, d, w, TIP, 0);
			int ce = Fx.argb(0xC8D0E0, al);
			int cf = Fx.argb(0xF0F4FF, al);
			pen.quad(a0, a1, b1, b0, ce, ce, cf, cf);
			pen.quad(b0, b1, t0, t0, cf, cf, cf, cf);
		}
		// thickness: a thin plate across the blade so it does not vanish when seen from the edge
		Vec3d n = d.crossProduct(w);
		if (n.lengthSquared() > 1e-6) {
			n = n.normalize();
			int c0 = Fx.argb(0x4A505C, al);
			int c1 = Fx.argb(0x7A8396, al);
			Vec3d s0 = h.add(d.multiply(0.25));
			Vec3d s1 = h.add(d.multiply(TIP - 0.02));
			double t = 0.04;
			pen.quad(s0.add(n.multiply(t)), s0.subtract(n.multiply(t)), s1.subtract(n.multiply(t * 0.2)), s1.add(n.multiply(t * 0.2)), c0, c0, c1, c1);
		}
	}

	/** Light parts, drawn additively: glowing edge, runes, tip. */
	static void glow(Fx.Pen pen, Vec3d h, Vec3d d, Vec3d w, float a, float power, double time) {
		int red = 0xFF2A1A;
		float edge = (0.25f + 0.5f * power) * a;
		for (int side = -1; side <= 1; side += 2) {
			pen.streak(p(h, d, w, 0.3, 0.18 * side), p(h, d, w, 1.9, 0.18 * side), 0.05, Fx.argb(red, edge * 0.4f), Fx.argb(0xFF8A50, edge));
			pen.streak(p(h, d, w, 1.9, 0.18 * side), p(h, d, w, TIP, 0), 0.05, Fx.argb(0xFF8A50, edge), Fx.argb(0xFFE0C0, edge));
		}
		pen.streak(p(h, d, w, 0.3, 0), p(h, d, w, TIP, 0), 0.3 + 0.2 * power, Fx.argb(red, 0.04f * a), Fx.argb(red, 0.14f * a * power));
		for (int i = 0; i < 6; i++) {
			double s = 0.55 + 0.24 * i;
			float pulse = (float) (0.45 + 0.55 * Math.sin(time * 5.0 + i * 1.1));
			pen.glow(p(h, d, w, s, 0), 0.07 + 0.04 * power, red, 0.55f * pulse * a);
		}
		pen.glow(p(h, d, w, TIP, 0), 0.25 + 0.3 * power, 0xFF5030, (0.35f + 0.5f * power) * a);
	}

	/** The swept band behind the blade; dark pass = smear, light pass = bright band with a white leading edge. */
	static void trail(Fx.Pen pen, Collection<PoseClient.Sample> samples, long now, boolean dark, float fade) {
		if (samples.size() < 2 || fade <= 0f) {
			return;
		}
		PoseClient.Sample prev = null;
		float prevA = 0f;
		for (PoseClient.Sample s : samples) {
			float age = (now - s.at) / 1_000_000_000f;
			float a = (float) Math.pow(Math.max(0f, 1f - age / 0.32f), 1.4) * fade;
			if (prev != null && prev.tip.squaredDistanceTo(s.tip) < 16.0) {
				if (dark) {
					int c0 = Fx.argb(0x14000C, prevA * 0.45f);
					int c1 = Fx.argb(0x14000C, a * 0.45f);
					pen.quad(prev.base, prev.tip, s.tip, s.base, c0, c0, c1, c1);
				} else {
					int b0 = Fx.argb(0xB01010, prevA * 0.2f);
					int b1 = Fx.argb(0xB01010, a * 0.2f);
					int t0 = Fx.argb(0xFF7A38, prevA * 0.9f);
					int t1 = Fx.argb(0xFF7A38, a * 0.9f);
					pen.quad(prev.base, prev.tip, s.tip, s.base, b0, t0, t1, b1);
					pen.streak(prev.tip, s.tip, 0.07, Fx.argb(0xFFF0E0, prevA), Fx.argb(0xFFF0E0, a));
				}
			}
			prev = s;
			prevA = a;
		}
	}
}
