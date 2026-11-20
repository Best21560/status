package com.orv.window;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Client side of the Lightbender: state of every caster nearby, drawing of orbs / halo / lights and the skill HUD. */
public final class LightClient {
	private LightClient() {
	}

	static final class St {
		int mask;
		final int[] hp = new int[8];
		long haloEnd;
		long extraEnd;
		int ring;
		float heal;
		long healResetAt;
		final long[] cdEnd = new long[3];
		long recv;
	}

	private static final Map<Integer, St> MAP = new HashMap<>();

	public static void reset() {
		MAP.clear();
	}

	public static boolean any() {
		return !MAP.isEmpty();
	}

	/** Reads the packet on the network thread, applies it on the game thread. */
	public static void receive(MinecraftClient client, PacketByteBuf buf) {
		int id = buf.readVarInt();
		int mask = buf.readVarInt();
		int[] hp = new int[8];
		for (int i = 0; i < 8; i++) {
			hp[i] = buf.readByte();
		}
		int halo = buf.readVarInt();
		int extra = buf.readVarInt();
		int ring = buf.readVarInt();
		float heal = buf.readFloat();
		int decay = buf.readVarInt();
		int[] cd = new int[3];
		for (int i = 0; i < 3; i++) {
			cd[i] = buf.readVarInt();
		}
		client.execute(() -> {
			boolean empty = mask == 0 && halo == 0 && extra == 0 && ring == 0 && heal <= 0f && cd[0] == 0 && cd[1] == 0 && cd[2] == 0;
			if (empty) {
				MAP.remove(id);
				return;
			}
			St s = MAP.computeIfAbsent(id, k -> new St());
			s.mask = mask;
			System.arraycopy(hp, 0, s.hp, 0, 8);
			s.haloEnd = halo > 0 ? Fx.clientTick + halo : 0;
			s.extraEnd = extra > 0 ? Fx.clientTick + extra : 0;
			s.ring = ring;
			s.heal = heal;
			s.healResetAt = decay > 0 ? Fx.clientTick + decay : 0;
			for (int i = 0; i < 3; i++) {
				s.cdEnd[i] = Fx.clientTick + cd[i];
			}
			s.recv = System.currentTimeMillis();
		});
	}

	static St mine() {
		MinecraftClient mc = MinecraftClient.getInstance();
		return mc.player == null ? null : MAP.get(mc.player.getId());
	}

	/** True while the local player has orbs out (M1 then shoots one). */
	public static boolean orbsOut() {
		St s = mine();
		return s != null && s.mask != 0;
	}

	// ------------------------------------------------------------------ world drawing
	// Palette: pale gold light, warm gold, a touch of jade green; everything is additive glow.

	private static final int PALE = 0xFFF2C8;
	private static final int GOLD = 0xFFD36A;
	private static final int DEEP = 0xE49A30;
	private static final int JADE = 0x9CF0C8;
	private static final int WHITE = 0xFFFFFF;

