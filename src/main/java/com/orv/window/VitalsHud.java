package com.orv.window;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/** Player bar at the top-left of the screen, Dark Souls style: face, HP (with damage trail), Mana, Stamina. */
public final class VitalsHud {
	// frame colours
	private static final int FRAME_OUTER = 0xFF050505;
	private static final int FRAME_GOLD = 0xFF6A5B3D;
	private static final int FRAME_GOLD_LIGHT = 0xFF9B8860;
	private static final int BAR_BACK = 0xFF110B0B;
	private static final int TEXT = 0xFFC9BEA4;

	// HP damage trail (the pale part that slowly shrinks after you get hurt)
	private static float trailHp = -1f;
	private static float lastHp = -1f;
	private static long trailHoldUntil = 0L;
	private static long lastFrameNs = 0L;

	private VitalsHud() {
	}

	public static void render(DrawContext ctx, float tickDelta) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden) {
			return;
		}
		TextRenderer tr = mc.textRenderer;
		long nowMs = System.currentTimeMillis();
		long nowNs = System.nanoTime();
		float dt = lastFrameNs == 0L ? 0f : Math.min(0.1f, (nowNs - lastFrameNs) / 1_000_000_000f);
		lastFrameNs = nowNs;

		float hp = mc.player.getHealth();
		float maxHp = Math.max(1f, mc.player.getMaxHealth());
		float abs = mc.player.getAbsorptionAmount();

		// trail logic
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
		} else if (nowMs > trailHoldUntil) {
			trailHp = Math.max(hp, trailHp - maxHp * 0.5f * dt);
		}

		// ---- face
		int x = 8;
		int y = 8;
		int size = 36;
		ctx.fill(x, y, x + size, y + size, FRAME_OUTER);
		ctx.fill(x + 1, y + 1, x + size - 1, y + size - 1, FRAME_GOLD);
		ctx.fill(x + 2, y + 2, x + size - 2, y + size - 2, FRAME_OUTER);
		Identifier skin = mc.player.getSkinTexture();
		RenderSystem.enableBlend();
		ctx.drawTexture(skin, x + 3, y + 3, size - 6, size - 6, 8f, 8f, 8, 8, 64, 64);
		ctx.drawTexture(skin, x + 3, y + 3, size - 6, size - 6, 40f, 8f, 8, 8, 64, 64);
		RenderSystem.disableBlend();
		ctx.fill(x + 3, y + 3, x + size - 3, y + size - 3, 0x30000000); // slight dimming
		// little corner studs
		ctx.fill(x - 1, y - 1, x + 3, y + 1, FRAME_GOLD_LIGHT);
		ctx.fill(x - 1, y - 1, x + 1, y + 3, FRAME_GOLD_LIGHT);
		ctx.fill(x + size - 3, y + size - 1, x + size + 1, y + size + 1, FRAME_GOLD_LIGHT);
		ctx.fill(x + size - 1, y + size - 3, x + size + 1, y + size + 1, FRAME_GOLD_LIGHT);

		int bx = x + size + 8;

		// ---- HP bar (longer when Max HP is higher)
		int hpW = clamp(90 + Math.round(maxHp * 3f), 120, 240);
		float hpFrac = clamp01(hp / maxHp);
		float pulse = hpFrac <= 0.30f ? 0.5f + 0.5f * (float) Math.sin(nowMs / 140.0) : 0f;
		int hpTop = mix(0xFFC23B2E, 0xFFFF6A55, pulse);
		int hpBot = mix(0xFF8A1E19, 0xFFB5302A, pulse);
		bar(ctx, bx, y + 1, hpW, 11, hpFrac, clamp01(trailHp / maxHp), hpTop, hpBot, 0xFFE0B040);
		if (abs > 0.5f) {
			// absorption: a thin gold line under the HP bar
			int aw = Math.round(hpW * clamp01(abs / maxHp));
			ctx.fill(bx, y + 13, bx + aw, y + 14, 0xFFE8C65A);
		}
		small(ctx, tr, (int) Math.ceil(hp) + "/" + (int) Math.ceil(maxHp), bx + hpW + 6, y + 3);

		// ---- Mana bar
		float mana = OrvWindowClient.mana;
		float maxMana = Math.max(1f, OrvWindowClient.maxMana);
		int mpW = clamp(50 + Math.round(maxMana * 0.8f), 90, 200);
		bar(ctx, bx, y + 17, mpW, 8, clamp01(mana / maxMana), -1f, 0xFF4F89D9, 0xFF2A4F96, 0);
		small(ctx, tr, (int) Math.ceil(mana) + "/" + (int) Math.ceil(maxMana), bx + mpW + 6, y + 17);

		// ---- Stamina bar (flashes when exhausted)
		float sta = OrvWindowClient.stamina;
		float maxSta = Math.max(1f, OrvWindowClient.maxStamina);
		int spW = clamp(50 + Math.round(maxSta * 0.8f), 90, 200);
		boolean ex = OrvWindowClient.exhausted;
		int spTop = 0xFF86C650;
		int spBot = 0xFF4B7B2C;
		if (ex) {
			boolean blink = (nowMs / 200) % 2 == 0;
			spTop = blink ? 0xFFB5442F : 0xFF6B2A1F;
			spBot = blink ? 0xFF7A2A1D : 0xFF3F1810;
		}
		bar(ctx, bx, y + 28, spW, 9, clamp01(sta / maxSta), -1f, spTop, spBot, 0);
		small(ctx, tr, (int) Math.ceil(sta) + "/" + (int) Math.ceil(maxSta), bx + spW + 6, y + 29);
	}

	/** One bar: dark double frame, dark back, optional pale trail, two-tone fill with a highlight line. */
	private static void bar(DrawContext ctx, int x, int y, int w, int h, float frac, float trailFrac,
							int topColor, int botColor, int trailColor) {
		ctx.fill(x - 2, y - 2, x + w + 2, y + h + 2, FRAME_OUTER);
		ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, FRAME_GOLD);
		ctx.fill(x, y, x + w, y + h, BAR_BACK);
		if (trailFrac > frac) {
			ctx.fill(x, y, x + Math.round(w * trailFrac), y + h, trailColor);
		}
		int fw = Math.round(w * frac);
		if (fw > 0) {
			int half = Math.max(1, h / 2);
			ctx.fill(x, y, x + fw, y + half, topColor);
			ctx.fill(x, y + half, x + fw, y + h, botColor);
			ctx.fill(x, y, x + fw, y + 1, 0x38FFFFFF); // top highlight
			ctx.fill(x + fw - 1, y, x + fw, y + h, 0x50000000); // dark edge at the end of the fill
		}
		// ornament: small gold ticks at both ends
		ctx.fill(x - 3, y + h / 2 - 1, x - 2, y + h / 2 + 2, FRAME_GOLD_LIGHT);
		ctx.fill(x + w + 2, y + h / 2 - 1, x + w + 3, y + h / 2 + 2, FRAME_GOLD_LIGHT);
	}

	private static void small(DrawContext ctx, TextRenderer tr, String text, int x, int y) {
		ctx.getMatrices().push();
		ctx.getMatrices().translate(x, y, 0);
		ctx.getMatrices().scale(0.75f, 0.75f, 1f);
		ctx.drawTextWithShadow(tr, text, 0, 0, TEXT);
		ctx.getMatrices().pop();
	}

	private static float clamp01(float v) {
		return v < 0f ? 0f : (v > 1f ? 1f : v);
	}

	private static int clamp(int v, int lo, int hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	private static int mix(int a, int b, float t) {
		int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
		int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
		int r = Math.round(ar + (br - ar) * t);
		int g = Math.round(ag + (bg - ag) * t);
		int bl = Math.round(ab + (bb - ab) * t);
		return 0xFF000000 | (r << 16) | (g << 8) | bl;
	}
}
