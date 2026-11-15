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

import java.util.List;
import java.util.UUID;

/** Draws the floating window in the world, in front of a player. */
public class StatusRenderer {
	// size of the window in "text pixels"; world size = TEXT_W * SCALE
	private static final float TEXT_W = 160f;
	private static final float TEXT_H = 180f;
	private static final float SCALE = 1.1f / TEXT_W;

	private static final double HALF_W = TEXT_W * SCALE / 2.0;
	private static final double HALF_H = TEXT_H * SCALE / 2.0;
	private static final double DISTANCE = 1.7;

	private static final int FILL_TOP = 0xD0309AD6;
	private static final int FILL_BOTTOM = 0xD07FD6FF;
	private static final int BORDER = 0xFF9BE3FF;

	private static final VertexConsumerProvider.Immediate TEXT = VertexConsumerProvider.immediate(new BufferBuilder(1024));

	public static void render(WorldRenderContext ctx) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null || mc.player == null) {
			return;
		}
		float td = ctx.tickDelta();
		Vec3d cam = ctx.camera().getPos();
		MatrixStack ms = ctx.matrixStack();
		boolean firstPerson = mc.options.getPerspective().isFirstPerson();

		if (OrvWindowClient.selfOpen) {
			drawFor(mc, ms, cam, mc.player, td, firstPerson, true);
		}
		for (UUID id : OrvWindowClient.OTHERS) {
			PlayerEntity p = mc.world.getPlayerByUuid(id);
			if (p != null && p != mc.player && !p.isInvisible()) {
				drawFor(mc, ms, cam, p, td, false, false);
			}
		}
	}

	private static void drawFor(MinecraftClient mc, MatrixStack ms, Vec3d cam, PlayerEntity player,
								float td, boolean showText, boolean self) {
		double yawDeg = self
				? player.getYaw(td)
				: MathHelper.lerpAngleDegrees(td, player.prevHeadYaw, player.headYaw);
		double yr = Math.toRadians(yawDeg);

		// f = horizontal direction the player faces; right = viewer's right when looking along f
		Vec3d f = new Vec3d(-Math.sin(yr), 0, Math.cos(yr));
		Vec3d right = new Vec3d(-f.z, 0, f.x);
		Vec3d up = new Vec3d(0, 1, 0);

		Vec3d eye = player.getCameraPosVec(td);
		Vec3d center = eye.add(f.multiply(DISTANCE)).add(0, -0.05, 0).subtract(cam);

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
		double hw = HALF_W;
		double hh = HALF_H;
		double t = 0.025;
		quad(b, m, center, right, up, -hw, -hh, hw, hh, FILL_BOTTOM, FILL_TOP);
		quad(b, m, center, right, up, -hw, hh - t, hw, hh, BORDER, BORDER);
		quad(b, m, center, right, up, -hw, -hh, hw, -hh + t, BORDER, BORDER);
		quad(b, m, center, right, up, -hw, -hh, -hw + t, hh, BORDER, BORDER);
		quad(b, m, center, right, up, hw - t, -hh, hw, hh, BORDER, BORDER);
		Tessellator.getInstance().draw();

		if (showText) {
			drawText(mc, m, center, right, up, f);
		}

		RenderSystem.enableCull();
		RenderSystem.depthMask(true);
		RenderSystem.enableDepthTest();
		RenderSystem.disableBlend();
	}

	private static void drawText(MinecraftClient mc, Matrix4f m, Vec3d center, Vec3d right, Vec3d up, Vec3d f) {
		TextRenderer tr = mc.textRenderer;
		float s = SCALE;

		// top-left corner of the window, nudged slightly toward the viewer (-f)
		Vec3d tl = center
				.add(right.multiply(-HALF_W))
				.add(up.multiply(HALF_H))
				.add(f.multiply(-0.002));

		// local text space: +x -> right, +y (down) -> -up
		Matrix4f local = new Matrix4f(
				(float) (right.x * s), (float) (right.y * s), (float) (right.z * s), 0f,
				(float) (-up.x * s), (float) (-up.y * s), (float) (-up.z * s), 0f,
				(float) (-f.x * s), (float) (-f.y * s), (float) (-f.z * s), 0f,
				(float) tl.x, (float) tl.y, (float) tl.z, 1f
		);
		Matrix4f tm = new Matrix4f(m).mul(local);

		List<Text> lines = PlayerData.lines();
		for (int i = 0; i < lines.size(); i++) {
			Text line = lines.get(i);
			float w = tr.getWidth(line);
			float x = (TEXT_W - w) / 2f;
			float y = 12f + i * 15f;
			tr.draw(line, x, y, 0xFFFFFFFF, true, tm, TEXT,
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