	static void render(Fx.Pen pen, MinecraftClient mc, float td) {
		if (MAP.isEmpty()) {
			return;
		}
		long nowMs = System.currentTimeMillis();
		double t = (mc.world.getTime() + td) / 20.0;
		Iterator<Map.Entry<Integer, St>> it = MAP.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, St> en = it.next();
			St s = en.getValue();
			if (nowMs - s.recv > 3500) {
				it.remove();
				continue;
			}
			Entity e = mc.world.getEntityById(en.getKey());
			if (e == null) {
				continue;
			}
			Vec3d base = e.getLerpedPos(td);
			float yaw = e instanceof LivingEntity le ? MathHelper.lerpAngleDegrees(td, le.prevBodyYaw, le.bodyYaw) : 0f;
			drawOrbs(pen, s, base, yaw, t);
			if (s.mask != 0 || s.haloEnd > Fx.clientTick) {
				drawMotes(pen, base, t, s.haloEnd > Fx.clientTick);
			}
			if (s.haloEnd > Fx.clientTick) {
				drawHalo(pen, s, base.add(0, e.getHeight() + 0.6, 0), t);
			}
			if (s.extraEnd > Fx.clientTick) {
				drawExtra(pen, base, t, (int) (s.extraEnd - Fx.clientTick));
			}
			if (s.ring > 0) {
				drawRing(pen, s, base, t);
			}
		}
	}

	/** Slow golden fireflies drifting up around the caster. */
	private static void drawMotes(Fx.Pen pen, Vec3d base, double t, boolean strong) {
		int n = strong ? 22 : 12;
		for (int i = 0; i < n; i++) {
			double ph = (t * (0.18 + 0.12 * Fx.rnd(3, i)) + Fx.rnd(9, i)) % 1.0;
			double a = Fx.rnd(5, i) * Math.PI * 2 + t * 0.5 * (i % 2 == 0 ? 1 : -1);
			double rad = 0.6 + 1.2 * Fx.rnd(7, i);
			Vec3d p = base.add(Math.cos(a) * rad, 0.1 + ph * 2.8, Math.sin(a) * rad);
			float f = (float) Math.sin(Math.PI * ph);
			pen.glow(p, 0.06 + 0.04 * Fx.rnd(2, i), i % 5 == 0 ? JADE : PALE, 0.85f * f);
		}
	}

	/** Spirit pearl: bright core, two turning armillary rings, a twinkling cross and two silk ribbons. */
	private static void drawPearl(Fx.Pen pen, Vec3d p, double scale, float f, double t, int seed, Vec3d back, Vec3d right) {
		pen.glow(p, 0.55 * scale, DEEP, 0.28f * f);
		pen.glow(p, 0.26 * scale, GOLD, 0.7f * f);
		pen.glow(p, 0.1 * scale, WHITE, f);
		double a = t * 1.3 + seed;
		Vec3d u1 = new Vec3d(Math.cos(a), 0, Math.sin(a));
		Vec3d v1 = new Vec3d(0, 1, 0);
		pen.ringIn(p, u1, v1, 0.3 * scale, 0.012 * scale, PALE, 0.85f * f);
		Vec3d u2 = new Vec3d(Math.cos(a + 1.57), 0, Math.sin(a + 1.57));
		Vec3d v2 = new Vec3d(0, 0.6, 0.8).normalize();
		pen.ringIn(p, u2, v2, 0.38 * scale, 0.012 * scale, GOLD, 0.6f * f);
		pen.face(p);
		float tw = 0.6f + 0.4f * (float) Math.sin(t * 6 + seed * 1.7);
		Vec3d c1 = pen.camRight.multiply(0.55 * scale);
		Vec3d c2 = pen.camUp.multiply(0.55 * scale);
		pen.streak(p.subtract(c1), p.add(c1), 0.02, Fx.argb(WHITE, 0f), Fx.argb(WHITE, 0.9f * f * tw));
		pen.streak(p.add(c1), p.subtract(c1), 0.02, Fx.argb(WHITE, 0f), Fx.argb(WHITE, 0.9f * f * tw));
		pen.streak(p.subtract(c2), p.add(c2), 0.02, Fx.argb(WHITE, 0f), Fx.argb(WHITE, 0.9f * f * tw));
		pen.streak(p.add(c2), p.subtract(c2), 0.02, Fx.argb(WHITE, 0f), Fx.argb(WHITE, 0.9f * f * tw));
		for (int k = 0; k < 2; k++) {
			Vec3d[] pts = new Vec3d[10];
			for (int j = 0; j < 10; j++) {
				double sj = j / 9.0;
				double sway = Math.sin(t * 2.2 + sj * 4.0 + k * Math.PI + seed) * 0.22 * sj;
				pts[j] = p.add(right.multiply(sway + (k == 0 ? -0.05 : 0.05) * sj))
						.add(back.multiply(0.35 * sj)).add(0, -1.05 * sj * scale, 0);
			}
			pen.ribbon(pts, 0.07 * scale, 0.015, PALE, 0.55f * f, 0f);
		}
	}

	private static void drawOrbs(Fx.Pen pen, St s, Vec3d base, float yaw, double t) {
		double yr = Math.toRadians(yaw);
		Vec3d back = new Vec3d(Math.sin(yr), 0, -Math.cos(yr));
		Vec3d right = new Vec3d(-Math.cos(yr), 0, -Math.sin(yr));
		for (int i = 0; i < 8; i++) {
			if ((s.mask & (1 << i)) == 0) {
				continue;
			}
			float f = 0.35f + 0.65f * Math.max(0f, Math.min(1f, s.hp[i] / 100f));
			drawPearl(pen, LightGeo.orbPos(base, yaw, i, t), 1.0, f, t, i, back, right);
		}
	}

	/** The Halo: a bright ring crowned with turning lotus-ray petals and floating talismans. */
	private static void drawHalo(Fx.Pen pen, St s, Vec3d c, double t) {
		long left = s.haloEnd - Fx.clientTick;
		float blink = left < 60 ? 0.65f + 0.35f * (float) Math.sin(t * 22.0) : 1f;
		pen.ring(c, 1.0, 0.07, PALE, 0.95f * blink);
		pen.ring(c, 1.0 + 0.05 * Math.sin(t * 4), 0.22, GOLD, 0.45f * blink);
		pen.ring(c, 0.62, 0.035, WHITE, 0.55f * blink);
		pen.ring(c, 1.55, 0.02, GOLD, 0.5f * blink);
		pen.glow(c.add(0, 0.05, 0), 1.7, GOLD, 0.2f * blink);
		for (int layer = 0; layer < 2; layer++) {
			int n = layer == 0 ? 12 : 12;
			double off = layer == 0 ? t * 0.45 : -t * 0.3 + Math.PI / n;
			double len = layer == 0 ? 0.6 : 0.38;
			for (int k = 0; k < n; k++) {
				double a = off + k * Math.PI * 2 / n;
				Vec3d out = new Vec3d(Math.cos(a), 0.0, Math.sin(a));
				Vec3d side = new Vec3d(-out.z, 0, out.x);
				pen.petal(c.add(out.multiply(1.02)), out, side, len, layer == 0 ? 0.24 : 0.16, layer == 0 ? GOLD : PALE, 0.8f * blink);
			}
		}
		// eight floating talismans
		for (int k = 0; k < 8; k++) {
			double a = -t * 0.7 + k * Math.PI / 4;
			Vec3d p = c.add(Math.cos(a) * 1.3, 0.18 * Math.sin(t * 2 + k), Math.sin(a) * 1.3);
			Vec3d tang = new Vec3d(-Math.sin(a), 0, Math.cos(a)).multiply(0.07);
			Vec3d up = new Vec3d(0, 0.2, 0);
			int cc = Fx.argb(k % 2 == 0 ? GOLD : JADE, 0.75f * blink);
			int ce = Fx.argb(PALE, 0.15f * blink);
			pen.quad(p.subtract(tang).subtract(up), p.add(tang).subtract(up), p.add(tang).add(up), p.subtract(tang).add(up), cc, cc, ce, ce);
			pen.glow(p, 0.15, PALE, 0.4f * blink);
		}
	}

	private static void drawExtra(Fx.Pen pen, Vec3d base, double t, int left) {
		float f = left < 40 ? left / 40f : 1f;
		for (int k = 0; k < 4; k++) {
			Vec3d p = LightGeo.extraPos(base, k, t);
			// ribbon following the path the pearl just flew
			Vec3d[] pts = new Vec3d[14];
			for (int j = 0; j < 14; j++) {
				pts[j] = LightGeo.extraPos(base, k, t - j * 0.035);
			}
			pen.ribbon(pts, 0.34, 0.05, GOLD, 0.6f * f, 0f);
			pen.ribbon(pts, 0.12, 0.02, WHITE, 0.8f * f, 0f);
			drawPearl(pen, p, 1.7, f, t, k + 20, new Vec3d(0, 0, 0), new Vec3d(0.5, 0, 0.5));
		}
	}

	private static void drawRing(Fx.Pen pen, St s, Vec3d base, double t) {
		int n = Math.min(40, s.ring);
		Vec3d c = base.add(0, 0.9, 0);
		pen.ring(c, 2.4, 0.04, PALE, 0.3f);
		pen.ring(c, 2.4, 0.14, GOLD, 0.15f);
		for (int j = 0; j < n; j++) {
			Vec3d p = LightGeo.ringPos(base, j, n, t);
			double a = t * 1.5 + j * Math.PI * 2.0 / Math.max(1, n);
			Vec3d tang = new Vec3d(-Math.sin(a), 0, Math.cos(a));
			Vec3d out = new Vec3d(Math.cos(a), 0, Math.sin(a));
			pen.petal(p.subtract(tang.multiply(0.18)), tang, out, 0.4, 0.12, j % 4 == 0 ? JADE : GOLD, 0.9f);
			pen.glow(p, 0.09, WHITE, 0.9f);
		}
	}

	// ------------------------------------------------------------------ one-shot effects

	/** Eight trigram bars (solid / broken) around a ring in the plane (u, v). */
	private static void trigrams(Fx.Pen pen, Vec3d c, Vec3d u, Vec3d v, double r, double spin, float alpha, int rgb) {
		for (int k = 0; k < 8; k++) {
			double a = spin + k * Math.PI / 4;
			Vec3d out = u.multiply(Math.cos(a)).add(v.multiply(Math.sin(a)));
			Vec3d tang = u.multiply(-Math.sin(a)).add(v.multiply(Math.cos(a)));
			for (int line = 0; line < 3; line++) {
				double rr = r + (line - 1) * 0.13;
				boolean yin = ((k >> line) & 1) == 0;
				Vec3d mid = c.add(out.multiply(rr));
				int col = Fx.argb(rgb, alpha);
				if (yin) {
					pen.streak(mid.add(tang.multiply(0.05)), mid.add(tang.multiply(0.3)), 0.035, col, col);
					pen.streak(mid.subtract(tang.multiply(0.05)), mid.subtract(tang.multiply(0.3)), 0.035, col, col);
				} else {
					pen.streak(mid.subtract(tang.multiply(0.3)), mid.add(tang.multiply(0.3)), 0.035, col, col);
				}
			}
		}
	}

	/** Summoning formation: bagua ring, turning lotus, runes and rising light, standing behind the caster. */
	static void drawCircle(Fx.Pen pen, Vec3d c, Vec3d fwd, float yaw, int variant, float tt) {
		double yr = Math.toRadians(yaw);
		Vec3d u = new Vec3d(-Math.cos(yr), 0, -Math.sin(yr));
		Vec3d v = new Vec3d(0, 1, 0);
		Vec3d n = new Vec3d(Math.sin(yr), 0, -Math.cos(yr)); // behind the caster
		float fade = tt < 0.15f ? tt / 0.15f : (float) Math.pow(1 - (tt - 0.15f) / 0.85f, 1.3);
		double grow = Fx.easeOut(Math.min(1.0, tt / 0.25));
		double R = 2.0 * grow;
		double spin = tt * 2.4 * (variant == 1 ? -1 : 1);
		pen.ringIn(c, u, v, R, 0.07, PALE, 0.95f * fade);
		pen.ringIn(c, u, v, R * 0.84, 0.03, GOLD, 0.8f * fade);
		pen.ringIn(c, u, v, R * 0.6, 0.04, PALE, 0.75f * fade);
		pen.ringIn(c, u, v, R * 1.1, 0.2, DEEP, 0.28f * fade);
		trigrams(pen, c, u, v, R * 0.92, 0.0, 0.9f * fade, GOLD);
		// rotating tick marks on the inner band
		for (int k = 0; k < 24; k++) {
			double a = -spin * 1.6 + k * Math.PI / 12;
			Vec3d out = u.multiply(Math.cos(a)).add(v.multiply(Math.sin(a)));
			pen.streak(c.add(out.multiply(R * 0.62)), c.add(out.multiply(R * (k % 3 == 0 ? 0.76 : 0.7))), 0.025,
					Fx.argb(PALE, 0.85f * fade), Fx.argb(PALE, 0.85f * fade));
		}
		// lotus in the middle
		for (int layer = 0; layer < 2; layer++) {
			int cnt = 8;
			for (int k = 0; k < cnt; k++) {
				double a = spin * (layer == 0 ? 1 : -1) * 0.6 + k * Math.PI * 2 / cnt + layer * Math.PI / cnt;
				Vec3d out = u.multiply(Math.cos(a)).add(v.multiply(Math.sin(a)));
				Vec3d side = u.multiply(-Math.sin(a)).add(v.multiply(Math.cos(a)));
				pen.petal(c.add(out.multiply(0.08)), out, side, R * (layer == 0 ? 0.56 : 0.4), R * (layer == 0 ? 0.26 : 0.18),
						layer == 0 ? GOLD : PALE, 0.75f * fade);
			}
		}
		pen.glow(c, 1.5 * grow, GOLD, 0.3f * fade);
		pen.glow(c, 0.35, WHITE, 0.9f * fade);
		// beams fanning out behind the formation
		for (int k = 0; k < 12; k++) {
			double a = k * Math.PI / 6 + 0.3;
			Vec3d out = u.multiply(Math.cos(a)).add(v.multiply(Math.sin(a)));
			pen.streak(c.add(out.multiply(R * 1.1)), c.add(out.multiply(R * 1.1 + 1.2 * grow)).add(n.multiply(0.4 * tt)), 0.05,
					Fx.argb(PALE, 0.5f * fade), Fx.argb(PALE, 0f));
		}
		// light motes rising from the formation
		for (int i = 0; i < 18; i++) {
			double a = Fx.rnd(1, i) * Math.PI * 2;
			double rr = R * 0.9 * Math.sqrt(Fx.rnd(2, i));
			Vec3d p = c.add(u.multiply(Math.cos(a) * rr)).add(v.multiply(Math.sin(a) * rr)).add(n.multiply(tt * 1.2 * (0.4 + Fx.rnd(3, i)))).add(0, tt * 0.8, 0);
			pen.glow(p, 0.05, i % 4 == 0 ? JADE : PALE, 0.9f * fade);
		}
	}

	/** A homing light: a small flying sword-light with a flowing ribbon. */
	static void drawProj(Fx.Pen pen, Vec3d from, Vec3d to, float age, float dur) {
		double k = Math.min(1.0, age / Math.max(0.1, dur));
		float a = k >= 1.0 ? 0f : 1f;
		Vec3d p = from.add(to.multiply(k));
		Vec3d dir = to.lengthSquared() < 1e-6 ? new Vec3d(0, 1, 0) : to.normalize();
		Vec3d n1 = dir.crossProduct(new Vec3d(0, 1, 0));
		n1 = n1.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : n1.normalize();
		Vec3d[] pts = new Vec3d[10];
		for (int j = 0; j < 10; j++) {
			double kk = Math.max(0, k - j * 0.018);
			double wob = Math.sin(kk * 18 + from.x) * 0.18 * (j / 9.0);
			pts[j] = from.add(to.multiply(kk)).add(n1.multiply(wob));
		}
		pen.ribbon(pts, 0.16, 0.02, GOLD, 0.8f * a, 0f);
		pen.glow(p, 0.26, GOLD, 0.65f * a);
		pen.glow(p, 0.1, WHITE, a);
		pen.petal(p.subtract(dir.multiply(0.1)), dir, n1, 0.34, 0.11, PALE, a);
	}

	/** Seraph Domain on the ground: expanding formation with a blooming lotus, trigram ring and pillars. */
	static void drawPulse(Fx.Pen pen, Vec3d c, float radius, int stage, float tt) {
		Vec3d g = c.add(0, 0.05, 0);
		double r = radius * Fx.easeOut(tt * 1.15);
		float fade = (float) Math.pow(1 - tt, 1.25);
		int core = stage == 0 ? 0xDFFFE0 : (stage == 1 ? 0xF0FFD0 : 0xFFF2C0);
		pen.ring(g, r, 0.22 + radius * 0.008, core, 0.9f * fade);
		pen.ring(g, r * 0.82, 0.5, GOLD, 0.35f * fade);
		pen.ring(g, r * 0.62, 0.04, PALE, 0.6f * fade);
		trigrams(pen, g, new Vec3d(1, 0, 0), new Vec3d(0, 0, 1), r * 0.9, tt * 0.8, 0.85f * fade, PALE);
		double bloom = Fx.easeOut(Math.min(1.0, tt * 2.0));
		double len = Math.min(radius * 0.45, 7.0) * bloom + 0.3;
		for (int layer = 0; layer < 3; layer++) {
			int cnt = layer == 2 ? 8 : 12;
			double lift = 0.9 - layer * 0.28;
			double l = len * (1.0 - layer * 0.28);
			for (int k = 0; k < cnt; k++) {
				double a = k * Math.PI * 2 / cnt + layer * 0.26 + tt * 0.3 * (layer % 2 == 0 ? 1 : -1);
				Vec3d out = new Vec3d(Math.cos(a), 0, Math.sin(a));
				Vec3d dir = out.multiply(Math.cos(lift * 0.6)).add(0, Math.sin(lift * 0.6) * (1 - tt * 0.4), 0).normalize();
				Vec3d side = new Vec3d(-out.z, 0, out.x);
				pen.petal(g.add(out.multiply(0.2)), dir, side, l, l * 0.34, layer == 1 ? PALE : (layer == 0 ? GOLD : JADE), 0.8f * fade);
			}
		}
		pen.glow(g.add(0, 0.4, 0), 2.2 * (1 - tt) + 0.7, PALE, 0.5f * fade);
		for (int k = 0; k < 8; k++) {
			double a = k * Math.PI / 4;
			Vec3d pt = g.add(Math.cos(a) * r * 0.9, 0, Math.sin(a) * r * 0.9);
			pen.pillar(pt, 3.5 * Math.min(1.0, tt * 3.0), 0.16, JADE, 0.55f * fade);
		}
		pen.pillar(g, 6.0 * Math.min(1.0, tt * 3), 0.8, PALE, 0.45f * fade);
		for (int i = 0; i < 20; i++) {
			double a = i * 2.399 + stage;
			double rr = r * (0.25 + 0.7 * Fx.rnd(11 + stage, i));
			Vec3d p = g.add(Math.cos(a) * rr, 0.2 + tt * 2.8 * (0.4 + Fx.rnd(5, i)), Math.sin(a) * rr);
			pen.glow(p, 0.06, i % 4 == 0 ? JADE : WHITE, 0.9f * fade);
		}
	}

	/** M1 shot: a silk-ribbon of light weaving from the orb to the target and bursting into petals. */
	static void drawShot(Fx.Pen pen, Vec3d from, Vec3d dir, float tt) {
		float fade = 1f - tt;
		Vec3d to = from.add(dir);
		double len = dir.length();
		if (len < 0.01) {
			return;
		}
		Vec3d d = dir.multiply(1.0 / len);
		Vec3d n1 = d.crossProduct(new Vec3d(0, 1, 0));
		n1 = n1.lengthSquared() < 1e-6 ? new Vec3d(1, 0, 0) : n1.normalize();
		Vec3d n2 = d.crossProduct(n1).normalize();
		int N = 22;
		Vec3d[] pts = new Vec3d[N];
		for (int j = 0; j < N; j++) {
			double s = j / (double) (N - 1);
			double w = Math.sin(Math.PI * s) * 0.3 * fade;
			double ph = s * 9 - tt * 14;
			pts[j] = from.add(dir.multiply(s)).add(n1.multiply(Math.sin(ph) * w)).add(n2.multiply(Math.cos(ph) * w));
		}
		pen.ribbon(pts, 0.28 * fade + 0.03, 0.06, GOLD, 0.6f * fade, 0.25f * fade);
		pen.ribbon(pts, 0.09 * fade + 0.02, 0.02, WHITE, 0.9f * fade, 0.5f * fade);
		pen.streak(from, to, 0.03, Fx.argb(WHITE, 0.6f * fade), Fx.argb(WHITE, 0.2f * fade));
		pen.glow(from, 0.55 * fade, PALE, 0.8f * fade);
		// head and a small lotus burst at the far end
		double hs = Fx.easeOut(Math.min(1.0, tt * 3.0));
		pen.glow(to, (0.35 + 0.5 * tt) , GOLD, 0.7f * fade);
		for (int k = 0; k < 6; k++) {
			double a = k * Math.PI / 3 + tt;
			Vec3d out = n1.multiply(Math.cos(a)).add(n2.multiply(Math.sin(a)));
			Vec3d side = d.crossProduct(out).normalize();
			pen.petal(to, out, side, 0.7 * hs, 0.22 * hs, k % 2 == 0 ? GOLD : PALE, 0.8f * fade);
		}
	}

	/** Halo burst / shatter: a huge lotus blooming outward with shock rings and a pillar of light. */
	static void drawBurst(Fx.Pen pen, Vec3d c, int big, float tt) {
		double R = big == 1 ? 9.0 : 3.5;
		double r = R * Fx.easeOut(tt);
		float fade = (float) Math.pow(1 - tt, 1.2);
		Vec3d g = c.add(0, 0.05, 0);
		pen.ring(g, r, 0.2 + R * 0.02, PALE, 0.95f * fade);
		pen.ring(g, r * 0.7, 0.5, GOLD, 0.45f * fade);
		pen.ring(g, r * 0.45, 0.05, WHITE, 0.6f * fade);
		double bloom = Fx.easeOut(Math.min(1.0, tt * 2.2));
		for (int layer = 0; layer < 3; layer++) {
			int cnt = layer == 1 ? 16 : 12;
			double l = R * (0.55 - layer * 0.13) * bloom + 0.2;
			for (int k = 0; k < cnt; k++) {
				double a = k * Math.PI * 2 / cnt + layer * 0.2 + tt * 0.6 * (layer % 2 == 0 ? 1 : -1);
				Vec3d out = new Vec3d(Math.cos(a), 0, Math.sin(a));
				double lift = 0.25 + layer * 0.35 + 0.4 * tt;
				Vec3d dir = out.multiply(Math.cos(lift)).add(0, Math.sin(lift), 0).normalize();
				Vec3d side = new Vec3d(-out.z, 0, out.x);
				pen.petal(g.add(out.multiply(0.3)), dir, side, l, l * 0.3, layer == 1 ? PALE : GOLD, 0.8f * fade);
			}
		}
		pen.pillar(c, big == 1 ? 14.0 : 8.0, 0.8 * (1 - tt * 0.5), PALE, 0.75f * fade);
		pen.glow(c.add(0, 1.0, 0), (big == 1 ? 4.0 : 2.0) * Fx.easeOut(tt * 1.5), GOLD, 0.55f * fade);
		int motes = big == 1 ? 40 : 18;
		for (int i = 0; i < motes; i++) {
			double a = Fx.rnd(4, i) * Math.PI * 2;
			double rr = r * Fx.rnd(6, i);
			Vec3d p = g.add(Math.cos(a) * rr, 0.3 + tt * (1.0 + 3.0 * Fx.rnd(8, i)), Math.sin(a) * rr);
			pen.glow(p, 0.07, i % 5 == 0 ? JADE : WHITE, 0.9f * fade);
		}
	}

	// ------------------------------------------------------------------ HUD

	private static final int OUT = 0xFF120E08;
	private static final int FRAME = 0xFF6A5A38;
	private static final int HUD_GOLD = 0xFFFFE9A8;

	/** Skill slots at the bottom right, Divine Orb status and the heal percentage. */
	public static void renderHud(DrawContext ctx, TextRenderer tr) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null) {
			return;
		}
		St s = mine();
		int sw = ctx.getScaledWindowWidth();
		int sh = ctx.getScaledWindowHeight();
		int size = 23;
		int gap = 3;
		int w = 3 * size + 2 * gap;
		int x0 = sw - w - 8;
		int y0 = sh - size - 8;
		long now = Fx.clientTick;
		boolean haloOn = s != null && s.haloEnd > now;
		for (int i = 0; i < 3; i++) {
			int x = x0 + i * (size + gap);
			long left = s == null ? 0 : s.cdEnd[i] - now;
			boolean cd = left > 0;
			ctx.fill(x - 1, y0 - 1, x + size + 1, y0 + size + 1, OUT);
			ctx.fillGradient(x, y0, x + size, y0 + size, 0xFF2C2616, 0xFF0E0B06);
			ctx.fill(x, y0, x + size, y0 + 1, FRAME);
			ctx.fill(x, y0, x + 1, y0 + size, FRAME);
			int col = cd ? 0xFF6A5E40 : (i == 2 && haloOn ? 0xFFFFFFFF : HUD_GOLD);
			glyph(ctx, i, x, y0, col);
			if (cd) {
				int total = LightEngine.CD[i];
				int h = (int) Math.min(size, Math.round(size * (left / (float) total)));
				ctx.fill(x + 1, y0 + size - h, x + size - 1, y0 + size - 1, 0xAA000000);
				String t = left >= 20 ? String.valueOf((left + 19) / 20) : String.format("%.1f", left / 20f);
				ctx.drawTextWithShadow(tr, t, x + (size - tr.getWidth(t)) / 2, y0 + 7, 0xFFFFFFFF);
			}
			ctx.getMatrices().push();
			ctx.getMatrices().translate(x + 2, y0 + 2, 0);
			ctx.getMatrices().scale(0.6f, 0.6f, 1f);
			ctx.drawTextWithShadow(tr, i == 0 ? "Z" : (i == 1 ? "X" : "C"), 0, 0, 0xFFE8D8B0);
			ctx.getMatrices().pop();
		}
		// Divine Orb status: eight small bars
		int py = y0 - 34;
		ctx.drawTextWithShadow(tr, "DIVINE ORB", x0, py, HUD_GOLD);
		for (int i = 0; i < 8; i++) {
			int bx = x0 + i * 10;
			ctx.fill(bx - 1, py + 11, bx + 9, py + 16, OUT);
			boolean alive = s != null && (s.mask & (1 << i)) != 0;
			if (alive) {
				int fill = Math.max(1, Math.round(8 * s.hp[i] / 100f));
				int c = s.hp[i] < 25 ? 0xFFE05030 : 0xFFF0D070;
				ctx.fill(bx, py + 12, bx + fill, py + 15, c);
			}
		}
		// heal percentage and the 10 s reset timer
		float heal = s == null ? 0f : s.heal;
		String ht = "HEAL " + (int) heal + "%";
		ctx.drawTextWithShadow(tr, ht, x0, py + 19, heal >= 200f ? 0xFFFFFFFF : (heal > 150f ? 0xFFFFE070 : HUD_GOLD));
		if (s != null && s.healResetAt > now) {
			int bw = Math.round(w * Math.min(1f, (s.healResetAt - now) / 200f));
			ctx.fill(x0, py + 29, x0 + w, py + 31, OUT);
			ctx.fill(x0, py + 29, x0 + bw, py + 31, 0xFFB89A50);
		}
	}

	private static void line(DrawContext ctx, int x0, int y0, int x1, int y1, int col) {
		int n = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
		for (int k = 0; k <= n; k++) {
			int px = x0 + (n == 0 ? 0 : (x1 - x0) * k / n);
			int py = y0 + (n == 0 ? 0 : (y1 - y0) * k / n);
			ctx.fill(px, py, px + 2, py + 2, col);
		}
	}

	private static void glyph(DrawContext ctx, int i, int x, int y, int col) {
		switch (i) {
			case 0 -> { // Divine Orb: a glowing sphere with rays
				ctx.fill(x + 8, y + 8, x + 15, y + 15, col);
				line(ctx, x + 11, y + 3, x + 11, y + 6, col);
				line(ctx, x + 11, y + 17, x + 11, y + 20, col);
				line(ctx, x + 3, y + 11, x + 6, y + 11, col);
				line(ctx, x + 17, y + 11, x + 20, y + 11, col);
			}
			case 1 -> { // Seraph Domain: concentric rings
				ctx.fill(x + 4, y + 4, x + 19, y + 6, col);
				ctx.fill(x + 4, y + 17, x + 19, y + 19, col);
				ctx.fill(x + 4, y + 4, x + 6, y + 19, col);
				ctx.fill(x + 17, y + 4, x + 19, y + 19, col);
				ctx.fill(x + 9, y + 9, x + 14, y + 14, col);
			}
			default -> { // Halo Shatter: a ring with a break
				ctx.fill(x + 6, y + 5, x + 17, y + 7, col);
				ctx.fill(x + 6, y + 15, x + 17, y + 17, col);
				ctx.fill(x + 4, y + 7, x + 6, y + 15, col);
				ctx.fill(x + 17, y + 7, x + 19, y + 11, col);
				line(ctx, x + 14, y + 13, x + 20, y + 20, col);
			}
		}
	}
}
