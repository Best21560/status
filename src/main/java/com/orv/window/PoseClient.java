package com.orv.window;

import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * Body animations of the Slayer skills, played on the (vanilla) player model: the server sends "entity X plays
 * animation N" (FxNet.ANIM) and BipedModelMixin asks this class to bend the arms, body and head every frame.
 * Works with or without Epic Fight (when Epic Fight draws the player with its own model, its animation wins).
 */
public final class PoseClient {
	private PoseClient() {
	}

	public static final int SLASH_R = 0;
	public static final int SLASH_L = 1;
	public static final int STAB = 2;
	public static final int LEAP = 3;
	public static final int SLAM = 4;
	public static final int JUDGE = 5;
	public static final int DASH = 6;
	public static final int ROAR = 7;
	public static final int COUNTER = 8;

	private static final class Anim {
		int id;
		long start;
	}

	private static final Map<Integer, Anim> ACTIVE = new HashMap<>();

	public static void reset() {
		ACTIVE.clear();
	}

	public static void start(int entityId, int id) {
		Anim a = new Anim();
		a.id = id;
		a.start = System.nanoTime();
		ACTIVE.put(entityId, a);
	}

	/** Seconds the animation lasts. */
	static float duration(int id) {
		return switch (id) {
			case SLASH_R, SLASH_L -> 0.5f;
			case STAB -> 0.42f;
			case LEAP -> 3.0f;
			case SLAM -> 0.6f;
			case JUDGE -> 3.8f;
			case DASH -> 0.35f;
			case ROAR -> 3.0f;
			case COUNTER -> 0.5f;
			default -> 0.4f;
		};
	}

