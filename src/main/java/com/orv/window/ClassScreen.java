package com.orv.window;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Shown when a player joins without a class. The class icon sits in the middle, the arrows switch between
 * classes, the stats are on the left and the description is on the right. The choice can only be made once.
 */
public class ClassScreen extends Screen {
	private static final int BG_TOP = 0xF0070506;
	private static final int BG_BOTTOM = 0xF0170808;
	private static final int METAL = 0xFF4E4330;
	private static final int METAL_LIGHT = 0xFF8C7A52;
	private static final int BLOOD = 0xFFB02820;
	private static final int TEXT = 0xFFD9C79A;
	private static final int DIM = 0xFF9A8B6A;
	private static final int ICON_PX = 32;

	private int index = 0;
	private long openedAt = 0;

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

	private int scale() {
		return Math.max(2, Math.min(5, (this.height - 130) / ICON_PX));
	}

	private int iconSize() {
		return ICON_PX * scale();
	}

	private int iconTop() {
		return 38;
	}

	@Override
	protected void init() {
		openedAt = System.currentTimeMillis();
		int cx = this.width / 2;
		int half = iconSize() / 2;
		int midY = iconTop() + half - 10;
		this.addDrawableChild(ButtonWidget.builder(Text.literal("<"), b -> step(-1))
				.dimensions(cx - half - 34, midY, 22, 22).build());
		this.addDrawableChild(ButtonWidget.builder(Text.literal(">"), b -> step(1))
				.dimensions(cx + half + 12, midY, 22, 22).build());
		this.addDrawableChild(ButtonWidget.builder(Text.translatable("orvwindow.class.confirm"), b -> {
			String id = Classes.ALL.get(index).id();
			OrvWindowClient.myClass = id;
			OrvWindowClient.sendClassPick(id);
			this.close();
		}).dimensions(cx - 60, iconTop() + iconSize() + 34, 120, 20).build());
	}

	private void step(int d) {
		int n = Classes.ALL.size();
		index = ((index + d) % n + n) % n;
		openedAt = System.currentTimeMillis();
	}

