package com.orv.window;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * Player bars at the top-left. Small, dark-fantasy look made of layered shading:
 * bevelled metal frames, glossy bars with a bit of grain, a delayed-damage trail on HP.
 */
public final class VitalsHud {
	// metal frame
	private static final int METAL_OUT = 0xFF080706;
	private static final int METAL_LIGHT = 0xFF8C7A52;
	private static final int METAL_MID = 0xFF4E4330;
	private static final int METAL_DARK = 0xFF1C1710;
	private static final int NUMBER = 0xFFB9A57A;

	/** All three bars (HP, Mana, Stamina) share this length. */
	public static final int BAR_LEN = 130;

	// HP trail
	private static float trailHp = -1f;
	private static float lastHp = -1f;
	private static long trailHoldUntil = 0;
	private static long lastNanos = 0;

	private VitalsHud() {
	}

	public static void render(DrawContext ctx, float tickDelta) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden) {
			return;
		}
		TextRenderer tr = mc.textRenderer;
		long nowN = System.nanoTime();
		float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (nowN - lastNanos) / 1_000_000_000f);
		lastNanos = nowN;
		long nowMs = nowN / 1_000_000L;

		float hp = mc.player.getHealth();
		float maxHp = Math.max(1f, mc.player.getMaxHealth());
		float abs = mc.player.getAbsorptionAmount();
		float mana = OrvWindowClient.mana;
		float maxMana = Math.max(1f, OrvWindowClient.maxMana);
		float sta = OrvWindowClient.stamina;
		float maxSta = Math.max(1f, OrvWindowClient.maxStamina);
		boolean exhausted = OrvWindowClient.exhausted;
		boolean lowHp = hp / maxHp <= 0.30f;

		// soft drop shadow behind everything
		ctx.fillGradient(3, 3, 6 + 36 + 8 + BAR_LEN + 40, 6 + 36 + 4, 0x30000000, 0x00000000);

		// ---- portrait
		int fx = 7;
		int fy = 7;
		int face = 30;
		bevelFrame(ctx, fx, fy, face, face, lowHp && (nowMs / 400) % 2 == 0);
		Identifier skin = mc.player.getSkinTexture();
		RenderSystem.enableBlend();
		ctx.drawTexture(skin, fx, fy, face, face, 8f, 8f, 8, 8, 64, 64);
		ctx.drawTexture(skin, fx, fy, face, face, 40f, 8f, 8, 8, 64, 64);
		RenderSystem.disableBlend();
		// shading: dark edges and a bottom vignette so the face sits inside the frame
		ctx.fillGradient(fx, fy, fx + face, fy + 5, 0x70000000, 0x00000000);
		ctx.fillGradient(fx, fy + face - 12, fx + face, fy + face, 0x00000000, 0xA0000000);
		ctx.fillGradient(fx, fy, fx + 4, fy + face, 0x55000000, 0x00000000);
		ctx.fill(fx + face - 1, fy, fx + face, fy + face, 0x40000000);

		// ---- HP trail
		if (trailHp < 0f) {
			trailHp = hp;
			lastHp = hp;
		}
		if (hp < lastHp - 0.01f) {
			trailHoldUntil = nowMs + 450;
		}
		lastHp = hp;
		if (hp >= trailHp) {
			trailHp = hp;
		} else if (nowMs >= trailHoldUntil) {
			trailHp = Math.max(hp, trailHp - maxHp * 0.30f * dt);
		}

		int bx = fx + face + 8;
		int y1 = fy + 2;

		// HP
		int hpLen = BAR_LEN;
		bar(ctx, bx, y1, hpLen, hp / maxHp, trailHp / maxHp, 0xFFC02A22, 0xFF6C100D, 0xFFD9B45C);
		if (abs > 0.5f) {
			int aw = Math.min(hpLen, Math.round(hpLen * Math.min(1f, abs / maxHp)));
			ctx.fill(bx, y1 - 1, bx + aw, y1, 0xFFE8C45A);
		}
		numbers(ctx, tr, bx + hpLen + 5, y1 - 1, (int) Math.ceil(hp) + (abs > 0.5f ? "+" + (int) Math.ceil(abs) : "") + "/" + (int) Math.ceil(maxHp));

		// Mana
		int y2 = y1 + 11;
		int manaLen = BAR_LEN;
		bar(ctx, bx, y2, manaLen, mana / maxMana, mana / maxMana, 0xFF4A80E0, 0xFF1C3A8A, 0);
		numbers(ctx, tr, bx + manaLen + 5, y2 - 1, (int) Math.ceil(mana) + "/" + (int) Math.ceil(maxMana));

		// Stamina (dull red pulse when it ran out)
		int y3 = y2 + 11;
		int staLen = BAR_LEN;
		int top = 0xFF62B858;
		int bottom = 0xFF2A6A2D;
		if (exhausted) {
			boolean pulse = (nowMs / 250) % 2 == 0;
			top = pulse ? 0xFFA03838 : 0xFF742828;
			bottom = pulse ? 0xFF601C1C : 0xFF421414;
		}
		bar(ctx, bx, y3, staLen, sta / maxSta, sta / maxSta, top, bottom, 0);
		numbers(ctx, tr, bx + staLen + 5, y3 - 1, (int) Math.ceil(sta) + "/" + (int) Math.ceil(maxSta));

		if (OrvWindowClient.slayer) {
			int y4 = y3 + 11;
			float rage = OrvWindowClient.rage;
			boolean bers = Fx.berserkTicks > 0;
			int rtop = 0xFFE03A2A;
			int rbot = 0xFF7A0C0C;
			if (bers || rage >= 85f) {
				boolean pulse = (nowMs / 120) % 2 == 0;
				rtop = pulse ? 0xFFFF6A3A : 0xFFE03A2A;
				rbot = pulse ? 0xFFA01410 : 0xFF7A0C0C;
			}
			bar(ctx, bx, y4, BAR_LEN, rage / 100f, rage / 100f, rtop, rbot, 0);
			numbers(ctx, tr, bx + BAR_LEN + 5, y4 - 1, bers ? "BERSERK " + (Fx.berserkTicks / 20 + 1) + "s" : (int) rage + "/100");
			// skill slots live at the bottom-right of the screen
			int sw = ctx.getScaledWindowWidth();
			int sh = ctx.getScaledWindowHeight();
			renderSlots(ctx, tr, sw - (5 * 26 - 3) - 8, sh - 23 - 8, rage, nowMs);
		}

		renderArmorAir(ctx, mc);
		if (OrvWindowClient.myClass.equals("lightbender")) {
			LightClient.renderHud(ctx, tr);
		}

		renderTarget(ctx, mc, tr);
	}

	// ------------------------------------------------------------------ Slayer skill slots

	/** Set by InGameHudMixin once the vanilla health/hunger row is hidden; then armor and air are drawn here. */
	public static boolean statusBarsHidden = false;
	private static final net.minecraft.util.Identifier ICONS = new net.minecraft.util.Identifier("textures/gui/icons.png");

	private static void renderArmorAir(DrawContext ctx, net.minecraft.client.MinecraftClient mc) {
		if (!statusBarsHidden || mc.player == null || mc.interactionManager == null || !mc.interactionManager.hasStatusBars()) {
			return;
		}
		int sw = ctx.getScaledWindowWidth();
		int sh = ctx.getScaledWindowHeight();
		int armor = mc.player.getArmor();
		if (armor > 0) {
			for (int i = 0; i < 10; i++) {
				int x = sw / 2 - 91 + i * 8;
				int u = armor > i * 2 + 1 ? 34 : (armor == i * 2 + 1 ? 25 : 16);
				ctx.drawTexture(ICONS, x, sh - 39, u, 9, 9, 9);
			}
		}
		int max = mc.player.getMaxAir();
		int air = mc.player.getAir();
		if (mc.player.isSubmergedIn(net.minecraft.registry.tag.FluidTags.WATER) || air < max) {
			int full = (int) Math.ceil((air - 2) * 10.0 / max);
			int all = (int) Math.ceil(air * 10.0 / max);
			for (int i = 0; i < all; i++) {
				int x = sw / 2 + 91 - i * 8 - 9;
				ctx.drawTexture(ICONS, x, sh - 39, i < full ? 16 : 25, 18, 9, 9);
			}
		}
	}

	private static final String[] KEYS = {"Z", "X", "-", "C", "B"};

	private static void renderSlots(DrawContext ctx, TextRenderer tr, int x0, int y0, float rage, long nowMs) {
		int size = 23;
		int gap = 3;
		for (int i = 0; i < 5; i++) {
			int x = x0 + i * (size + gap);
			long left = OrvWindowClient.cdEnd[i] - Fx.clientTick;
			boolean cd = left > 0;
			boolean noRage = false;
			boolean locked = Fx.locked || Fx.berserkTicks > 0;
			ctx.fill(x - 1, y0 - 1, x + size + 1, y0 + size + 1, METAL_OUT);
			ctx.fillGradient(x, y0, x + size, y0 + size, i == 4 ? 0xFF3A1210 : 0xFF241A14, 0xFF0C0807);
			ctx.fill(x, y0, x + size, y0 + 1, METAL_LIGHT);
			ctx.fill(x, y0, x + 1, y0 + size, METAL_MID);
			ctx.fill(x + size - 1, y0, x + size, y0 + size, METAL_DARK);
			ctx.fill(x, y0 + size - 1, x + size, y0 + size, METAL_DARK);
			int col = (cd || noRage || locked) ? 0xFF6A5A4A : (i == 4 ? 0xFFFFC870 : 0xFFFF6A50);
			glyph(ctx, i, x, y0, col);
			if (cd) {
				int h = (int) Math.min(size, Math.round(size * (left / (float) SlayerEngine.CD[i])));
				ctx.fill(x + 1, y0 + size - h, x + size - 1, y0 + size - 1, 0xAA000000);
				String t = left >= 20 ? String.valueOf((left + 19) / 20) : String.format("%.1f", left / 20f);
				int tw = tr.getWidth(t);
				ctx.drawTextWithShadow(tr, t, x + (size - tw) / 2, y0 + 7, 0xFFFFFFFF);
			} else if (!noRage && !locked && i != 2) {
				ctx.fill(x + 1, y0 + size - 2, x + size - 1, y0 + size - 1, 0x80FF6A50);
			}
			ctx.getMatrices().push();
			ctx.getMatrices().translate(x + 2, y0 + 2, 0);
			ctx.getMatrices().scale(0.6f, 0.6f, 1f);
			ctx.drawTextWithShadow(tr, i == 2 ? "AUTO" : KEYS[i], 0, 0, NUMBER);
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
			case 0 -> { // slash: a curved cut
				line(ctx, x + 4, y + 18, x + 9, y + 10, col);
				line(ctx, x + 9, y + 10, x + 17, y + 5, col);
				line(ctx, x + 6, y + 18, x + 12, y + 12, col & 0x80FFFFFF | 0x80000000);
			}
			case 1 -> { // pursuit: chevrons
				for (int k = 0; k < 3; k++) {
					line(ctx, x + 4 + k * 5, y + 6, x + 8 + k * 5, y + 11, col);
					line(ctx, x + 8 + k * 5, y + 11, x + 4 + k * 5, y + 16, col);
				}
			}
			case 2 -> { // counter hunt: crossed strokes
				line(ctx, x + 5, y + 5, x + 17, y + 17, col);
				line(ctx, x + 17, y + 5, x + 5, y + 17, col);
			}
			case 3 -> { // fatal strike: blade down
				ctx.fill(x + 10, y + 3, x + 13, y + 16, col);
				ctx.fill(x + 6, y + 15, x + 17, y + 17, col);
				ctx.fill(x + 10, y + 17, x + 13, y + 20, col);
			}
			default -> { // judgment: burst
				line(ctx, x + 11, y + 3, x + 11, y + 19, col);
				line(ctx, x + 3, y + 11, x + 19, y + 11, col);
				line(ctx, x + 5, y + 5, x + 17, y + 17, col);
				line(ctx, x + 17, y + 5, x + 5, y + 17, col);
			}
		}
	}

	// ------------------------------------------------------------------ enemy plate (name + HP of what you look at)

	/** Shows the name and HP of the creature you look at: 16 blocks, +16 with Keen Eyes. */
	private static void renderTarget(DrawContext ctx, MinecraftClient mc, TextRenderer tr) {
		if (mc.world == null || mc.player == null) {
			return;
		}
		double range = OrvWindowClient.mySkills.contains("keen_eyes") ? 32.0 : 16.0;
		net.minecraft.util.math.Vec3d eye = mc.player.getEyePos();
		net.minecraft.util.math.Vec3d look = mc.player.getRotationVec(1f);
		net.minecraft.entity.LivingEntity best = null;
		double bestDot = 0.9975; // about 4 degrees
		for (net.minecraft.entity.Entity e : mc.world.getEntities()) {
			if (!(e instanceof net.minecraft.entity.LivingEntity le) || e == mc.player || !le.isAlive() || le.isInvisible()) {
				continue;
			}
			net.minecraft.util.math.Vec3d to = le.getPos().add(0, le.getHeight() * 0.5, 0).subtract(eye);
			double dist = to.length();
			if (dist > range || dist < 0.5) {
				continue;
			}
			double dot = to.normalize().dotProduct(look);
			if (dot > bestDot && mc.player.canSee(le)) {
				bestDot = dot;
				best = le;
			}
		}
		if (best == null) {
			return;
		}
		int w = mc.getWindow().getScaledWidth();
		int len = 110;
		int x = (w - len) / 2;
		int y = 18;
		String name = best.getDisplayName().getString();
		int nw = tr.getWidth(name);
		ctx.drawTextWithShadow(tr, name, (w - nw) / 2, y - 11, 0xFFD9C79A);
		float hp = best.getHealth();
		float max = Math.max(1f, best.getMaxHealth());
		bar(ctx, x, y, len, hp / max, hp / max, 0xFFC02A22, 0xFF6C100D, 0);
		String txt = (int) Math.ceil(hp) + "/" + (int) Math.ceil(max);
		int tw = (int) (tr.getWidth(txt) * 0.65f);
		numbers(ctx, tr, (w - tw) / 2, y + 9, txt);
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** Bevelled metal frame around the content area x..x+w, y..y+h. */
	private static void bevelFrame(DrawContext ctx, int x, int y, int w, int h, boolean redGlow) {
		ctx.fill(x - 3, y - 3, x + w + 3, y + h + 3, METAL_OUT);
		// metal body, lit from the top-left
		ctx.fillGradient(x - 2, y - 2, x + w + 2, y + h + 2, METAL_LIGHT, METAL_DARK);
		ctx.fill(x - 2, y - 2, x + w + 2, y - 1, 0xFFB8A472); // top highlight
		ctx.fill(x - 2, y - 2, x - 1, y + h + 2, 0xFFA08C5C); // left highlight
		ctx.fill(x - 2, y + h + 1, x + w + 2, y + h + 2, 0xFF0F0C08); // bottom shade
		ctx.fill(x + w + 1, y - 2, x + w + 2, y + h + 2, 0xFF14100A); // right shade
		// inner groove
		ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, METAL_MID);
		ctx.fill(x, y, x + w, y + h, 0xFF050506);
		if (redGlow) {
			ctx.fill(x - 1, y - 1, x + w + 1, y, 0xFFB02820);
			ctx.fill(x - 1, y + h, x + w + 1, y + h + 1, 0xFFB02820);
		}
		// rivets
		ctx.fill(x - 3, y - 3, x - 1, y - 1, 0xFFB8A472);
		ctx.fill(x + w + 1, y - 3, x + w + 3, y - 1, METAL_LIGHT);
		ctx.fill(x - 3, y + h + 1, x - 1, y + h + 3, METAL_MID);
		ctx.fill(x + w + 1, y + h + 1, x + w + 3, y + h + 3, METAL_MID);
	}

	/** One thin bar with gloss and grain. trail = delayed value (0 colour = no trail). */
	private static void bar(DrawContext ctx, int x, int y, int len, float frac, float trail,
							int top, int bottom, int trailColor) {
		int h = 6;
		// metal frame around a long thin groove
		ctx.fill(x - 2, y - 2, x + len + 2, y + h + 2, METAL_OUT);
		ctx.fillGradient(x - 1, y - 1, x + len + 1, y + h + 1, METAL_LIGHT, METAL_DARK);
		ctx.fill(x - 1, y - 1, x + len + 1, y, 0xFFB8A472);
		ctx.fill(x - 1, y + h, x + len + 1, y + h + 1, 0xFF0F0C08);
		// empty groove (dark glass)
		ctx.fillGradient(x, y, x + len, y + h, 0xFF030304, 0xFF15151B);

		float f = Math.max(0f, Math.min(1f, frac));
		float t = Math.max(f, Math.min(1f, trail));
		int fw = Math.round(len * f);
		int tw = Math.round(len * t);

		if (trailColor != 0 && tw > fw) {
			ctx.fillGradient(x + fw, y, x + tw, y + h, trailColor, darker(trailColor));
		}
		if (fw > 0) {
			// body
			ctx.fillGradient(x, y, x + fw, y + h, lighter(top, 30), bottom);
			// specular strip on the upper half
			ctx.fill(x, y + 1, x + fw, y + 2, 0x30FFFFFF);
			// top edge light, bottom edge shade
			ctx.fill(x, y, x + fw, y + 1, lighter(top, 60));
			ctx.fill(x, y + h - 1, x + fw, y + h, 0x60000000);
			// fine vertical grain
			for (int i = 2; i < fw; i += 3) {
				ctx.fill(x + i, y, x + i + 1, y + h, 0x16000000);
			}
			// bright leading edge
			if (fw > 1) {
				ctx.fill(x + fw - 1, y, x + fw, y + h, lighter(top, 70));
			}
		}
		// shadow inside the groove at the top and at both ends
		ctx.fill(x, y, x + len, y + 1, 0x66000000);
		ctx.fill(x, y, x + 1, y + h, 0x55000000);
		ctx.fill(x + len - 1, y, x + len, y + h, 0x55000000);
	}

	private static int lighter(int argb, int amount) {
		int r = Math.min(255, ((argb >> 16) & 255) + amount);
		int g = Math.min(255, ((argb >> 8) & 255) + amount);
		int b = Math.min(255, (argb & 255) + amount);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private static int darker(int argb) {
		int r = ((argb >> 16) & 255) * 6 / 10;
		int g = ((argb >> 8) & 255) * 6 / 10;
		int b = (argb & 255) * 6 / 10;
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	/** Small dim numbers to the right of a bar. */
	private static void numbers(DrawContext ctx, TextRenderer tr, int x, int y, String text) {
		ctx.getMatrices().push();
		ctx.getMatrices().translate(x, y, 0);
		ctx.getMatrices().scale(0.65f, 0.65f, 1f);
		ctx.drawTextWithShadow(tr, text, 0, 0, NUMBER);
		ctx.getMatrices().pop();
	}
}
