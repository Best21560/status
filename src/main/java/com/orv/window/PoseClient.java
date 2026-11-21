package com.orv.window;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Greatsword fighting animations of the Slayer, in the heavy two-handed style of Elden Ring (slow wind-up, a fast
 * cut, a long recovery). The server sends "entity X plays animation N" (FxNet.ANIM); this class bends the player model
 * every frame (BipedModelMixin, LivingRendererMixin for the whole-body spin) and works out where the hands are, so a big
 * sword (GreatSword) and its light trail can be drawn exactly in them.
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
	public static final int FAST_R = 9;
	public static final int FAST_L = 10;

	static final class Sample {
		Vec3d base;
		Vec3d tip;
		long at;
	}

	private static final class Anim {
		int id;
		long start;
		long born;
		final ArrayDeque<Sample> trail = new ArrayDeque<>();
	}

	private static final Map<Integer, Anim> ACTIVE = new HashMap<>();
	private static final float TAU = 6.2831855f;

	public static void reset() {
		ACTIVE.clear();
	}

	public static boolean active() {
		return !ACTIVE.isEmpty();
	}

	public static void start(int entityId, int id) {
		Anim old = ACTIVE.get(entityId);
		Anim a = new Anim();
		a.id = id;
		a.start = System.nanoTime();
		a.born = a.start;
		if (old != null && (a.start - old.start) / 1_000_000_000f < duration(old.id)) {
			// a chain of moves keeps the same sword and the same trail
			a.born = old.born;
			a.trail.addAll(old.trail);
		}
		ACTIVE.put(entityId, a);
	}

	/** Seconds the animation lasts. */
	static float duration(int id) {
		return switch (id) {
			case SLASH_R, SLASH_L -> 0.7f;
			case STAB -> 0.55f;
			case LEAP -> 3.0f;
			case SLAM -> 0.75f;
			case JUDGE -> 3.8f;
			case DASH -> 0.35f;
			case ROAR -> 5.0f;
			case COUNTER -> 0.6f;
			case FAST_R, FAST_L -> 0.26f;
			default -> 0.4f;
		};
	}

	/**
	 * Key pose: time, right arm pitch/yaw/roll, left arm pitch/yaw/roll, body pitch, body yaw, head pitch,
	 * right leg pitch, left leg pitch, whole-body spin (radians), sword tilt against the arm.
	 * Arm pitch: negative lifts the arm forward and up. Arm yaw: for the right arm positive is outward (to the right),
	 * for the left arm positive is inward.
	 */
	private static float[] r(float t, float rax, float ray, float raz, float lax, float lay, float laz, float bp, float by,
							 float hp, float rl, float ll, float ry, float beta) {
		return new float[]{t, rax, ray, raz, lax, lay, laz, bp, by, hp, rl, ll, ry, beta};
	}

	/** Two-handed guard: the sword held low in front, tip up. */
	private static float[] ready(float t) {
		return r(t, -1.15f, -0.3f, 0.1f, -1.25f, 0.55f, -0.1f, 0.1f, 0f, 0f, -0.15f, 0.15f, 0f, -0.55f);
	}

	private static final float[][] K_SLASH_R = {
			ready(0f),
			r(0.20f, -1.35f, 1.25f, 0.4f, -1.5f, 1.7f, -0.2f, -0.05f, 0.95f, 0f, 0.25f, -0.25f, 0.35f, -0.2f),
			r(0.30f, -1.45f, -1.45f, 0.1f, -1.3f, -0.9f, -0.2f, 0.2f, -1.05f, 0.1f, -0.5f, 0.5f, -0.5f, -0.2f),
			r(0.42f, -1.4f, -1.7f, 0.1f, -1.2f, -1.0f, -0.2f, 0.2f, -1.2f, 0.1f, -0.5f, 0.5f, -0.6f, -0.3f),
			ready(0.70f)};
	private static final float[][] K_SLASH_L = {
			ready(0f),
			r(0.20f, -1.1f, -1.3f, 0f, -1.2f, -0.8f, -0.2f, 0.15f, -0.9f, 0f, -0.2f, 0.2f, -0.3f, -0.2f),
			r(0.30f, -1.9f, 1.35f, 0.5f, -1.8f, 1.8f, -0.2f, -0.05f, 1.0f, 0f, 0.45f, -0.45f, 0.45f, -0.4f),
			r(0.42f, -2.1f, 1.6f, 0.5f, -2.0f, 1.9f, -0.2f, -0.1f, 1.15f, 0f, 0.5f, -0.5f, 0.55f, -0.5f),
			ready(0.70f)};
	private static final float[][] K_FAST_R = {
			ready(0f),
			r(0.07f, -1.35f, 1.25f, 0.4f, -1.5f, 1.7f, -0.2f, -0.05f, 0.95f, 0f, 0.25f, -0.25f, 0.35f, -0.2f),
			r(0.13f, -1.45f, -1.45f, 0.1f, -1.3f, -0.9f, -0.2f, 0.2f, -1.05f, 0.1f, -0.5f, 0.5f, -0.5f, -0.2f),
			r(0.26f, -1.4f, -1.7f, 0.1f, -1.2f, -1.0f, -0.2f, 0.2f, -1.2f, 0.1f, -0.5f, 0.5f, -0.6f, -0.3f)};
	private static final float[][] K_FAST_L = {
			ready(0f),
			r(0.07f, -1.1f, -1.3f, 0f, -1.2f, -0.8f, -0.2f, 0.15f, -0.9f, 0f, -0.2f, 0.2f, -0.3f, -0.2f),
			r(0.13f, -1.9f, 1.35f, 0.5f, -1.8f, 1.8f, -0.2f, -0.05f, 1.0f, 0f, 0.45f, -0.45f, 0.45f, -0.4f),
			r(0.26f, -2.1f, 1.6f, 0.5f, -2.0f, 1.9f, -0.2f, -0.1f, 1.15f, 0f, 0.5f, -0.5f, 0.55f, -0.5f)};
	private static final float[][] K_STAB = {
			ready(0f),
			r(0.22f, -0.9f, 0.5f, 0.2f, -1.0f, 0.9f, -0.1f, 0f, 0.5f, 0f, 0.3f, -0.3f, 0.2f, -0.6f),
			r(0.32f, -1.5f, -0.25f, 0f, -1.45f, 0.3f, -0.1f, 0.4f, -0.2f, 0.1f, -0.55f, 0.55f, -0.15f, -0.1f),
			r(0.42f, -1.5f, -0.25f, 0f, -1.45f, 0.3f, -0.1f, 0.4f, -0.2f, 0.1f, -0.55f, 0.55f, -0.15f, -0.1f),
			ready(0.55f)};
	// jump, spin twice with the sword held out flat, raise it overhead (the smash itself is SLAM)
	private static final float[][] K_LEAP = {
			ready(0f),
			r(0.12f, -0.6f, 0.8f, 0.3f, -0.7f, 1.3f, -0.1f, 0.55f, 0.6f, 0.3f, 0.4f, -0.4f, 0f, -0.3f),
			r(0.24f, -1.55f, 0.2f, 0.1f, -1.5f, 0.4f, -0.1f, 0f, 0f, 0f, -0.3f, 0.3f, 0f, -0.05f),
			r(0.64f, -1.55f, 0.2f, 0.1f, -1.5f, 0.4f, -0.1f, 0f, 0f, 0f, -0.3f, 0.3f, 2 * TAU, -0.05f),
			r(0.74f, -3.0f, 0.2f, 0.3f, -2.9f, -0.1f, -0.2f, -0.45f, 0f, -0.5f, 0.2f, -0.2f, 2 * TAU, -0.1f),
			r(3.00f, -3.0f, 0.2f, 0.3f, -2.9f, -0.1f, -0.2f, -0.45f, 0f, -0.5f, 0.2f, -0.2f, 2 * TAU, -0.1f)};
	private static final float[][] K_SLAM = {
			r(0f, -3.0f, 0.2f, 0.3f, -2.9f, -0.1f, -0.2f, -0.45f, 0f, -0.5f, 0.2f, -0.2f, 0f, -0.1f),
			r(0.07f, -3.0f, 0.2f, 0.3f, -2.9f, -0.1f, -0.2f, -0.45f, 0f, -0.5f, 0.2f, -0.2f, 0f, -0.1f),
			r(0.19f, -0.35f, 0f, 0.1f, -0.5f, 0f, -0.1f, 0.9f, 0f, 0.4f, -0.6f, 0.6f, 0f, -0.3f),
			r(0.45f, -0.35f, 0f, 0.1f, -0.5f, 0f, -0.1f, 0.9f, 0f, 0.4f, -0.6f, 0.6f, 0f, -0.3f),
			ready(0.75f)};
	private static final float[][] K_DASH = {
			ready(0f),
			r(0.06f, 0.5f, 0.3f, 0.2f, 0.2f, 0.5f, -0.1f, 0.7f, 0f, 0.2f, -0.6f, 0.6f, 0f, 0.5f),
			r(0.25f, 0.5f, 0.3f, 0.2f, 0.2f, 0.5f, -0.1f, 0.7f, 0f, 0.2f, -0.6f, 0.6f, 0f, 0.5f),
			ready(0.35f)};
	private static final float[][] K_COUNTER = {
			ready(0f),
			r(0.08f, -0.6f, -1.3f, 0.2f, -0.9f, -0.6f, -0.1f, 0.3f, -0.9f, 0.1f, 0.3f, -0.3f, -0.4f, 0f),
			r(0.20f, -2.2f, 1.3f, 0.4f, -2.1f, 1.6f, -0.2f, -0.2f, 1.0f, -0.1f, -0.5f, 0.5f, 0.5f, -0.5f),
			r(0.34f, -2.2f, 1.3f, 0.4f, -2.1f, 1.6f, -0.2f, -0.2f, 1.0f, -0.1f, -0.5f, 0.5f, 0.5f, -0.5f),
			ready(0.60f)};
	// grab with the left hand, throw, spin three times cutting, raise, smash
	private static final float[][] K_JUDGE = {
			ready(0f),
			r(0.15f, 0.3f, 0.3f, 0.2f, -0.2f, 0.6f, -0.1f, 0.6f, 0f, 0.2f, -0.6f, 0.6f, 0f, 0.3f),
			r(0.32f, -0.8f, 0.8f, 0.2f, -1.6f, 0.1f, -0.1f, 0.1f, 0.3f, 0f, -0.3f, 0.3f, 0f, -0.3f),
			r(0.95f, -1.0f, 0.9f, 0.2f, -2.6f, 0.1f, -0.2f, -0.15f, 0.3f, -0.3f, 0.2f, -0.2f, 0f, -0.3f),
			r(1.05f, -1.5f, 0.2f, 0.1f, -1.4f, 0.4f, -0.1f, 0.15f, 0f, 0f, -0.35f, 0.35f, 0f, -0.1f),
			r(2.55f, -1.5f, 0.2f, 0.1f, -1.4f, 0.4f, -0.1f, 0.15f, 0f, 0f, -0.35f, 0.35f, 3 * TAU, -0.1f),
			r(2.62f, -3.0f, 0.2f, 0.3f, -2.9f, -0.1f, -0.2f, -0.45f, 0f, -0.5f, 0.2f, -0.2f, 3 * TAU, -0.1f),
			r(2.78f, -0.35f, 0f, 0.1f, -0.5f, 0f, -0.1f, 0.9f, 0f, 0.4f, -0.6f, 0.6f, 3 * TAU, -0.3f),
			r(3.40f, -0.35f, 0f, 0.1f, -0.5f, 0f, -0.1f, 0.9f, 0f, 0.4f, -0.6f, 0.6f, 3 * TAU, -0.3f),
			r(3.80f, -1.15f, -0.3f, 0.1f, -1.25f, 0.55f, -0.1f, 0.1f, 0f, 0f, -0.15f, 0.15f, 3 * TAU, -0.55f)};
	private static final float[][] K_ROAR = {
			r(0f, -0.3f, 0f, 0.1f, 0f, 0f, -0.1f, 0f, 0f, 0f, 0f, 0f, 0f, -0.8f),
			r(0.30f, -1.0f, -0.7f, 0.1f, -1.0f, 0.7f, -0.1f, 0.45f, 0f, 0.3f, 0f, 0f, 0f, -0.8f),
			r(1.15f, -1.0f, -0.7f, 0.1f, -1.0f, 0.7f, -0.1f, 0.45f, 0f, 0.3f, 0f, 0f, 0f, -0.8f),
			r(1.35f, -0.2f, 0f, 0.5f, -0.2f, 0f, -0.5f, 0.3f, 0f, 0.1f, 0f, 0f, 0f, -0.8f),
			r(1.60f, -0.4f, 0.2f, 1.55f, -0.4f, -0.2f, -1.55f, -0.5f, 0f, -0.75f, 0f, 0f, 0f, -0.8f),
			r(2.55f, -0.4f, 0.2f, 1.55f, -0.4f, -0.2f, -1.55f, -0.5f, 0f, -0.75f, 0f, 0f, 0f, -0.8f),
			r(2.75f, 0.3f, 0f, 0.2f, 0.3f, 0f, -0.2f, 0.5f, 0f, 0.3f, 0f, 0f, 0f, -0.8f),
			r(3.00f, -0.3f, 0f, 0.1f, 0f, 0f, -0.1f, 0f, 0f, 0f, 0f, 0f, 0f, -0.8f)};

	private static float smooth(float x) {
		x = x < 0f ? 0f : (x > 1f ? 1f : x);
		return x * x * (3f - 2f * x);
	}

	private static void keys(float[][] k, float t, float[] out) {
		if (t <= k[0][0]) {
			System.arraycopy(k[0], 1, out, 0, 13);
			return;
		}
		for (int i = 0; i < k.length - 1; i++) {
			if (t <= k[i + 1][0]) {
				float s = smooth((t - k[i][0]) / (k[i + 1][0] - k[i][0]));
				for (int j = 0; j < 13; j++) {
					out[j] = k[i][j + 1] + (k[i + 1][j + 1] - k[i][j + 1]) * s;
				}
				return;
			}
		}
		System.arraycopy(k[k.length - 1], 1, out, 0, 13);
	}

	/** out: 0-2 right arm, 3-5 left arm, 6 body pitch, 7 body yaw, 8 head pitch, 9-10 legs, 11 spin, 12 sword tilt. */
	private static void pose(int id, float t, float[] o) {
		switch (id) {
			case SLASH_R -> keys(K_SLASH_R, t, o);
			case SLASH_L -> keys(K_SLASH_L, t, o);
			case FAST_R -> keys(K_FAST_R, t, o);
			case FAST_L -> keys(K_FAST_L, t, o);
			case STAB -> keys(K_STAB, t, o);
			case LEAP -> keys(K_LEAP, t, o);
			case SLAM -> keys(K_SLAM, t, o);
			case DASH -> keys(K_DASH, t, o);
			case COUNTER -> keys(K_COUNTER, t, o);
			case JUDGE -> {
				keys(K_JUDGE, t, o);
				if (t > 1.05f && t < 2.55f) {
					float env = Math.min(1f, Math.min((t - 1.05f) / 0.15f, (2.55f - t) / 0.15f));
					o[6] += 0.12f * env * (float) Math.sin(t * 18f);
				}
			}
			case ROAR -> {
				float ts = t * 0.6f; // the animation is stretched to 5 seconds
				keys(K_ROAR, ts, o);
				if (ts > 0.3f && ts < 1.15f) {
					float env = Math.min(1f, Math.min((ts - 0.3f) / 0.1f, (1.15f - ts) / 0.1f));
					float s = (float) Math.sin(t * 22f);
					o[0] += 0.4f * env * s;
					o[3] -= 0.4f * env * s;
					o[6] += 0.08f * env * Math.abs(s);
				}
				if (ts > 1.6f && ts < 2.55f) {
					o[6] += 0.05f * (float) Math.sin(t * 45f);
					o[8] += 0.07f * (float) Math.sin(t * 50f);
				}
			}
			default -> keys(K_DASH, t, o);
		}
	}

	private static Anim animOf(Entity e) {
		if (ACTIVE.isEmpty()) {
			return null;
		}
		return ACTIVE.get(e.getId());
	}

	/** Extra whole-body rotation in degrees (the jump spin); used by LivingRendererMixin. */
	public static float rootYaw(LivingEntity e) {
		Anim a = animOf(e);
		if (a == null || !(e instanceof PlayerEntity)) {
			return 0f;
		}
		float t = (System.nanoTime() - a.start) / 1_000_000_000f;
		if (t >= duration(a.id)) {
			return 0f;
		}
		float[] o = new float[13];
		pose(a.id, t, o);
		return (float) Math.toDegrees(o[11]);
	}

	/** Called from BipedModelMixin after the vanilla pose was computed. */
	public static void apply(BipedEntityModel<?> m, LivingEntity e) {
		if (!(e instanceof PlayerEntity)) {
			return;
		}
		Anim a = animOf(e);
		if (a == null) {
			return;
		}
		float t = (System.nanoTime() - a.start) / 1_000_000_000f;
		float dur = duration(a.id);
		if (t >= dur) {
			return;
		}
		float[] o = new float[13];
		pose(a.id, t, o);
		float w = Math.max(0f, Math.min(1f, Math.min((System.nanoTime() - a.born) / 1_000_000_000f / 0.06f, (dur - t) / 0.12f)));
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
		m.rightLeg.pitch += (o[9] - m.rightLeg.pitch) * w;
		m.leftLeg.pitch += (o[10] - m.leftLeg.pitch) * w;
		m.hat.copyTransform(m.head);
	}

	// ------------------------------------------------------------------ the sword in the hands

	/** {hand, blade direction, blade width direction} in the world for the pose o. */
	private static Vec3d[] rig(LivingEntity e, float[] o, float td) {
		float yawDeg = MathHelper.lerpAngleDegrees(td, e.prevBodyYaw, e.bodyYaw);
		double th = Math.toRadians(180.0 - yawDeg) + o[11];
		Vector3f arm = new Quaternionf().rotationZYX(o[2], o[1], o[0]).transform(new Vector3f(0, 1, 0));
		Quaternionf bq = new Quaternionf().rotationZYX(o[2], o[1], o[0] + o[12]);
		Vector3f blade = bq.transform(new Vector3f(0, 1, 0));
		Vector3f wide = bq.transform(new Vector3f(1, 0, 0));
		Vec3d base = e.getLerpedPos(td);
		Vec3d shoulder = new Vec3d(0.3125, 1.375, 0);
		Vec3d hand = shoulder.add(modelDir(arm).multiply(0.68));
		return new Vec3d[]{
				base.add(turn(hand, th)),
				turn(modelDir(blade), th).normalize(),
				turn(modelDir(wide), th).normalize()};
	}

	/** Model space (y down, front is -z) to entity space (before the body yaw). */
	private static Vec3d modelDir(Vector3f v) {
		return new Vec3d(-v.x, -v.y, v.z);
	}

	private static Vec3d turn(Vec3d v, double th) {
		double c = Math.cos(th);
		double s = Math.sin(th);
		return new Vec3d(v.x * c + v.z * s, v.y, -v.x * s + v.z * c);
	}

	/**
	 * Draws every sword that is being swung. pass 1 (dark != null): the steel and a dark smear behind it;
	 * pass 2 (glow != null): the light trail, the glowing edge and the runes. Samples are only taken in pass 2.
	 */
	public static void drawBlades(Fx.Pen dark, Fx.Pen glow, MinecraftClient mc, float td) {
		if (ACTIVE.isEmpty() || mc.world == null) {
			return;
		}
		long now = System.nanoTime();
		Iterator<Map.Entry<Integer, Anim>> it = ACTIVE.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Anim> en = it.next();
			Anim a = en.getValue();
			float t = (now - a.start) / 1_000_000_000f;
			float dur = duration(a.id);
			if (t >= dur) {
				if (glow != null && now - a.start > (long) ((dur + 0.4f) * 1_000_000_000L)) {
					it.remove();
				}
				if (a.trail.isEmpty() || t >= dur + 0.4f) {
					continue;
				}
			}
			if (a.id == ROAR) {
				continue;
			}
			Entity ent = mc.world.getEntityById(en.getKey());
			if (!(ent instanceof LivingEntity le)) {
				continue;
			}
			float[] o = new float[13];
			pose(a.id, Math.min(t, dur), o);
			Vec3d[] rg = rig(le, o, td);
			boolean holding = t < dur;
			float alpha = holding
					? Math.min(1f, Math.min((now - a.born) / 1_000_000_000f / 0.08f, (dur - t) / 0.15f))
					: 0f;
			alpha = Math.max(0f, alpha);
			Vec3d tip = rg[0].add(rg[1].multiply(GreatSword.TIP));
			Vec3d bas = rg[0].add(rg[1].multiply(GreatSword.TRAIL_BASE));
			float power = 0.5f;
			if (glow != null && holding) {
				Sample last = a.trail.peekLast();
				if (last != null) {
					float dt = Math.max(0.001f, (now - last.at) / 1_000_000_000f);
					power = Math.max(0.35f, Math.min(1f, (float) (tip.distanceTo(last.tip) / dt) / 14f));
				}
				if (last == null || last.tip.squaredDistanceTo(tip) > 0.0025) {
					Sample s = new Sample();
					s.base = bas;
					s.tip = tip;
					s.at = now;
					a.trail.addLast(s);
				}
			}
			if (glow != null) {
				while (!a.trail.isEmpty() && (now - a.trail.peekFirst().at) / 1_000_000_000f > 0.34f) {
					a.trail.removeFirst();
				}
				while (a.trail.size() > 48) {
					a.trail.removeFirst();
				}
			}
			float ta = holding ? 1f : 1f - (t - dur) / 0.4f;
			if (dark != null) {
				GreatSword.trail(dark, a.trail, now, true, ta);
				if (alpha > 0f) {
					GreatSword.body(dark, rg[0], rg[1], rg[2], alpha);
				}
			}
			if (glow != null) {
				GreatSword.trail(glow, a.trail, now, false, ta);
				if (alpha > 0f) {
					GreatSword.glow(glow, rg[0], rg[1], rg[2], alpha, power, now / 1_000_000_000.0);
				}
			}
		}
	}
}
