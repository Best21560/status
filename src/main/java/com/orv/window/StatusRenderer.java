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

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Draws the floating window in the world, in front of a player. */
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

	// all blue, no white
	private static final int FILL_TOP = 0xD02E9FF0;
	private static final int FILL_BOTTOM = 0xD02E9FF0;
	private static final int BORDER = 0xFF2E9FF0;
	private static final int BRACKET = 0xFF52B3F5;
	private static final int BAND = 0x283BB5FF;
	private static final int BAND_CLEAR = 0x003BB5FF;

	private static final VertexConsumerProvider.Immediate TEXT = VertexConsumerProvider.immediate(new BufferBuilder(1024));

	private record Frame(Vec3d center, Vec3d f, Vec3d right) {
	}

	private static float textHeight(int rows) {
		return rows * LINE_H + 18f;
	}

	public static void render(WorldRenderContext ctx) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null) {
			return;
		}
		float td = ctx.tickDelta();
		Vec3d cam = ctx.camera().getPos();
		MatrixStack ms = ctx.matrixStack();
		boolean firstPerson = mc.options.getPerspective().isFirstPerson();

		WindowAnim self = OrvWindowClient.SELF;
		if (self.visible()) {
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

	/** Is the player's crosshair on the "+" button of their own window? */
	public static boolean aimingAtPlus(PlayerEntity player, float td) {
		int rows = PlayerData.lines().size();
		double halfHFull = textHeight(rows) * SCALE / 2.0;
		Frame fr = frame(player, td, true);
		Vec3d origin = player.getCameraPosVec(td);
		Vec3d dir = player.getRotationVec(td);
		double denom = dir.dotProduct(fr.f());
		if (Math.abs(denom) < 1e-4) {
			return false;
		}
		double t = fr.center().subtract(origin).dotProduct(fr.f()) / denom;
		if (t <= 0 || t > 6) {
			return false;
		}
		Vec3d rel = origin.add(dir.multiply(t)).subtract(fr.center());
		double lx = rel.dotProduct(fr.right());
		double ly = rel.y;
		double tx = (lx + HALF_W) / SCALE;
		double ty = (halfHFull - ly) / SCALE;
		return tx >= BTN_X0 - 3 && tx <= BTN_X1 + 3 && ty >= BTN_Y0 - 3 && ty <= BTN_Y1 + 3;
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

		if (self && showText && p > 0.85f && OrvWindowClient.canGrant(player)) {
			float a = clamp01((p - 0.85f) / 0.15f);
			drawPlusButton(b, m, center, right, up, halfHFull, a, aimingAtPlus(player, td));
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
									   double halfHFull, float alpha, boolean aimed) {
		double x0 = -HALF_W + BTN_X0 * SCALE;
		double x1 = -HALF_W + BTN_X1 * SCALE;
		double yTop = halfHFull - BTN_Y0 * SCALE;
		double yBot = halfHFull - BTN_Y1 * SCALE;

		int fill = withAlpha(aimed ? 0xFF2E9FF0 : 0xFF1B5FC8, alpha);
		int line = withAlpha(aimed ? 0xFFA9DCFF : 0xFF52B3F5, alpha);
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