	@Override
	public boolean keyPressed(int key, int scan, int mods) {
		if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_A) {
			step(-1);
			return true;
		}
		if (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_D) {
			step(1);
			return true;
		}
		return super.keyPressed(key, scan, mods);
	}

	@Override
	public void render(DrawContext ctx, int mx, int my, float delta) {
		this.renderBackground(ctx);
		ctx.fillGradient(0, 0, this.width, this.height, BG_TOP, BG_BOTTOM);

		Classes.Def d = Classes.ALL.get(index);
		int cx = this.width / 2;
		int sc = scale();
		int size = iconSize();
		int top = iconTop();
		float fade = Math.min(1f, (System.currentTimeMillis() - openedAt) / 250f);

		// title
		ctx.getMatrices().push();
		ctx.getMatrices().translate(cx, 9, 0);
		ctx.getMatrices().scale(1.5f, 1.5f, 1f);
		Text title = Text.translatable("orvwindow.class.title");
		ctx.drawText(this.textRenderer, title, -this.textRenderer.getWidth(title) / 2, 0, TEXT, true);
		ctx.getMatrices().pop();
		Text sub = Text.translatable("orvwindow.class.subtitle");
		ctx.drawText(this.textRenderer, sub, cx - this.textRenderer.getWidth(sub) / 2, 25, DIM, false);

		// icon frame
		int ix = cx - size / 2;
		frame(ctx, ix - 8, top - 8, size + 16, size + 16);
		ctx.fillGradient(ix, top, ix + size, top + size, 0xFF1B0F0D, 0xFF050404);
		drawIcon(ctx, d.id(), ix, top, sc, fade);

		// name + page dots
		Text name = Text.translatable("orvwindow.class." + d.id());
		ctx.getMatrices().push();
		ctx.getMatrices().translate(cx, top + size + 12, 0);
		ctx.getMatrices().scale(1.5f, 1.5f, 1f);
		ctx.drawText(this.textRenderer, name, -this.textRenderer.getWidth(name) / 2, 0, 0xFFFF9C8A, true);
		ctx.getMatrices().pop();
		int n = Classes.ALL.size();
		int dotsX = cx - (n * 10 - 4) / 2;
		for (int i = 0; i < n; i++) {
			ctx.fill(dotsX + i * 10, top + size + 28, dotsX + i * 10 + 6, top + size + 31, i == index ? BLOOD : METAL);
		}

		// panels
		int arrowW = 34;
		int panelW = Math.max(80, cx - size / 2 - arrowW - 24);
		int leftX = 12;
		int rightX = this.width - 12 - panelW;
		drawStats(ctx, d, leftX, top - 8, panelW);
		drawDescription(ctx, d, rightX, top - 8, panelW);

		super.render(ctx, mx, my, delta);
	}

	private void frame(DrawContext ctx, int x, int y, int w, int h) {
		ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF050404);
		ctx.fillGradient(x, y, x + w, y + h, METAL_LIGHT, 0xFF1C1710);
		ctx.fill(x + 2, y + 2, x + w - 2, y + h - 2, METAL);
		ctx.fill(x + 3, y + 3, x + w - 3, y + h - 3, 0xFF050404);
	}

	private void panel(DrawContext ctx, int x, int y, int w, int h, Text heading) {
		ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF050404);
		ctx.fillGradient(x, y, x + w, y + h, 0xFF17110D, 0xFF0A0807);
		ctx.fill(x, y, x + w, y + 1, METAL);
		ctx.drawText(this.textRenderer, heading, x + 8, y + 7, TEXT, true);
		ctx.fill(x + 8, y + 18, x + w - 8, y + 19, METAL);
	}

	private void drawStats(DrawContext ctx, Classes.Def d, int x, int y, int w) {
		int h = 18 + 7 * 20 + 14;
		panel(ctx, x, y, w, h, Text.translatable("orvwindow.class.stats"));
		int ry = y + 26;
		row(ctx, x, ry, w, "hp", String.valueOf((int) Math.round(20 + d.hp())), (20 + d.hp()) / 800.0);
		row(ctx, x, ry + 20, w, "mana", String.valueOf((int) d.mana()), d.mana() / 500.0);
		row(ctx, x, ry + 40, w, "stamina", String.valueOf((int) d.stamina()), d.stamina() / 600.0);
		row(ctx, x, ry + 60, w, "attack", signed(d.attack(), 1), d.attack() / 1.5);
		row(ctx, x, ry + 80, w, "armor", signed(d.armor(), 0), d.armor() / 3.0);
		row(ctx, x, ry + 100, w, "speed", pct(d.speed()), (d.speed() + 0.05) / 0.15);
		row(ctx, x, ry + 120, w, "atkspeed", pct(d.attackSpeed()), d.attackSpeed() / 0.15);
	}

	private void row(DrawContext ctx, int x, int y, int w, String key, String value, double ratio) {
		ctx.drawText(this.textRenderer, Text.translatable("orvwindow.stat." + key), x + 8, y, DIM, false);
		ctx.drawText(this.textRenderer, value, x + w - 8 - this.textRenderer.getWidth(value), y, TEXT, false);
		int bw = w - 16;
		int by = y + 11;
		ctx.fill(x + 8, by, x + 8 + bw, by + 4, 0xFF050404);
		int fw = (int) Math.round(bw * Math.max(0.04, Math.min(1.0, ratio)));
		ctx.fillGradient(x + 8, by, x + 8 + fw, by + 4, 0xFFC02A22, 0xFF6C100D);
		ctx.fill(x + 8, by, x + 8 + fw, by + 1, 0x40FFFFFF);
	}

	private void drawDescription(DrawContext ctx, Classes.Def d, int x, int y, int w) {
		int h = 18 + 7 * 20 + 14;
		panel(ctx, x, y, w, h, Text.translatable("orvwindow.class.about"));
		List<OrderedText> lines = this.textRenderer.wrapLines(Text.translatable("orvwindow.class." + d.id() + ".desc"), w - 16);
		int ty = y + 26;
		for (OrderedText l : lines) {
			ctx.drawText(this.textRenderer, l, x + 8, ty, TEXT, false);
			ty += 10;
		}
		ty += 6;
		ctx.drawText(this.textRenderer, Text.translatable("orvwindow.class.perk"), x + 8, ty, 0xFFFF9C8A, false);
		ty += 11;
		for (OrderedText l : this.textRenderer.wrapLines(Text.translatable("orvwindow.class." + d.id() + ".perk"), w - 16)) {
			ctx.drawText(this.textRenderer, l, x + 8, ty, DIM, false);
			ty += 10;
		}
	}

	private static String signed(double v, int decimals) {
		String s = decimals == 0 ? String.valueOf(Math.round(v)) : String.format(java.util.Locale.ROOT, "%." + decimals + "f", v);
		return v > 0 ? "+" + s : s;
	}

	private static String pct(double v) {
		long p = Math.round(v * 100);
		return (p > 0 ? "+" : "") + p + "%";
	}

	// ------------------------------------------------------------------ icons (drawn with code, no textures)

	private void drawIcon(DrawContext ctx, String id, int ox, int oy, int sc, float fade) {
		int a = Math.round(255 * fade) << 24;
		for (int y = 0; y < ICON_PX; y++) {
			for (int x = 0; x < ICON_PX; x++) {
				int c = pixel(id, x + 0.5 - ICON_PX / 2.0, y + 0.5 - ICON_PX / 2.0);
				if (c != 0) {
					ctx.fill(ox + x * sc, oy + y * sc, ox + (x + 1) * sc, oy + (y + 1) * sc, (c & 0x00FFFFFF) | a);
				}
			}
		}
	}

	private static int pixel(String id, double dx, double dy) {
		return switch (id) {
			case "lightbender" -> lightbender(dx, dy);
			case "rogue" -> rogue(dx, dy);
			case "wuxia" -> wuxia(dx, dy);
			default -> slayer(dx, dy);
		};
	}

	/** A glowing prism with rays of light. */
	private static int lightbender(double dx, double dy) {
		double ax = Math.abs(dx);
		double ay = Math.abs(dy);
		double d = ax + ay;
		if (d <= 2.0) {
			return 0xFFFFFFFF;
		}
		if (d <= 8.5) {
			return d < 5.5 ? 0xFFFFE9A0 : 0xFFE9A83A;
		}
		if (d <= 9.8) {
			return 0xFF4A3412;
		}
		if ((ax <= 0.7 && ay <= 15.5) || (ay <= 0.7 && ax <= 15.5)) {
			return 0xFFF5D88A;
		}
		if (Math.abs(ax - ay) <= 0.7 && d >= 13 && d <= 21) {
			return 0xFFB88A3A;
		}
		return 0;
	}

	/** Two crossed daggers. */
	private static int rogue(double dx, double dy) {
		double y = dy - 4;
		int c = dagger((-dx - y) / Math.sqrt(2), (dx - y) / Math.sqrt(2));
		if (c != 0) {
			return c;
		}
		return dagger((dx - y) / Math.sqrt(2), (-dx - y) / Math.sqrt(2));
	}

	private static int dagger(double s, double u) {
		double au = Math.abs(u);
		if (s >= 0 && s <= 13) {
			double w = 1.9 * (1 - Math.max(0, s - 6) / 8.0);
			if (au <= w) {
				return au < 0.5 ? 0xFFE8EEF4 : 0xFF9FA9B5;
			}
		}
		if (s >= -2 && s < 0 && au <= 4.5) {
			return 0xFF8A2A24;
		}
		if (s >= -9 && s < -2 && au <= 1.3) {
			return 0xFF3A2A20;
		}
		if (s >= -11 && s < -9 && au <= 2.0) {
			return 0xFF8A2A24;
		}
		return 0;
	}

	/** A taiji circle: body and inner energy in balance. */
	private static int wuxia(double dx, double dy) {
		double r = Math.sqrt(dx * dx + dy * dy);
		if (r > 15) {
			return 0;
		}
		if (r > 13.2) {
			return 0xFFB8A472;
		}
		if (r > 12) {
			return 0xFF2A2218;
		}
		int light = 0xFFE8E0C8;
		int dark = 0xFF1A1614;
		int col = dx >= 0 ? light : dark;
		if (Math.sqrt(dx * dx + (dy + 6) * (dy + 6)) <= 6) {
			col = light;
		}
		if (Math.sqrt(dx * dx + (dy - 6) * (dy - 6)) <= 6) {
			col = dark;
		}
		if (Math.sqrt(dx * dx + (dy + 6) * (dy + 6)) <= 1.8) {
			col = dark;
		}
		if (Math.sqrt(dx * dx + (dy - 6) * (dy - 6)) <= 1.8) {
			col = light;
		}
		return col;
	}

	/** A greatsword in front of a blood-red moon. */
	private static int slayer(double dx, double dy) {
		double ax = Math.abs(dx);
		double r = Math.sqrt(dx * dx + dy * dy);
		// blade
		if (dy >= -14 && dy <= 6) {
			double w = dy < -9 ? 2.4 * (dy + 14) / 5.0 : 2.4;
			if (ax <= w) {
				return ax < 0.6 ? 0xFF6E7884 : 0xFFD2D9E0;
			}
		}
		if (dy > 6 && dy <= 8 && ax <= 6.5) {
			return 0xFF8A6A2A;
		}
		if (dy > 8 && dy <= 13 && ax <= 1.3) {
			return 0xFF3A2A20;
		}
		if (dy > 13 && dy <= 15 && ax <= 2.2) {
			return 0xFF8A6A2A;
		}
		if (r <= 14.5) {
			return r > 13 ? 0xFF8A2A24 : (r < 8 ? 0xFF5A1412 : 0xFF3A0F0E);
		}
		return 0;
	}
}
