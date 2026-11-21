package com.orv.window;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/** Rogue on the client: draws the Shadow Clones (a dark copy of the player wrapped in smoke) and the skill slots. */
public final class RogueClient {
	private RogueClient() {
	}

	private static final Random RAND = new Random();

	private static final class Clone {
		OtherClientPlayerEntity body;
		Vec3d pos;
		long endMs;
	}

	private static final List<Clone> CLONES = new ArrayList<>();

	/** A shadow aura on an entity (Adrenaline) or on a clone, as handed to FireFx. */
	static final class Aura {
		Vec3d pos;
		float k;
		boolean dense;
		boolean firstPerson;
	}

	/** A short burst of shadow flames; tt = progress 0..1. */
	static final class Burst {
		Vec3d pos;
		long start;
		float life;
		float tt;
		long seed;
		int count;
		double radius;
		double height;
		double lift;
		float alpha;
	}

	private static final java.util.Map<Integer, Long> AURA_END = new java.util.HashMap<>();
	private static final List<Burst> BURSTS = new ArrayList<>();

	public static boolean any() {
		return !CLONES.isEmpty() || !AURA_END.isEmpty() || !BURSTS.isEmpty();
	}

	public static void aura(int entityId, float seconds) {
		AURA_END.put(entityId, System.currentTimeMillis() + (long) (seconds * 1000));
	}

	public static void burst(Vec3d pos, float radius, int count) {
		Burst b = new Burst();
		b.pos = pos;
		b.start = System.nanoTime();
		b.life = 0.55f + radius * 0.12f;
		b.seed = RAND.nextLong();
		b.count = Math.max(6, Math.min(70, count));
		b.radius = radius;
		b.height = 0.8 + radius * 0.7;
		b.lift = Math.min(1.6, radius * 0.6);
		b.alpha = 0.95f;
		BURSTS.add(b);
		if (BURSTS.size() > 60) {
			BURSTS.remove(0);
		}
	}

	/** A dash in shadow colours: small flame bursts along the way. */
	public static void trail(Vec3d from, Vec3d delta) {
		double len = delta.length();
		int n = (int) Math.max(2, Math.min(8, len / 1.2));
		for (int i = 0; i <= n; i++) {
			Vec3d p = from.add(delta.multiply(i / (double) n)).add(0, -0.9, 0);
			Burst b = new Burst();
			b.pos = p;
			b.start = System.nanoTime() + (long) (i * 25_000_000L);
			b.life = 0.6f;
			b.seed = RAND.nextLong();
			b.count = 10;
			b.radius = 0.45;
			b.height = 1.3;
			b.lift = 0.9;
			b.alpha = 0.9f;
			BURSTS.add(b);
		}
	}

	static List<Aura> auras(MinecraftClient mc, float td) {
		List<Aura> out = new ArrayList<>();
		long now = System.currentTimeMillis();
		for (Clone c : CLONES) {
			Aura a = new Aura();
			a.pos = c.pos;
			long left = c.endMs - now;
			a.k = Math.min(1f, left / 400f);
			a.dense = true;
			out.add(a);
		}
		if (mc.world != null) {
			AURA_END.entrySet().removeIf(e -> e.getValue() <= now);
			for (java.util.Map.Entry<Integer, Long> e : AURA_END.entrySet()) {
				Entity en = mc.world.getEntityById(e.getKey());
				if (en == null || en.isInvisible()) {
					continue;
				}
				Aura a = new Aura();
				a.pos = en.getLerpedPos(td);
				a.k = Math.min(1f, (e.getValue() - now) / 500f);
				a.firstPerson = en == mc.player && mc.options.getPerspective().isFirstPerson();
				out.add(a);
			}
		}
		return out;
	}

	static List<Burst> bursts() {
		long now = System.nanoTime();
		BURSTS.removeIf(b -> (now - b.start) / 1e9f >= b.life);
		List<Burst> out = new ArrayList<>();
		for (Burst b : BURSTS) {
			float age = (now - b.start) / 1e9f;
			if (age < 0f) {
				continue;
			}
			b.tt = age / b.life;
			out.add(b);
		}
		return out;
	}