	// columns: time, rightArm pitch/yaw/roll, leftArm pitch/yaw/roll, body pitch, body yaw, head pitch
	private static final float[][] K_SLASH_R = {
			{0.00f, -0.3f, 0f, 0.1f, 0f, 0f, -0.1f, 0f, 0f, 0f},
			{0.10f, -2.6f, 0.6f, 0.9f, -0.4f, 0.3f, -0.3f, -0.12f, 0.5f, -0.1f},
			{0.22f, -1.5f, -1.1f, 0.1f, -0.9f, 0.6f, -0.4f, 0.15f, -0.7f, 0.1f},
			{0.34f, -0.7f, -0.6f, 0.2f, -0.3f, 0.2f, -0.2f, 0.2f, -0.4f, 0.1f},
			{0.50f, -0.3f, 0f, 0.1f, 0f, 0f, -0.1f, 0f, 0f, 0f}};
	private static final float[][] K_SLASH_L = {
			{0.00f, -0.3f, 0f, 0.1f, 0f, 0f, -0.1f, 0f, 0f, 0f},
			{0.10f, -1.3f, -1.0f, 0.2f, -0.5f, 0.5f, -0.3f, 0.1f, -0.6f, 0f},
			{0.22f, -2.3f, 0.8f, 0.6f, -0.8f, 0.2f, -0.2f, -0.1f, 0.6f, -0.1f},
			{0.34f, -1.1f, 0.4f, 0.3f, -0.4f, 0.1f, -0.2f, 0.1f, 0.3f, 0f},
			{0.50f, -0.3f, 0f, 0.1f, 0f, 0f, -0.1f, 0f, 0f, 0f}};
	private static final float[][] K_STAB = {
			{0.00f, -0.3f, 0f, 0.1f, 0f, 0f, -0.1f, 0f, 0f, 0f},
			{0.08f, -1.2f, 0.6f, 0.1f, -0.6f, -0.4f, -0.2f, 0.1f, 0.5f, 0f},
			{0.16f, -1.62f, -0.1f, 0f, 0.3f, 0.3f, -0.3f, 0.35f, -0.2f, 0.1f},
			{0.30f, -1.6f, -0.1f, 0f, 0.3f, 0.3f, -0.3f, 0.35f, -0.2f, 0.1f},
			{0.42f, -0.3f, 0f, 0.1f, 0f, 0f, -0.1f, 0f, 0f, 0f}};
	private static final float[][] K_LEAP = {
			{0.00f, -0.3f, 0f, 0.1f, 0f, 0f, -0.1f, 0f, 0f, 0f},
			{0.10f, 0.5f, 0f, 0.4f, 0.5f, 0f, -0.4f, 0.45f, 0f, 0.2f},
			{0.22f, -3.0f, 0.3f, 0.3f, -2.7f, -0.2f, -0.3f, -0.35f, 0f, -0.4f},
			{0.50f, -3.1f, 0.3f, 0.2f, -2.9f, -0.2f, -0.2f, -0.45f, 0f, -0.5f},
			{3.00f, -3.1f, 0.3f, 0.2f, -2.9f, -0.2f, -0.2f, -0.45f, 0f, -0.5f}};
	private static final float[][] K_SLAM = {
			{0.00f, -3.1f, 0.3f, 0.2f, -2.9f, -0.2f, -0.2f, -0.45f, 0f, -0.5f},
			{0.08f, -3.1f, 0.3f, 0.2f, -2.9f, -0.2f, -0.2f, -0.45f, 0f, -0.5f},
			{0.20f, -0.5f, 0f, 0.1f, -0.6f, 0f, -0.1f, 0.7f, 0f, 0.3f},
			{0.40f, -0.5f, 0f, 0.1f, -0.6f, 0f, -0.1f, 0.7f, 0f, 0.3f},
			{0.60f, -0.3f, 0f, 0.1f, 0f, 0f, -0.1f, 0f, 0f, 0f}};
	private static final float[][] K_DASH = {
			{0.00f, -0.3f, 0f, 0.1f, 0f, 0f, -0.1f, 0f, 0f, 0f},
			{0.06f, 0.9f, 0f, 0.3f, 0.9f, 0f, -0.3f, 0.6f, 0f, 0.2f},
			{0.25f, 0.9f, 0f, 0.3f, 0.9f, 0f, -0.3f, 0.6f, 0f, 0.2f},
			{0.35f, -0.3f, 0f, 0.1f, 0f, 0f, -0.1f, 0f, 0f, 0f}};
	private static final float[][] K_COUNTER = {
			{0.00f, -0.3f, 0f, 0.1f, 0f, 0f, -0.1f, 0f, 0f, 0f},
			{0.06f, -0.9f, -1.3f, 0.2f, -0.4f, 0.5f, -0.2f, 0.1f, -0.8f, 0f},
			{0.16f, -1.55f, 1.2f, 0.1f, -0.2f, 0.3f, -0.2f, 0.2f, 0.9f, 0f},
			{0.30f, -1.55f, 1.2f, 0.1f, -0.2f, 0.3f, -0.2f, 0.2f, 0.9f, 0f},
			{0.50f, -0.3f, 0f, 0.1f, 0f, 0f, -0.1f, 0f, 0f, 0f}};
	private static final float[][] K_JUDGE = {
			{0.00f, -0.3f, 0f, 0.1f, 0f, 0f, -0.1f, 0f, 0f, 0f},
			{0.15f, 0.9f, 0f, 0.3f, 0.9f, 0f, -0.3f, 0.6f, 0f, 0.2f},
			{0.32f, -1.4f, 0.3f, 0.1f, -1.4f, -0.3f, -0.1f, 0.1f, 0f, -0.2f},
			{0.95f, -2.2f, 0.2f, 0.2f, -2.2f, -0.2f, -0.2f, -0.2f, 0f, -0.4f},
			{1.05f, -1.5f, 0f, 0.2f, -0.8f, 0f, -0.2f, 0.2f, 0f, 0f},
			{2.55f, -1.5f, 0f, 0.2f, -0.8f, 0f, -0.2f, 0.2f, 0f, 0f},
			{2.62f, -3.0f, 0.3f, 0.3f, -2.8f, -0.2f, -0.3f, -0.4f, 0f, -0.5f},
			{2.78f, -0.4f, 0f, 0.1f, -0.5f, 0f, -0.1f, 0.8f, 0f, 0.3f},
			{3.40f, -0.4f, 0f, 0.1f, -0.5f, 0f, -0.1f, 0.8f, 0f, 0.3f},
			{3.80f, -0.3f, 0f, 0.1f, 0f, 0f, -0.1f, 0f, 0f, 0f}};
	private static final float[][] K_ROAR = {
			{0.00f, -0.3f, 0f, 0.1f, 0f, 0f, -0.1f, 0f, 0f, 0f},
			{0.30f, -1.0f, -0.7f, 0.1f, -1.0f, 0.7f, -0.1f, 0.45f, 0f, 0.3f},
			{1.15f, -1.0f, -0.7f, 0.1f, -1.0f, 0.7f, -0.1f, 0.45f, 0f, 0.3f},
			{1.35f, -0.2f, 0f, 0.5f, -0.2f, 0f, -0.5f, 0.3f, 0f, 0.1f},
			{1.60f, -0.4f, 0.2f, 1.55f, -0.4f, -0.2f, -1.55f, -0.5f, 0f, -0.75f},
			{2.55f, -0.4f, 0.2f, 1.55f, -0.4f, -0.2f, -1.55f, -0.5f, 0f, -0.75f},
			{2.75f, 0.3f, 0f, 0.2f, 0.3f, 0f, -0.2f, 0.5f, 0f, 0.3f},
			{3.00f, -0.3f, 0f, 0.1f, 0f, 0f, -0.1f, 0f, 0f, 0f}};

