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
		ctx.fillGradient(3, 3, 6 + 36 + 8 + 190, 6 + 36 + 4, 0x30000000, 0x00000000);

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
		int hpLen = clamp(50 + Math.round(maxHp * 2.5f), 70, 170);
		bar(ctx, bx, y1, hpLen, hp / maxHp, trailHp / maxHp, 0xFFC02A22, 0xFF6C100D, 0xFFD9B45C);
		if (abs > 0.5f) {
			int aw = Math.min(hpLen, Math.round(hpLen * Math.min(1f, abs / maxHp)));
			ctx.fill(bx, y1 - 1, bx + aw, y1, 0xFFE8C45A);
		}
		numbers(ctx, tr, bx + hpLen + 5, y1 - 1, (int) Math.ceil(hp) + (abs > 0.5f ? "+" + (int) Math.ceil(abs) : "") + "/" + (int) Math.ceil(maxHp));

		// Mana
		int y2 = y1 + 11;
		int manaLen = clamp(30 + Math.round(maxMana * 0.8f), 60, 150);
		bar(ctx, bx, y2, manaLen, mana / maxMana, mana / maxMana, 0xFF4A80E0, 0xFF1C3A8A, 0);
		numbers(ctx, tr, bx + manaLen + 5, y2 - 1, (int) Math.ceil(mana) + "/" + (int) Math.ceil(maxMana));

		// Stamina (dull red pulse when it ran out)
		int y3 = y2 + 11;
		int staLen = clamp(30 + Math.round(maxSta * 0.9f), 60, 160);
		int top = 0xFF62B858;
		int bottom = 0xFF2A6A2D;
		if (exhausted) {
			boolean pulse = (nowMs / 250) % 2 == 0;
			top = pulse ? 0xFFA03838 : 0xFF742828;
			bottom = pulse ? 0xFF601C1C : 0xFF421414;
		}
		bar(ctx, bx, y3, staLen, sta / maxSta, sta / maxSta, top, bottom, 0);
		numbers(ctx, tr, bx + staLen + 5, y3 - 1, (int) Math.ceil(sta) + "/" + (int) Math.ceil(maxSta));
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