	/** Additive light: the glowing purple eyes of the clones and a faint violet core inside the flames. */
	static void drawGlow(Fx.Pen pen, MinecraftClient mc, float td) {
		long now = System.currentTimeMillis();
		double flick = 0.85 + 0.15 * Math.sin(now / 70.0);
		for (Clone c : CLONES) {
			float k = Math.min(1f, (c.endMs - now) / 400f);
			double yaw = Math.toRadians(c.body.getHeadYaw());
			Vec3d f = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
			Vec3d r = new Vec3d(-f.z, 0, f.x);
			Vec3d head = c.pos.add(0, 1.56, 0).add(f.multiply(0.27));
			for (int s = -1; s <= 1; s += 2) {
				Vec3d eye = head.add(r.multiply(0.09 * s));
				pen.glow(eye, 0.05, 0xFFFFFF, (float) (0.9 * k * flick));
				pen.glow(eye, 0.14, 0xB070FF, (float) (0.8 * k * flick));
				pen.glow(eye, 0.35, 0x6A18D0, (float) (0.35 * k * flick));
			}
			pen.glow(c.pos.add(0, 1.0, 0), 1.3, 0x4A0C90, 0.18f * k);
		}
	}

	/** From the CLONE effect packet: pos, dir = (body yaw, pitch, head yaw), a = seconds, b = owner entity id. */
	public static void spawn(Vec3d pos, Vec3d dir, float seconds, int ownerId) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null) {
			return;
		}
		Entity owner = mc.world.getEntityById(ownerId);
		if (!(owner instanceof PlayerEntity op)) {
			return;
		}
		try {
			OtherClientPlayerEntity b = new OtherClientPlayerEntity(mc.world, op.getGameProfile());
			b.refreshPositionAndAngles(pos.x, pos.y, pos.z, (float) dir.z, (float) dir.y);
			b.setBodyYaw((float) dir.x);
			b.prevBodyYaw = (float) dir.x;
			b.setHeadYaw((float) dir.z);
			b.prevHeadYaw = (float) dir.z;
			for (EquipmentSlot s : EquipmentSlot.values()) {
				b.equipStack(s, op.getEquippedStack(s).copy());
			}
			Clone c = new Clone();
			c.body = b;
			c.pos = pos;
			c.endMs = System.currentTimeMillis() + (long) (seconds * 1000);
			CLONES.add(c);
			burst(pos.add(0, 0.1, 0), 1.4f, 44);
		} catch (Throwable t) {
			org.slf4j.LoggerFactory.getLogger("orvwindow").warn("[orvwindow] shadow clone could not be made", t);
		}
	}

	public static void tick(MinecraftClient mc) {
		if (CLONES.isEmpty()) {
			return;
		}
		if (mc.world == null) {
			CLONES.clear();
			return;
		}
		long now = System.currentTimeMillis();
		Iterator<Clone> it = CLONES.iterator();
		while (it.hasNext()) {
			Clone c = it.next();
			if (now >= c.endMs || c.body.getWorld() != mc.world) {
				it.remove();
				burst(c.pos.add(0, 0.1, 0), 1.2f, 38);
				continue;
			}
			c.body.age++;
			for (int i = 0; i < 2; i++) {
				mc.world.addParticle(ParticleTypes.SQUID_INK, c.pos.x + (RAND.nextDouble() - 0.5) * 0.7,
						c.pos.y + RAND.nextDouble() * 1.9, c.pos.z + (RAND.nextDouble() - 0.5) * 0.7, 0, 0.03, 0);
			}
			if (RAND.nextInt(3) == 0) {
				mc.world.addParticle(ParticleTypes.WITCH, c.pos.x, c.pos.y + 0.1, c.pos.z,
						(RAND.nextDouble() - 0.5) * 0.05, 0.01, (RAND.nextDouble() - 0.5) * 0.05);
			}
		}
	}

	public static void render(WorldRenderContext ctx) {
		if (CLONES.isEmpty()) {
			return;
		}
		MinecraftClient mc = MinecraftClient.getInstance();
		VertexConsumerProvider vcp = ctx.consumers();
		MatrixStack ms = ctx.matrixStack();
		if (vcp == null || ms == null || mc.world == null) {
			return;
		}
		Vec3d cam = ctx.camera().getPos();
		// very dark light level: the copy looks like a shadow
		int light = LightmapTextureManager.pack(0, 1);
		for (Clone c : CLONES) {
			try {
				mc.getEntityRenderDispatcher().render(c.body, c.pos.x - cam.x, c.pos.y - cam.y, c.pos.z - cam.z,
						c.body.getYaw(), ctx.tickDelta(), ms, vcp, light);
			} catch (Throwable t) {
				// a renderer from another mod did not like the fake player: keep only the smoke
				c.body.setInvisible(true);
			}
		}
	}

	public static void reset() {
		CLONES.clear();
		AURA_END.clear();
		BURSTS.clear();
	}

	// ------------------------------------------------------------------ skill slots (bottom right)

	private static final String[] KEYS = {"Z", "X", "C", "B", "G"};
	private static final String[] NAMES = {"Dash", "Flurry", "Rush", "Wave", "Clone"};

	public static void renderHud(DrawContext ctx, TextRenderer tr) {
		int size = 23;
		int gap = 3;
		int sw = ctx.getScaledWindowWidth();
		int sh = ctx.getScaledWindowHeight();
		int x0 = sw - (5 * (size + gap) - gap) - 8;
		int y0 = sh - size - 8;
		for (int i = 0; i < 5; i++) {
			int x = x0 + i * (size + gap);
			long left = OrvWindowClient.cdEnd[i] - Fx.clientTick;
			boolean cd = left > 0;
			ctx.fill(x - 1, y0 - 1, x + size + 1, y0 + size + 1, 0xFF3A3F48);
			ctx.fillGradient(x, y0, x + size, y0 + size, 0xFF1A1E26, 0xFF07080B);
			ctx.fill(x, y0, x + size, y0 + 1, 0xFF6A7380);
			int col = cd ? 0xFF555A63 : 0xFFB8C6E0;
			glyph(ctx, i, x, y0, col);
			if (cd) {
				int h = (int) Math.min(size, Math.round(size * (left / (float) RogueEngine.CD[i])));
				ctx.fill(x + 1, y0 + size - h, x + size - 1, y0 + size - 1, 0xAA000000);
				String t = left >= 20 ? String.valueOf((left + 19) / 20) : String.format("%.1f", left / 20f);
				ctx.drawTextWithShadow(tr, t, x + (size - tr.getWidth(t)) / 2, y0 + 7, 0xFFFFFFFF);
			}
			ctx.getMatrices().push();
			ctx.getMatrices().translate(x + 2, y0 + 2, 0);
			ctx.getMatrices().scale(0.6f, 0.6f, 1f);
			ctx.drawTextWithShadow(tr, KEYS[i], 0, 0, 0xFFE0E6F0);
			ctx.getMatrices().pop();
			ctx.getMatrices().push();
			ctx.getMatrices().translate(x + size / 2f - tr.getWidth(NAMES[i]) * 0.25f, y0 - 6, 0);
			ctx.getMatrices().scale(0.5f, 0.5f, 1f);
			ctx.drawTextWithShadow(tr, NAMES[i], 0, 0, 0xFF9AA4B4);
			ctx.getMatrices().pop();
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
			case 0 -> { // dash: speed lines + arrow
				line(ctx, x + 4, y + 8, x + 12, y + 8, col);
				line(ctx, x + 3, y + 12, x + 14, y + 12, col);
				line(ctx, x + 4, y + 16, x + 12, y + 16, col);
				line(ctx, x + 14, y + 7, x + 19, y + 12, col);
				line(ctx, x + 19, y + 12, x + 14, y + 17, col);
			}
			case 1 -> { // flurry: many small cuts
				for (int k = 0; k < 3; k++) {
					line(ctx, x + 4 + k * 5, y + 17, x + 8 + k * 5, y + 5, col);
				}
			}
			case 2 -> { // adrenaline: lightning
				line(ctx, x + 13, y + 3, x + 8, y + 12, col);
				line(ctx, x + 8, y + 12, x + 14, y + 11, col);
				line(ctx, x + 14, y + 11, x + 9, y + 20, col);
			}
			case 3 -> { // wave: crescent
				line(ctx, x + 7, y + 4, x + 12, y + 8, col);
				line(ctx, x + 12, y + 8, x + 13, y + 14, col);
				line(ctx, x + 13, y + 14, x + 7, y + 19, col);
				line(ctx, x + 15, y + 11, x + 20, y + 11, col);
			}
			default -> { // clone: two figures
				ctx.fill(x + 5, y + 5, x + 9, y + 9, col);
				ctx.fill(x + 4, y + 10, x + 10, y + 19, col);
				ctx.fill(x + 13, y + 5, x + 17, y + 9, col & 0x60FFFFFF);
				ctx.fill(x + 12, y + 10, x + 18, y + 19, col & 0x60FFFFFF);
			}
		}
	}
}
