package com.orv.window;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/** Player bar at the top-left of the screen: face, HP, Mana, Stamina. */
public final class VitalsHud {
	private static final int BORDER = 0xFF2E9FF0;

	private VitalsHud() {
	}

	public static void render(DrawContext ctx, float tickDelta) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.player == null || mc.options.hudHidden) {
			return;
		}
		TextRenderer tr = mc.textRenderer;

		// face (head + hat layer of the skin)
		int x = 6;
		int y = 6;
		int face = 36;
		ctx.fill(x - 2, y - 2, x + face + 2, y + face + 2, BORDER);
		ctx.fill(x, y, x + face, y + face, 0xFF061426);
		Identifier skin = mc.player.getSkinTexture();
		RenderSystem.enableBlend();
		ctx.drawTexture(skin, x, y, face, face, 8f, 8f, 8, 8, 64, 64);
		ctx.drawTexture(skin, x, y, face, face, 40f, 8f, 8, 8, 64, 64);
		RenderSystem.disableBlend();

		// bars
		int bx = x + face + 6;
		int bw = 124;
		int bh = 10;

		float hp = mc.player.getHealth();
		float maxHp = mc.player.getMaxHealth();
		float abs = mc.player.getAbsorptionAmount();
		boolean low = maxHp > 0 && hp / maxHp <= 0.30f;
		String hpText = "HP " + (int) Math.ceil(hp) + (abs > 0.5f ? "+" + (int) Math.ceil(abs) : "") + "/" + (int) Math.ceil(maxHp);
		bar(ctx, tr, bx, y, bw, bh, hp, maxHp, low ? 0xFFE53935 : 0xFFC62828, 0xFF2A0A0A, hpText);

		float mana = OrvWindowClient.mana;
		float maxMana = OrvWindowClient.maxMana;
		bar(ctx, tr, bx, y + 13, bw, bh, mana, maxMana, 0xFF2F7DE0, 0xFF0A1630,
				"MP " + (int) Math.ceil(mana) + "/" + (int) Math.ceil(maxMana));

		float sta = OrvWindowClient.stamina;
		float maxSta = OrvWindowClient.maxStamina;
		boolean ex = OrvWindowClient.exhausted;
		bar(ctx, tr, bx, y + 26, bw, bh, sta, maxSta, ex ? 0xFF8A3B3B : 0xFFE0B22F, 0xFF2A2208,
				"SP " + (int) Math.ceil(sta) + "/" + (int) Math.ceil(maxSta));
	}

	private static void bar(DrawContext ctx, TextRenderer tr, int x, int y, int w, int h,
							float cur, float max, int fill, int back, String text) {
		ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, BORDER);
		ctx.fill(x, y, x + w, y + h, back);
		float frac = max <= 0f ? 0f : Math.max(0f, Math.min(1f, cur / max));
		int fw = Math.round(w * frac);
		if (fw > 0) {
			ctx.fill(x, y, x + fw, y + h, fill);
		}
		ctx.drawTextWithShadow(tr, text, x + 3, y + 1, 0xFFFFFFFF);
	}
}
