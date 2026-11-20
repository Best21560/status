package com.orv.window;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.List;

/** Shown when a player joins without a class: pick one of the starting classes (once). */
public class ClassScreen extends Screen {
	private static final int BG = 0xF0070506;
	private static final int CARD = 0xFF15110D;
	private static final int METAL = 0xFF4E4330;
	private static final int METAL_LIGHT = 0xFF8C7A52;
	private static final int BLOOD = 0xFFB02820;
	private static final int TEXT = 0xFFD9C79A;
	private static final int DIM = 0xFF9A8B6A;

	private int selected = -1;
	private ButtonWidget confirm;

	public ClassScreen() {
		super(Text.translatable("orvwindow.class.title"));
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	private int cardW() {
		return Math.max(70, Math.min(100, (this.width - 40 - 30) / 4));
	}

	private int cardH() {
		return Math.min(170, this.height - 90);
	}

	private int cardX(int i) {
		int total = cardW() * 4 + 30;
		return (this.width - total) / 2 + i * (cardW() + 10);
	}

	private int cardY() {
		return 38;
	}

	@Override
	protected void init() {
		confirm = ButtonWidget.builder(Text.translatable("orvwindow.class.confirm"), b -> {
			if (selected >= 0) {
				String id = Classes.ALL.get(selected).id();
				OrvWindowClient.myClass = id;
				OrvWindowClient.sendClassPick(id);
				this.close();
			}
		}).dimensions(this.width / 2 - 60, cardY() + cardH() + 12, 120, 20).build();
		confirm.active = selected >= 0;
		this.addDrawableChild(confirm);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button == 0) {
			for (int i = 0; i < Classes.ALL.size(); i++) {
				int x = cardX(i);
				if (mx >= x && mx < x + cardW() && my >= cardY() && my < cardY() + cardH()) {
					selected = i;
					confirm.active = true;
					return true;
				}
			}
		}
		return super.mouseClicked(mx, my, button);
	}

	@Override
	public void render(DrawContext ctx, int mx, int my, float delta) {
		this.renderBackground(ctx);
		ctx.fillGradient(0, 0, this.width, this.height, BG, 0xF0150808);

		// title
		ctx.getMatrices().push();
		ctx.getMatrices().translate(this.width / 2f, 10, 0);
		ctx.getMatrices().scale(1.5f, 1.5f, 1f);
		Text title = Text.translatable("orvwindow.class.title");
		ctx.drawText(this.textRenderer, title, -this.textRenderer.getWidth(title) / 2, 0, TEXT, true);
		ctx.getMatrices().pop();
		Text sub = Text.translatable("orvwindow.class.subtitle");
		ctx.drawText(this.textRenderer, sub, (this.width - this.textRenderer.getWidth(sub)) / 2, 25, DIM, false);

		for (int i = 0; i < Classes.ALL.size(); i++) {
			drawCard(ctx, i, mx, my);
		}
		super.render(ctx, mx, my, delta);
	}

	private void drawCard(DrawContext ctx, int i, int mx, int my) {
		Classes.Def d = Classes.ALL.get(i);
		int x = cardX(i);
		int y = cardY();
		int w = cardW();
		int h = cardH();
		boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
		boolean sel = i == selected;
		int border = sel ? BLOOD : (hover ? METAL_LIGHT : METAL);

		ctx.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xFF050404);
		ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, border);
		ctx.fillGradient(x, y, x + w, y + h, sel ? 0xFF2A1210 : CARD, 0xFF0A0807);

		int tx = x + 6;
		int ty = y + 7;
		Text name = Text.translatable("orvwindow.class." + d.id());
		ctx.drawText(this.textRenderer, name, tx, ty, sel ? 0xFFFF9C8A : TEXT, true);
		ctx.fill(tx, ty + 11, x + w - 6, ty + 12, border);
		ty += 17;

		List<OrderedText> desc = this.textRenderer.wrapLines(Text.translatable("orvwindow.class." + d.id() + ".desc"), w - 12);
		int maxLines = Math.max(1, (h - 17 - 7 - 6 * 10 - 8) / 9);
		for (int k = 0; k < desc.size() && k < maxLines; k++) {
			ctx.drawText(this.textRenderer, desc.get(k), tx, ty, DIM, false);
			ty += 9;
		}

		// stats at the bottom of the card
		int sy = y + h - 6 * 10 - 4;
		stat(ctx, tx, sy, "hp", String.valueOf((int) Math.round(20 + d.hp())));
		stat(ctx, tx, sy + 10, "mana", String.valueOf((int) d.mana()));
		stat(ctx, tx, sy + 20, "stamina", String.valueOf((int) d.stamina()));
		stat(ctx, tx, sy + 30, "attack", signed(d.attack(), 1));
		stat(ctx, tx, sy + 40, "armor", signed(d.armor(), 0));
		stat(ctx, tx, sy + 50, "speed", (d.speed() >= 0 ? "+" : "") + Math.round(d.speed() * 100) + "%");
	}

	private static String signed(double v, int decimals) {
		String s = decimals == 0 ? String.valueOf(Math.round(v)) : String.format(java.util.Locale.ROOT, "%." + decimals + "f", v);
		return v > 0 ? "+" + s : s;
	}

	private void stat(DrawContext ctx, int x, int y, String key, String value) {
		ctx.drawText(this.textRenderer, Text.translatable("orvwindow.stat." + key), x, y, DIM, false);
		ctx.drawText(this.textRenderer, value, x + cardW() - 12 - this.textRenderer.getWidth(value), y, TEXT, false);
	}
}