	private static float smooth(float x) {
		x = x < 0f ? 0f : (x > 1f ? 1f : x);
		return x * x * (3f - 2f * x);
	}

	private static void keys(float[][] k, float t, float[] out) {
		if (t <= k[0][0]) {
			System.arraycopy(k[0], 1, out, 0, 9);
			return;
		}
		for (int i = 0; i < k.length - 1; i++) {
			if (t <= k[i + 1][0]) {
				float s = smooth((t - k[i][0]) / (k[i + 1][0] - k[i][0]));
				for (int j = 0; j < 9; j++) {
					out[j] = k[i][j + 1] + (k[i + 1][j + 1] - k[i][j + 1]) * s;
				}
				return;
			}
		}
		System.arraycopy(k[k.length - 1], 1, out, 0, 9);
	}

	private static void pose(int id, float t, float[] o) {
		switch (id) {
			case SLASH_R -> keys(K_SLASH_R, t, o);
			case SLASH_L -> keys(K_SLASH_L, t, o);
			case STAB -> keys(K_STAB, t, o);
			case LEAP -> keys(K_LEAP, t, o);
			case SLAM -> keys(K_SLAM, t, o);
			case DASH -> keys(K_DASH, t, o);
			case COUNTER -> keys(K_COUNTER, t, o);
			case JUDGE -> {
				keys(K_JUDGE, t, o);
				if (t > 1.05f && t < 2.55f) {
					// spinning cuts around the target
					float env = Math.min(1f, Math.min((t - 1.05f) / 0.15f, (2.55f - t) / 0.15f));
					o[0] += 0.45f * env * (float) Math.sin(t * 20f + 1f);
					o[1] += 1.3f * env * (float) Math.sin(t * 20f);
					o[2] += 0.3f * env * (float) Math.sin(t * 20f + 2f);
					o[3] += 0.4f * env * (float) Math.sin(t * 20f + 3f);
					o[7] += 0.9f * env * (float) Math.sin(t * 10f);
				}
			}
			case ROAR -> {
				keys(K_ROAR, t, o);
				if (t > 0.3f && t < 1.15f) {
					// beating the chest with both fists
					float env = Math.min(1f, Math.min((t - 0.3f) / 0.1f, (1.15f - t) / 0.1f));
					float s = (float) Math.sin(t * 22f);
					o[0] += 0.4f * env * s;
					o[3] -= 0.4f * env * s;
					o[6] += 0.08f * env * Math.abs(s);
				}
				if (t > 1.6f && t < 2.55f) {
					// the body shakes while it roars
					o[6] += 0.05f * (float) Math.sin(t * 45f);
					o[8] += 0.07f * (float) Math.sin(t * 50f);
				}
			}
			default -> keys(K_DASH, t, o);
		}
	}

	/** Called from BipedModelMixin after the vanilla pose was computed. */
	public static void apply(BipedEntityModel<?> m, LivingEntity e) {
		if (ACTIVE.isEmpty() || !(e instanceof PlayerEntity)) {
			return;
		}
		Anim a = ACTIVE.get(e.getId());
		if (a == null) {
			return;
		}
		float t = (System.nanoTime() - a.start) / 1_000_000_000f;
		float dur = duration(a.id);
		if (t >= dur) {
			ACTIVE.remove(e.getId());
			return;
		}
		float[] o = new float[9];
		pose(a.id, t, o);
		float w = Math.min(1f, Math.min(t / 0.06f, (dur - t) / 0.12f));
		w = Math.max(0f, w);
		m.rightArm.pitch += (o[0] - m.rightArm.pitch) * w;
		m.rightArm.yaw += (o[1] - m.rightArm.yaw) * w;
		m.rightArm.roll += (o[2] - m.rightArm.roll) * w;
		m.leftArm.pitch += (o[3] - m.leftArm.pitch) * w;
		m.leftArm.yaw += (o[4] - m.leftArm.yaw) * w;
		m.leftArm.roll += (o[5] - m.leftArm.roll) * w;
		m.body.pitch += o[6] * w;
		m.body.yaw += o[7] * w;
		m.head.pitch += o[8] * w;
		m.head.yaw += o[7] * 0.4f * w;
		m.hat.copyTransform(m.head);
	}
}
