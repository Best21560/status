package com.orv.window;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/**
 * Player bar at the top-left, Dark Souls style: bronze-rimmed portrait, long thin bars whose length grows with
 * the maximum, and a pale "damage trail" that drains after a short delay.
 */
public final class VitalsHud {
	private static final int OUTER = 0xFF050505;
	private static final int BRONZE = 0xFF7A6A44;
	private static final int BRONZE_HI = 0xFFB59A5C;
	private static final int BG = 0xFF120E0C;
	private static final int TEXT = 0xFFB8AE96;
	private static final int ALERT = 0xFFB03A2A;

	private static final Trail HP = new Trail();
	private static final Trail MP = new Trail();

	private VitalsHud() {
	}

	/** Remembers the "lost" part of a bar so it can drain slowly after a delay. */
	private static final class Trail {
		float trail = -1f;
		float last = -1f;
		long changedAt = 0;
		long lastFrame = 0;

		float update(float cur, float max, long now) {
			float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1000f);
			lastFrame = now;
			if (trail < 0f) {
				trail = cur;
				last = cur;
			}
			if (cur < last - 0.01f) {
				changedAt = now;
			}
			last = cur;
			if (cur >= trail) {
				trail = cur;
			} else if (now - changedAt > 500) {
				trail = Math.max(cur, trail - Math.max(1f, max) * 0.6f * dt);
			}
			return trail;
		}
	}

	public static void render(DrawContext ctx, float tickDelta) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden) {
			return;
		}
		TextRenderer tr = mc.textRenderer;
		long now = System.currentTimeMillis();

		int px = 8;
		int py = 8;
		int ps = 40;
		portrait(ctx, mc, px, py, ps);

		int bx = px + ps + 11;

		// ---- HP
		float hp = mc.player.getHealth();
		float maxHp = Math.max(1f, mc.player.getMaxHealth());
		float abs = mc.player.getAbsorptionAmount();
		boolean low = hp / maxHp <= 0.30f;
		int hpW = clamp(Math.round(70 + maxHp * 4.5f), 110, 230);
		int hpY = py + 3;
		int hpH = 10;
		int hpRim = (low && (now / 400) % 2 == 0) ? ALERT : BRONZE;
		bar(ctx, bx, hpY, hpW, hpH, hp / maxHp, HP.update(hp, maxHp, now) / maxHp,
				0xFFC8342A, 0xFF8C1A14, 0xFFD9B44A, hpRim);
		if (abs > 0.5f) {
			int aw = Math.min(hpW, Math.round(hpW * abs / maxHp));
			ctx.fill(bx, hpY + hpH - 2, bx + aw, hpY + hpH, 0xFFE0C060);
		}
		number(ctx, tr, (int) Math.ceil(hp) + (abs > 0.5f ? "+" + (int) Math.ceil(abs) : "") + "/" + (int) Math.ceil(maxHp),
				bx + hpW + 7, hpY + 2);

		// ---- Mana
		float mana = OrvWindowClient.mana;
		float maxMana = Math.max(1f, OrvWindowClient.maxMana);
		int mpW = clamp(Math.round(60 + maxMana * 0.7f), 90, 200);
		int mpY = hpY + hpH + 6;
		int mpH = 6;
		bar(ctx, bx, mpY, mpW, mpH, mana / maxMana, MP.update(mana, maxMana, now) / maxMana,
				0xFF4A7FD6, 0xFF2A4C94, 0xFF9DB8E8, BRONZE);
		number(ctx, tr, (int) Math.ceil(mana) + "/" + (int) Math.ceil(maxMana), bx + mpW + 7, mpY);

		// ---- Stamina
		float sta = OrvWindowClient.stamina;
		float maxSta = Math.max(1f, OrvWindowClient.maxStamina);
		boolean ex = OrvWindowClient.exhausted;
		int spW = clamp(Math.round(60 + maxSta * 0.9f), 90, 220);
		int spY = mpY + mpH + 6;
		int spH = 8;
		int spRim = (ex && (now / 150) % 2 == 0) ? ALERT : BRONZE;
		bar(ctx, bx, spY, spW, spH, sta / maxSta, sta / maxSta,
				ex ? 0xFF9A7F3A : 0xFF6FBF4A, ex ? 0xFF6A5526 : 0xFF3F7F2C, 0xFF000000, spRim);
		number(ctx, tr, (int) Math.ceil(sta) + "/" + (int) Math.ceil(maxSta), bx + spW + 7, spY + 1);
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static float clamp01(float v) {
		return v < 0f ? 0f : (v > 1f ? 1f : v);
	}

	private static void portrait(DrawContext ctx, MinecraftClient mc, int x, int y, int s) {
		ctx.fill(x - 4, y - 4, x + s + 4, y + s + 4, OUTER);
		ctx.fill(x - 3, y - 3, x + s + 3, y + s + 3, BRONZE);
		ctx.fill(x - 2, y - 2, x + s + 2, y + s + 2, 0xFF1A1512);
		ctx.fill(x - 1, y - 1, x + s + 1, y + s + 1, OUTER);

		Identifier skin = mc.player.getSkinTexture();
		RenderSystem.enableBlend();
		ctx.drawTexture(skin, x, y, s, s, 8f, 8f, 8, 8, 64, 64);
		ctx.drawTexture(skin, x, y, s, s, 40f, 8f, 8, 8, 64, 64);
		RenderSystem.disableBlend();

		// vignette
		int v = 9;
		ctx.fillGradient(x, y, x + s, y + v, 0x90000000, 0x00000000);
		ctx.fillGradient(x, y + s - v, x + s, y + s, 0x00000000, 0x90000000);

		// corner studs
		stud(ctx, x - 4, y - 4);
		stud(ctx, x + s + 3, y - 4);
		stud(ctx, x - 4, y + s + 3);
		stud(ctx, x + s + 3, y + s + 3);
	}

	private static void stud(DrawContext ctx, int cx, int cy) {
		ctx.fill(cx - 1, cy - 1, cx + 2, cy + 2, BRONZE_HI);
	}

	private static void bar(DrawContext ctx, int x, int y, int w, int h, float frac, float trailFrac,
							int top, int bottom, int trailColor, int rim) {
		ctx.fill(x - 2, y - 2, x + w + 2, y + h + 2, OUTER);
		ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, rim);
		ctx.fill(x, y, x + w, y + h, BG);

		int fw = Math.round(w * clamp01(frac));
		int tw = Math.round(w * clamp01(trailFrac));
		if (tw > fw) {
			ctx.fill(x + fw, y, x + tw, y + h, trailColor);
		}
		if (fw > 0) {
			ctx.fillGradient(x, y, x + fw, y + h, top, bottom);
			ctx.fill(x, y, x + fw, y + 1, 0x30FFFFFF);
		}

		// little notches on both ends
		int my = y + h / 2;
		ctx.fill(x - 4, my - 1, x - 1, my + 1, rim);
		ctx.fill(x + w + 1, my - 1, x + w + 4, my + 1, rim);
	}

	private static void number(DrawContext ctx, TextRenderer tr, String s, int x, int y) {
		MatrixStack m = ctx.getMatrices();
		m.push();
		m.translate(x, y, 0);
		m.scale(0.75f, 0.75f, 1f);
		ctx.drawTextWithShadow(tr, s, 0, 0, TEXT);
		m.pop();
	}
}
