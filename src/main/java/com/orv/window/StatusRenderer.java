package com.orv.window;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Draws the floating window in the world, in front of a player, and maps the mouse cursor onto it. */
public class StatusRenderer {
	// window width in "text pixels"; world width = TEXT_W * SCALE. Height grows with the number of lines.
	private static final float TEXT_W = 160f;
	private static final float LINE_H = 15f;
	private static final float SCALE = 1.1f / TEXT_W;

	private static final double HALF_W = TEXT_W * SCALE / 2.0;
	private static final double DISTANCE = 1.7;

	// "+" button, in text pixels (sits on the unique-skill row)
	private static final float ROW_Y = 12f + PlayerData.UNIQUE_ROW * LINE_H;
	private static final float BTN_X0 = TEXT_W - 34f;
	private static final float BTN_X1 = TEXT_W - 10f;
	private static final float BTN_Y0 = ROW_Y - 5f;
	private static final float BTN_Y1 = ROW_Y + 13f;

	// stat panel beside the window (to the right), in text pixels
	private static final float ST_X0 = TEXT_W + 8f;
	private static final float ST_X1 = ST_X0 + 88f;
	private static final float ST_Y0 = 8f;
	private static final float ST_ROW0 = ST_Y0 + 17f;
	private static final float ST_ROW_H = 15f;
	private static final float ST_Y1 = ST_ROW0 + 5 * ST_ROW_H + 17f;
	private static final float ST_BTN_X0 = ST_X1 - 20f;
	private static final float ST_BTN_X1 = ST_X1 - 5f;

	// all blue, no white
	private static final int FILL_TOP = 0xD02E9FF0;
	private static final int FILL_BOTTOM = 0xD02E9FF0;
	private static final int BORDER = 0xFF2E9FF0;
	private static final int BRACKET = 0xFF52B3F5;
	private static final int BAND = 0x283BB5FF;
	private static final int BAND_CLEAR = 0x003BB5FF;
	private static final int ROW_HIGHLIGHT = 0x5066C2FF;

	private static final VertexConsumerProvider.Immediate TEXT = VertexConsumerProvider.immediate(new BufferBuilder(1024));

	// ---- what the cursor is on ----
	public enum Kind {
		NONE, NAME, PLUS, SKILL, STAT
	}

	public record Pick(Kind kind, int index) {
		public static final Pick NONE = new Pick(Kind.NONE, -1);
	}

	private static volatile Pick hover = Pick.NONE;

	public static void setHover(Pick p) {
		hover = p == null ? Pick.NONE : p;
	}

	// ---- data from the last drawn frame, used to turn a mouse position into a window position ----
	private static Matrix4f lastInvPV = null;
	private static boolean pickReady = false;
	private static Vec3d pickCenter = Vec3d.ZERO;
	private static Vec3d pickRight = Vec3d.ZERO;
	private static Vec3d pickF = Vec3d.ZERO;
	private static double pickHalfH = 0;
	private static int pickRows = 0;

	private record Frame(Vec3d center, Vec3d f, Vec3d right) {
	}

	private static float textHeight(int rows) {
		return rows * LINE_H + 18f;
	}

	public static void render(WorldRenderContext ctx) {
		MinecraftClient mc = MinecraftClient.getInstance();
		pickReady = false;
		if (mc.world == null || mc.player == null) {
			return;
		}
		float td = ctx.tickDelta();
		Vec3d cam = ctx.camera().getPos();
		MatrixStack ms = ctx.matrixStack();
		boolean firstPerson = mc.options.getPerspective().isFirstPerson();

		WindowAnim self = OrvWindowClient.SELF;
		if (self.visible()) {
			lastInvPV = new Matrix4f(ctx.projectionMatrix()).mul(ms.peek().getPositionMatrix()).invert();
			float p = self.update();
			if (p > 0f) {
				drawFor(mc, ms, cam, mc.player, td, firstPerson, true, p);
			}
		}

		Iterator<Map.Entry<UUID, WindowAnim>> it = OrvWindowClient.OTHERS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, WindowAnim> e = it.next();
			WindowAnim a = e.getValue();
			float p = a.update();
			if (!a.open && p <= 0f) {
				it.remove();
				continue;
			}
			PlayerEntity pl = mc.world.getPlayerByUuid(e.getKey());
			if (pl != null && pl != mc.player && !pl.isInvisible() && p > 0f) {
				drawFor(mc, ms, cam, pl, td, false, false, p);
			}
		}
	}

	private static float clamp01(float x) {
		return x < 0f ? 0f : (x > 1f ? 1f : x);
	}

	private static float easeOut(float x) {
		float k = 1f - x;
		return 1f - k * k * k;
	}

	private static int withAlpha(int argb, float a) {
		int base = (argb >>> 24) & 255;
		int na = Math.round(base * clamp01(a));
		return (na << 24) | (argb & 0xFFFFFF);
	}

	private static Frame frame(PlayerEntity player, float td, boolean self) {
		double yawDeg = self
				? player.getYaw(td)
				: MathHelper.lerpAngleDegrees(td, player.prevHeadYaw, player.headYaw);
		double yr = Math.toRadians(yawDeg);
		// f = horizontal direction the player faces; right = viewer's right when looking along f
		Vec3d f = new Vec3d(-Math.sin(yr), 0, Math.cos(yr));
		Vec3d right = new Vec3d(-f.z, 0, f.x);
		Vec3d eye = player.getCameraPosVec(td);
		Vec3d center = eye.add(f.multiply(DISTANCE)).add(0, -0.05, 0);
		return new Frame(center, f, right);
	}

	/**
	 * Which part of my window is under the mouse? (mouse position in scaled GUI pixels,
	 * sw/sh = scaled screen size). Uses the window as it was drawn in the last frame.
	 */
	public static Pick pick(double mx, double my, int sw, int sh) {
		if (!pickReady || lastInvPV == null || sw <= 0 || sh <= 0) {
			return Pick.NONE;
		}
		float nx = (float) (2.0 * mx / sw - 1.0);
		float ny = (float) (1.0 - 2.0 * my / sh);

		Vector4f a = new Vector4f(nx, ny, -1f, 1f);
		Vector4f c = new Vector4f(nx, ny, 1f, 1f);
		lastInvPV.transform(a);
		lastInvPV.transform(c);
		Vec3d p0 = new Vec3d(a.x / a.w, a.y / a.w, a.z / a.w);
		Vec3d p1 = new Vec3d(c.x / c.w, c.y / c.w, c.z / c.w);
		Vec3d dir = p1.subtract(p0).normalize();

		double denom = dir.dotProduct(pickF);
		if (Math.abs(denom) < 1e-5) {
			return Pick.NONE;
		}
		double t = pickCenter.subtract(p0).dotProduct(pickF) / denom;
		if (t <= 0) {
			return Pick.NONE;
		}
		Vec3d rel = p0.add(dir.multiply(t)).subtract(pickCenter);
		double tx = (rel.dotProduct(pickRight) + HALF_W) / SCALE;
		double ty = (pickHalfH - rel.y) / SCALE;
		if (tx >= ST_X0 && tx <= ST_X1 && ty >= ST_ROW0 && ty < ST_ROW0 + 5 * ST_ROW_H) {
			return new Pick(Kind.STAT, (int) ((ty - ST_ROW0) / ST_ROW_H));
		}
		if (tx < 0 || tx > TEXT_W || ty < 0 || ty > textHeight(pickRows)) {
			return Pick.NONE;
		}

		MinecraftClient mc = MinecraftClient.getInstance();
		if (OrvWindowClient.canGrant(mc.player)
				&& tx >= BTN_X0 - 3 && tx <= BTN_X1 + 3 && ty >= BTN_Y0 - 3 && ty <= BTN_Y1 + 3) {
			return new Pick(Kind.PLUS, 0);
		}

		int row = (int) Math.floor((ty - 9f) / LINE_H);
		if (row < 0 || row >= pickRows) {
			return Pick.NONE;
		}
		if (row == 0) {
			return new Pick(Kind.NAME, 0);
		}
		int idx = row - (PlayerData.UNIQUE_ROW + 2);
		if (idx >= 0 && idx < PlayerData.shownOwnedCount()) {
			return new Pick(Kind.SKILL, idx);
		}
		return Pick.NONE;
	}

	private static void drawFor(MinecraftClient mc, MatrixStack ms, Vec3d cam, PlayerEntity player,
								float td, boolean showText, boolean self, float p) {
		Frame fr = frame(player, td, self);
		Vec3d f = fr.f();
		Vec3d right = fr.right();
		Vec3d up = new Vec3d(0, 1, 0);
		Vec3d center = fr.center().subtract(cam);

		List<Text> lines = self ? PlayerData.lines() : null;
		int rows = lines != null ? lines.size() : PlayerData.BASE_ROWS;
		double halfHFull = textHeight(rows) * SCALE / 2.0;

		if (self && showText && p >= 0.95f) {
			pickReady = true;
			pickCenter = center;
			pickRight = right;
			pickF = f;
			pickHalfH = halfHFull;
			pickRows = rows;
		}

		// opening: first a line grows wide, then it grows tall (closing plays this backwards)
		float w = easeOut(clamp01(p / 0.5f));
		float h = easeOut(clamp01((p - 0.4f) / 0.6f));
		double hw = Math.max(0.01, HALF_W * w);
		double hh = Math.max(0.006, halfHFull * h);

		Matrix4f m = ms.peek().getPositionMatrix();

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(GameRenderer::getPositionColorProgram);
		RenderSystem.disableCull();
		RenderSystem.depthMask(false);
		if (self) {
			RenderSystem.disableDepthTest();
		} else {
			RenderSystem.enableDepthTest();
		}

		BufferBuilder b = Tessellator.getInstance().getBuffer();
		b.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

		double t = Math.min(0.025, hh * 0.9);
		quad(b, m, center, right, up, -hw, -hh, hw, hh, FILL_BOTTOM, FILL_TOP);

		if (h > 0.6f) {
			// slow scan band moving down the screen
			double period = 2.6;
			double ph = ((System.nanoTime() / 1_000_000_000.0) % period) / period;
			double by = hh - ph * 2.0 * hh;
			double bh = 0.09;
			double lo0 = Math.max(-hh, by - bh);
			double lo1 = Math.min(hh, by);
			if (lo1 > lo0) {
				quad(b, m, center, right, up, -hw + t, lo0, hw - t, lo1, BAND_CLEAR, BAND);
			}
			double up0 = Math.max(-hh, by);
			double up1 = Math.min(hh, by + bh);
			if (up1 > up0) {
				quad(b, m, center, right, up, -hw + t, up0, hw - t, up1, BAND, BAND_CLEAR);
			}
		}

		// border
		quad(b, m, center, right, up, -hw, hh - t, hw, hh, BORDER, BORDER);
		quad(b, m, center, right, up, -hw, -hh, hw, -hh + t, BORDER, BORDER);
		quad(b, m, center, right, up, -hw, -hh, -hw + t, hh, BORDER, BORDER);
		quad(b, m, center, right, up, hw - t, -hh, hw, hh, BORDER, BORDER);

		if (h > 0.6f) {
			// futuristic corner brackets
			double len = 0.14;
			double th = 0.022;
			for (int sx = -1; sx <= 1; sx += 2) {
				for (int sy = -1; sy <= 1; sy += 2) {
					quad(b, m, center, right, up, sx * hw, sy * hh, sx * (hw - len), sy * (hh - th), BRACKET, BRACKET);
					quad(b, m, center, right, up, sx * hw, sy * hh, sx * (hw - th), sy * (hh - len), BRACKET, BRACKET);
				}
			}
		}

		if (self && showText && p > 0.85f) {
			float a = clamp01((p - 0.85f) / 0.15f);
			Pick hv = hover;

			// highlight the row under the cursor
			int hrow = -1;
			if (hv.kind() == Kind.NAME) {
				hrow = 0;
			} else if (hv.kind() == Kind.SKILL) {
				hrow = PlayerData.UNIQUE_ROW + 2 + hv.index();
			}
			if (hrow >= 0) {
				double y0 = halfHFull - (12f + hrow * LINE_H - 3f) * SCALE;
				double y1 = halfHFull - (12f + hrow * LINE_H + 12f) * SCALE;
				double x0 = -HALF_W + 8f * SCALE;
				double x1 = HALF_W - 8f * SCALE;
				int col = withAlpha(ROW_HIGHLIGHT, a);
				quad(b, m, center, right, up, x0, y1, x1, y0, col, col);
			}

			if (OrvWindowClient.canGrant(player)) {
				drawPlusButton(b, m, center, right, up, halfHFull, a, hv.kind() == Kind.PLUS);
			}
			drawStatPanel(b, m, center, right, up, halfHFull, a, hv.kind() == Kind.STAT ? hv.index() : -1);
		}

		Tessellator.getInstance().draw();

		if (showText && lines != null && p > 0.85f) {
			float a = clamp01((p - 0.85f) / 0.15f);
			drawText(mc, m, center, right, up, f, a, lines, halfHFull);
		}

		RenderSystem.enableCull();
		RenderSystem.depthMask(true);
		RenderSystem.enableDepthTest();
		RenderSystem.disableBlend();
	}

	private static void drawPlusButton(BufferBuilder b, Matrix4f m, Vec3d c, Vec3d right, Vec3d up,
									   double halfHFull, float alpha, boolean hovered) {
		double x0 = -HALF_W + BTN_X0 * SCALE;
		double x1 = -HALF_W + BTN_X1 * SCALE;
		double yTop = halfHFull - BTN_Y0 * SCALE;
		double yBot = halfHFull - BTN_Y1 * SCALE;

		int fill = withAlpha(hovered ? 0xFF2E9FF0 : 0xFF1B5FC8, alpha);
		int line = withAlpha(hovered ? 0xFFA9DCFF : 0xFF52B3F5, alpha);
		double e = 0.007;

		quad(b, m, c, right, up, x0, yBot, x1, yTop, fill, fill);
		quad(b, m, c, right, up, x0, yTop - e, x1, yTop, line, line);
		quad(b, m, c, right, up, x0, yBot, x1, yBot + e, line, line);
		quad(b, m, c, right, up, x0, yBot, x0 + e, yTop, line, line);
		quad(b, m, c, right, up, x1 - e, yBot, x1, yTop, line, line);

		// plus sign
		double cx = (x0 + x1) / 2.0;
		double cy = (yTop + yBot) / 2.0;
		double len = Math.min(x1 - x0, yTop - yBot) * 0.28;
		double th = 0.007;
		quad(b, m, c, right, up, cx - len, cy - th, cx + len, cy + th, line, line);
		quad(b, m, c, right, up, cx - th, cy - len, cx + th, cy + len, line, line);
	}

	private static void drawStatPanel(BufferBuilder b, Matrix4f m, Vec3d c, Vec3d right, Vec3d up,
									  double halfHFull, float alpha, int hoverRow) {
		double x0 = -HALF_W + ST_X0 * SCALE;
		double x1 = -HALF_W + ST_X1 * SCALE;
		double yTop = halfHFull - ST_Y0 * SCALE;
		double yBot = halfHFull - ST_Y1 * SCALE;

		int fill = withAlpha(0xD02E9FF0, alpha);
		int line = withAlpha(0xFF2E9FF0, alpha);
		double e = 0.01;
		quad(b, m, c, right, up, x0, yBot, x1, yTop, fill, fill);
		quad(b, m, c, right, up, x0, yTop - e, x1, yTop, line, line);
		quad(b, m, c, right, up, x0, yBot, x1, yBot + e, line, line);
		quad(b, m, c, right, up, x0, yBot, x0 + e, yTop, line, line);
		quad(b, m, c, right, up, x1 - e, yBot, x1, yTop, line, line);

		boolean canSpend = OrvWindowClient.myStatPoints > 0;
		for (int i = 0; i < 5; i++) {
			double ry0 = halfHFull - (ST_ROW0 + i * ST_ROW_H) * SCALE;
			double ry1 = halfHFull - (ST_ROW0 + (i + 1) * ST_ROW_H) * SCALE;
			if (i == hoverRow) {
				int hl = withAlpha(ROW_HIGHLIGHT, alpha);
				quad(b, m, c, right, up, x0 + 4 * SCALE, ry1, x1 - 4 * SCALE, ry0, hl, hl);
			}
			if (!canSpend) {
				continue;
			}
			double bx0 = -HALF_W + ST_BTN_X0 * SCALE;
			double bx1 = -HALF_W + ST_BTN_X1 * SCALE;
			double by0 = ry1 + 0.012;
			double by1 = ry0 - 0.012;
			boolean hv = i == hoverRow;
			int bf = withAlpha(hv ? 0xFF2E9FF0 : 0xFF1B5FC8, alpha);
			int bl = withAlpha(hv ? 0xFFA9DCFF : 0xFF52B3F5, alpha);
			double t = 0.005;
			quad(b, m, c, right, up, bx0, by0, bx1, by1, bf, bf);
			quad(b, m, c, right, up, bx0, by1 - t, bx1, by1, bl, bl);
			quad(b, m, c, right, up, bx0, by0, bx1, by0 + t, bl, bl);
			quad(b, m, c, right, up, bx0, by0, bx0 + t, by1, bl, bl);
			quad(b, m, c, right, up, bx1 - t, by0, bx1, by1, bl, bl);
			double cx = (bx0 + bx1) / 2.0;
			double cy = (by0 + by1) / 2.0;
			double len = Math.min(bx1 - bx0, by1 - by0) * 0.30;
			double th = 0.005;
			quad(b, m, c, right, up, cx - len, cy - th, cx + len, cy + th, bl, bl);
			quad(b, m, c, right, up, cx - th, cy - len, cx + th, cy + len, bl, bl);
		}
	}

	private static void drawText(MinecraftClient mc, Matrix4f m, Vec3d center, Vec3d right, Vec3d up, Vec3d f,
								 float alpha, List<Text> lines, double halfHFull) {
		int a = Math.max(8, (int) (alpha * 255f));
		int color = (a << 24) | 0xFFFFFF;

		TextRenderer tr = mc.textRenderer;
		float s = SCALE;

		// top-left corner of the window, nudged slightly toward the viewer (-f)
		Vec3d tl = center
				.add(right.multiply(-HALF_W))
				.add(up.multiply(halfHFull))
				.add(f.multiply(-0.002));

		// local text space: +x -> right, +y (down) -> -up
		Matrix4f local = new Matrix4f(
				(float) (right.x * s), (float) (right.y * s), (float) (right.z * s), 0f,
				(float) (-up.x * s), (float) (-up.y * s), (float) (-up.z * s), 0f,
				(float) (-f.x * s), (float) (-f.y * s), (float) (-f.z * s), 0f,
				(float) tl.x, (float) tl.y, (float) tl.z, 1f
		);
		Matrix4f tm = new Matrix4f(m).mul(local);

		for (int i = 0; i < lines.size(); i++) {
			Text line = lines.get(i);
			float w = tr.getWidth(line);
			float x = (TEXT_W - w) / 2f;
			float y = 12f + i * LINE_H;
			tr.draw(line, x, y, color, true, tm, TEXT,
					TextRenderer.TextLayerType.SEE_THROUGH, 0, 0xF000F0);
		}

		// stat panel beside the window
		float boxW = ST_X1 - ST_X0;
		Text head = Text.translatable("orvwindow.stat.title");
		tr.draw(head, ST_X0 + (boxW - tr.getWidth(head)) / 2f, ST_Y0 + 5f, color, true, tm, TEXT,
				TextRenderer.TextLayerType.SEE_THROUGH, 0, 0xF000F0);
		String[] names = {"STR", "DEX", "INT", "CON", "WIL"};
		int[] stats = OrvWindowClient.myStats;
		for (int i = 0; i < 5; i++) {
			float ry = ST_ROW0 + i * ST_ROW_H + 3f;
			tr.draw(names[i], ST_X0 + 8f, ry, color, true, tm, TEXT, TextRenderer.TextLayerType.SEE_THROUGH, 0, 0xF000F0);
			String v = String.valueOf(stats[i]);
			float vx = OrvWindowClient.myStatPoints > 0 ? ST_BTN_X0 - 6f - tr.getWidth(v) : ST_X1 - 10f - tr.getWidth(v);
			tr.draw(v, vx, ry, color, true, tm, TEXT, TextRenderer.TextLayerType.SEE_THROUGH, 0, 0xF000F0);
		}
		Text pts = Text.translatable("orvwindow.stat.points", OrvWindowClient.myStatPoints);
		tr.draw(pts, ST_X0 + (boxW - tr.getWidth(pts)) / 2f, ST_ROW0 + 5 * ST_ROW_H + 5f, color, true, tm, TEXT,
				TextRenderer.TextLayerType.SEE_THROUGH, 0, 0xF000F0);
		TEXT.draw();
	}

	/** One rectangle on the window plane. x runs along "right", y along "up". Bottom/top colors make a gradient. */
	private static void quad(BufferBuilder b, Matrix4f m, Vec3d c, Vec3d right, Vec3d up,
							 double x0, double y0, double x1, double y1, int colorBottom, int colorTop) {
		vertex(b, m, c, right, up, x0, y0, colorBottom);
		vertex(b, m, c, right, up, x1, y0, colorBottom);
		vertex(b, m, c, right, up, x1, y1, colorTop);
		vertex(b, m, c, right, up, x0, y1, colorTop);
	}

	private static void vertex(BufferBuilder b, Matrix4f m, Vec3d c, Vec3d right, Vec3d up,
							   double x, double y, int argb) {
		float px = (float) (c.x + right.x * x + up.x * y);
		float py = (float) (c.y + right.y * x + up.y * y);
		float pz = (float) (c.z + right.z * x + up.z * y);
		int a = (argb >>> 24) & 255;
		int r = (argb >>> 16) & 255;
		int g = (argb >>> 8) & 255;
		int bl = argb & 255;
		b.vertex(m, px, py, pz).color(r, g, bl, a).next();
	}
}
