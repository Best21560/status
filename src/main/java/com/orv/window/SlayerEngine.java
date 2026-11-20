package com.orv.window;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.List;

/**
 * The Slayer class: Rage gauge, Berserk mode and the five skills
 * (Slash, Relentless Pursuit, Counter Hunt, Fatal Strike, Final Judgment).
 * The visuals are drawn by the client (see Fx / FxNet).
 */
public final class SlayerEngine {
	private SlayerEngine() {
	}

	public static final String ID = "slayer";
	public static final float MAX_RAGE = 100f;

	/** How long Berserk lasts, in server ticks (20 ticks = 1 second). */
	public static final int BERSERK_TICKS = 20 * 20;

	// cooldowns in ticks
	public static final int[] CD = {100, 300, 80, 160, 1800};
	public static final int SLASH = 0;
	public static final int PURSUIT = 1;
	public static final int COUNTER = 2;
	public static final int FATAL = 3;
	public static final int JUDGMENT = 4;

	private static final float PURSUIT_RAGE = 20f;
	private static final float JUDGMENT_MIN_RAGE = 70f;
	private static final int CUTSCENE_MAX = 100;

	public static boolean is(PlayerRes r) {
		return ID.equals(r.classId);
	}

	public static boolean berserk(PlayerRes r, long now) {
		return r.berserkUntil > now;
	}

	public static boolean locked(PlayerRes r) {
		return r.jTarget != null;
	}

	// ------------------------------------------------------------------ rage

	public static void addRage(ServerPlayerEntity p, PlayerRes r, float amount, long now) {
		if (!is(r) || amount <= 0f || berserk(r, now) || locked(r) || now < r.rageLockUntil) {
			return;
		}
		r.rage = Math.min(MAX_RAGE, r.rage + amount);
		r.lastRageGain = now;
		if (r.rage >= MAX_RAGE) {
			startBerserk(p, r, now);
		}
	}

	private static void startBerserk(ServerPlayerEntity p, PlayerRes r, long now) {
		r.berserkUntil = now + BERSERK_TICKS;
		r.rage = MAX_RAGE;
		p.sendMessage(Text.translatable("orvwindow.msg.berserk_on"), true);
		FxNet.send(p.getServerWorld(), FxNet.BERSERK_START, p.getPos(), Vec3d.ZERO, 0f, 0);
	}

	private static void endBerserk(ServerPlayerEntity p, PlayerRes r, long now) {
		r.berserkUntil = 0;
		r.rage = 0f;
		r.rageLockUntil = now + 100;
		p.sendMessage(Text.translatable("orvwindow.msg.berserk_off"), true);
		FxNet.send(p.getServerWorld(), FxNet.BERSERK_END, p.getPos(), Vec3d.ZERO, 0f, 0);
	}

	/** Rage when the Slayer is hit: more for big hits, double for bosses. */
	public static void onDamaged(ServerPlayerEntity p, PlayerRes r, LivingEntity attacker, float amount, long now) {
		if (!is(r)) {
			return;
		}
		float gain = Math.min(12f, 3f + amount * 0.8f);
		if (attacker != null && attacker.getMaxHealth() >= 100f) {
			gain *= 2f;
		}
		addRage(p, r, gain, now);
	}

	// ------------------------------------------------------------------ hooks from PassiveEngine

	/** A Slayer hit something. */
	public static void onHit(ServerPlayerEntity p, PlayerRes r, long now) {
		addRage(p, r, 5f, now);
	}

	public static void onKill(ServerPlayerEntity p, PlayerRes r, LivingEntity victim, long now) {
		addRage(p, r, victim.getMaxHealth() >= 100f ? 25f : 10f, now);
	}

	public static void onArmorBreak(ServerPlayerEntity p, PlayerRes r, long now) {
		addRage(p, r, 15f, now);
	}

	/** Shield raised just before the hit. */
	public static void onParry(ServerPlayerEntity p, PlayerRes r, long now) {
		addRage(p, r, 12f, now);
		FxNet.send(p.getServerWorld(), FxNet.PARRY, p.getEyePos().add(p.getRotationVec(1f).multiply(0.9)),
				p.getRotationVec(1f), 1f, 0);
	}

	/** An attack against the Slayer was dodged (dash invulnerability, Evasive Instinct, Deflection). */
	public static void onDodge(ServerPlayerEntity p, PlayerRes r, Entity attacker, long now) {
		if (!is(r) || locked(r) || berserk(r, now)) {
			return;
		}
		if (attacker instanceof LivingEntity le && le.isAlive() && now >= r.cdEnd[COUNTER] && le.distanceTo(p) <= 7f) {
			r.cdEnd[COUNTER] = now + CD[COUNTER];
			r.cTarget = le.getUuid();
			r.cAt = now + 3;
			addRage(p, r, 10f, now);
		}
	}

	// ------------------------------------------------------------------ key presses

	/** 10 Slash, 11 Relentless Pursuit, 12 Fatal Strike, 13 Final Judgment. */
	public static void action(ServerPlayerEntity p, PlayerRes r, int id, long now) {
		if (!is(r) || p.isSpectator() || locked(r) || berserk(r, now) || r.pTarget != null) {
			return;
		}
		switch (id) {
			case 10 -> slash(p, r, now);
			case 11 -> pursuit(p, r, now);
			case 12 -> fatal(p, r, now);
			case 13 -> judgment(p, r, now);
			default -> {
			}
		}
	}

	private static float base(ServerPlayerEntity p) {
		return Math.max(2f, (float) p.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE));
	}

	private static boolean ready(ServerPlayerEntity p, PlayerRes r, int skill, long now) {
		if (now < r.cdEnd[skill]) {
			p.sendMessage(Text.translatable("orvwindow.msg.cooldown"), true);
			return false;
		}
		return true;
	}

	private static boolean payStamina(ServerPlayerEntity p, PlayerRes r, float cost) {
		if (r.exhausted || r.stamina < cost) {
			p.sendMessage(Text.translatable("orvwindow.msg.no_stamina"), true);
			return false;
		}
		r.stamina -= cost;
		return true;
	}

	static boolean strike(ServerPlayerEntity p, LivingEntity v, float dmg) {
		v.timeUntilRegen = 0;
		return v.damage(p.getDamageSources().playerAttack(p), dmg);
	}

	private static Vec3d chest(Entity e) {
		return e.getPos().add(0, e.getHeight() * 0.65, 0);
	}

	private static List<LivingEntity> candidates(ServerPlayerEntity p, double range) {
		ServerWorld w = p.getServerWorld();
		return w.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(range),
				e -> e.isAlive() && e != p && !e.isSpectator() && e.squaredDistanceTo(p) <= range * range
						&& !(e instanceof TameableEntity t && t.isOwner(p)));
	}

	/** Nearest creature inside a cone in front of the player (minCos = cosine of the half angle). */
	private static LivingEntity findTarget(ServerPlayerEntity p, double range, double minCos) {
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVec(1f);
		LivingEntity best = null;
		double bestD = Double.MAX_VALUE;
		for (LivingEntity e : candidates(p, range)) {
			Vec3d to = chest(e).subtract(eye);
			double d = to.length();
			if (d < 1e-3) {
				continue;
			}
			if (to.multiply(1.0 / d).dotProduct(look) >= minCos && d < bestD) {
				bestD = d;
				best = e;
			}
		}
		return best;
	}

	private static LivingEntity nearest(ServerPlayerEntity p, double range) {
		LivingEntity best = null;
		double bestD = Double.MAX_VALUE;
		for (LivingEntity e : candidates(p, range)) {
			double d = e.squaredDistanceTo(p);
			if (d < bestD) {
				bestD = d;
				best = e;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------ 1. Slash

	private static void slash(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!ready(p, r, SLASH, now) || !payStamina(p, r, 15f)) {
			return;
		}
		r.cdEnd[SLASH] = now + CD[SLASH];
		ServerWorld w = p.getServerWorld();
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVec(1f);
		float dmg = base(p) * 1.6f;
		p.swingHand(Hand.MAIN_HAND, true);
		FxNet.send(w, FxNet.SLASH, eye.add(0, -0.3, 0), look, r.slashFlip ? -32f : 32f, 1);
		r.slashFlip = !r.slashFlip;
		for (LivingEntity e : candidates(p, 4.0)) {
			Vec3d to = chest(e).subtract(eye);
			double d = to.length();
			if (d > 1e-3 && to.multiply(1.0 / d).dotProduct(look) >= 0.3) {
				float d2 = e.getHealth() < 0.4f * e.getMaxHealth() ? dmg * 1.5f : dmg;
				strike(p, e, d2);
				e.takeKnockback(0.5, -look.x, -look.z);
				FxNet.send(w, FxNet.IMPACT, chest(e), look, 1f, 0);
			}
		}
	}

	// ------------------------------------------------------------------ 2. Relentless Pursuit

	private static void pursuit(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!ready(p, r, PURSUIT, now)) {
			return;
		}
		LivingEntity t = findTarget(p, 5.0, 0.2);
		if (t == null) {
			t = nearest(p, 5.0);
		}
		if (t == null) {
			p.sendMessage(Text.translatable("orvwindow.msg.no_target"), true);
			return;
		}
		if (r.rage < PURSUIT_RAGE) {
			p.sendMessage(Text.translatable("orvwindow.msg.no_rage"), true);
			return;
		}
		r.rage -= PURSUIT_RAGE;
		r.cdEnd[PURSUIT] = now + CD[PURSUIT];
		r.pTarget = t.getUuid();
		r.pHits = 5;
		r.pNext = now + 3;
		r.iframeUntil = Math.max(r.iframeUntil, now + 30);
		dashTo(p, t);
	}

	private static void tickPursuit(ServerPlayerEntity p, PlayerRes r, long now) {
		if (r.pTarget == null || now < r.pNext) {
			return;
		}
		ServerWorld w = p.getServerWorld();
		Entity e = w.getEntity(r.pTarget);
		if (!(e instanceof LivingEntity t) || !t.isAlive() || r.pHits <= 0) {
			r.pTarget = null;
			return;
		}
		if (t.distanceTo(p) > 2.6f) {
			dashTo(p, t);
		}
		faceTo(p, t);
		int n = 5 - r.pHits; // 0..4
		boolean last = r.pHits == 1;
		Vec3d dir = chest(t).subtract(p.getEyePos()).normalize();
		p.swingHand(Hand.MAIN_HAND, true);
		FxNet.send(w, FxNet.SLASH, p.getEyePos().add(0, -0.2, 0), dir, (n % 2 == 0 ? 40f : -40f) + n * 7f, last ? 2 : 0);
		strike(p, t, base(p) * (last ? 1.1f : 0.75f));
		if (last) {
			t.takeKnockback(1.0, -dir.x, -dir.z);
		}
		FxNet.send(w, FxNet.IMPACT, chest(t), dir, last ? 1.6f : 1f, 0);
		r.pHits--;
		r.pNext = now + 4;
		if (r.pHits <= 0) {
			r.pTarget = null;
		}
	}

	/** Dash next to the target (instant move with a trail effect). */
	private static void dashTo(ServerPlayerEntity p, LivingEntity t) {
		Vec3d from = p.getPos();
		Vec3d to = t.getPos().subtract(from);
		Vec3d flat = new Vec3d(to.x, 0, to.z);
		if (flat.lengthSquared() < 1e-4) {
			flat = p.getRotationVec(1f);
			flat = new Vec3d(flat.x, 0, flat.z);
		}
		flat = flat.normalize();
		Vec3d dest = t.getPos().subtract(flat.multiply(1.4));
		ServerWorld w = p.getServerWorld();
		if (!w.isSpaceEmpty(p, p.getBoundingBox().offset(dest.subtract(from)))) {
			dest = t.getPos().add(flat.multiply(1.4));
			if (!w.isSpaceEmpty(p, p.getBoundingBox().offset(dest.subtract(from)))) {
				return;
			}
		}
		FxNet.send(w, FxNet.DASH, from.add(0, 1.0, 0), dest.subtract(from), 0f, 0);
		float[] yp = angles(dest.add(0, p.getStandingEyeHeight(), 0), chest(t));
		p.networkHandler.requestTeleport(dest.x, dest.y, dest.z, yp[0], yp[1]);
		p.setVelocity(Vec3d.ZERO);
		p.fallDistance = 0f;
	}

	private static float[] angles(Vec3d from, Vec3d to) {
		double dx = to.x - from.x;
		double dy = to.y - from.y;
		double dz = to.z - from.z;
		double h = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (MathHelper.atan2(dz, dx) * 57.29577951308232) - 90f;
		float pitch = (float) -(MathHelper.atan2(dy, h) * 57.29577951308232);
		return new float[]{yaw, pitch};
	}

	private static void faceTo(ServerPlayerEntity p, LivingEntity t) {
		float[] yp = angles(p.getEyePos(), chest(t));
		p.networkHandler.requestTeleport(p.getX(), p.getY(), p.getZ(), yp[0], yp[1]);
	}

	// ------------------------------------------------------------------ 3. Counter Hunt (triggered by a dodge)

	private static void tickCounter(ServerPlayerEntity p, PlayerRes r, long now) {
		if (r.cTarget == null || now < r.cAt) {
			return;
		}
		ServerWorld w = p.getServerWorld();
		Entity e = w.getEntity(r.cTarget);
		r.cTarget = null;
		if (!(e instanceof LivingEntity t) || !t.isAlive()) {
			return;
		}
		if (t.distanceTo(p) > 2.6f) {
			dashTo(p, t);
		}
		faceTo(p, t);
		Vec3d dir = chest(t).subtract(p.getEyePos()).normalize();
		r.iframeUntil = Math.max(r.iframeUntil, now + 8);
		p.swingHand(Hand.MAIN_HAND, true);
		FxNet.send(w, FxNet.SLASH, p.getEyePos().add(0, -0.2, 0), dir, -55f, 3);
		strike(p, t, base(p) * 2.2f);
		t.takeKnockback(0.8, -dir.x, -dir.z);
		t.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 50, 2), p);
		FxNet.send(w, FxNet.IMPACT, chest(t), dir, 1.4f, 3);
	}

	// ------------------------------------------------------------------ 4. Fatal Strike

	private static void fatal(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!ready(p, r, FATAL, now)) {
			return;
		}
		LivingEntity t = findTarget(p, 4.5, 0.75);
		if (t == null) {
			p.sendMessage(Text.translatable("orvwindow.msg.no_target"), true);
			return;
		}
		if (!payStamina(p, r, 20f)) {
			return;
		}
		r.cdEnd[FATAL] = now + CD[FATAL];
		ServerWorld w = p.getServerWorld();
		Vec3d dir = chest(t).subtract(p.getEyePos()).normalize();
		p.swingHand(Hand.MAIN_HAND, true);
		FxNet.send(w, FxNet.SLASH, p.getEyePos().add(0, -0.1, 0), dir, 8f, 2);
		strike(p, t, base(p) * 3.0f);
		t.takeKnockback(1.1, -dir.x, -dir.z);
		FxNet.send(w, FxNet.IMPACT, chest(t), dir, 2f, 2);
	}

	// ------------------------------------------------------------------ 5. Final Judgment (cutscene)

	private static void judgment(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!ready(p, r, JUDGMENT, now)) {
			return;
		}
		if (r.rage < JUDGMENT_MIN_RAGE) {
			p.sendMessage(Text.translatable("orvwindow.msg.no_rage"), true);
			return;
		}
		LivingEntity t = findTarget(p, 8.0, 0.3);
		if (t == null) {
			t = nearest(p, 6.0);
		}
		if (t == null) {
			p.sendMessage(Text.translatable("orvwindow.msg.no_target"), true);
			return;
		}
		float rageMult = 1f + (r.rage - JUDGMENT_MIN_RAGE) / 100f;
		r.rage = 0f;
		r.cdEnd[JUDGMENT] = now + CD[JUDGMENT];
		r.jTarget = t.getUuid();
		r.jStart = now;
		r.jFrom = p.getPos();
		r.jBase = t.getPos();
		r.jLandAt = -1;
		float total = base(p) * 14f * rageMult;
		r.jEach = total * 0.035f;
		r.jFinal = total * 0.475f;
		// how high can we lift the target?
		ServerWorld w = p.getServerWorld();
		Vec3d top = t.getPos().add(0, t.getHeight() + 0.2, 0);
		BlockHitResult up = w.raycast(new RaycastContext(top, top.add(0, 8, 0), RaycastContext.ShapeType.COLLIDER,
				RaycastContext.FluidHandling.NONE, t));
		double room = up.getType() == HitResult.Type.MISS ? 8.0 : up.getPos().y - top.y;
		r.jTop = Math.max(2.0, Math.min(7.0, room - 1.6));
		r.iframeUntil = Math.max(r.iframeUntil, now + CUTSCENE_MAX);
		p.setVelocity(Vec3d.ZERO);
		FxNet.sendTo(p, FxNet.CUTSCENE_START, p.getPos(), Vec3d.ZERO, 0f, 0);
		FxNet.send(w, FxNet.DASH, p.getPos().add(0, 1.0, 0), t.getPos().subtract(p.getPos()), 0f, 1);
	}

	private static void moveTo(Entity e, Vec3d pos, float yaw, float pitch) {
		if (e instanceof ServerPlayerEntity sp) {
			sp.networkHandler.requestTeleport(pos.x, pos.y, pos.z, yaw, pitch);
		} else {
			e.refreshPositionAndAngles(pos.x, pos.y, pos.z, yaw, pitch);
		}
		e.setVelocity(Vec3d.ZERO);
		e.fallDistance = 0f;
	}

	private static double ease(double x) {
		x = Math.max(0, Math.min(1, x));
		return x * x * (3 - 2 * x);
	}

	private static void tickJudgment(ServerPlayerEntity p, PlayerRes r, long now) {
		if (r.jTarget == null) {
			return;
		}
		ServerWorld w = p.getServerWorld();
		Entity e = w.getEntity(r.jTarget);
		long t = now - r.jStart;
		if (!(e instanceof LivingEntity target) || !target.isAlive() || t > CUTSCENE_MAX) {
			endJudgment(p, r, e instanceof LivingEntity le ? le : null, now);
			return;
		}
		p.fallDistance = 0f;
		Vec3d base = r.jBase;
		Vec3d tp = target.getPos();
		Vec3d flat = new Vec3d(base.x - r.jFrom.x, 0, base.z - r.jFrom.z);
		flat = flat.lengthSquared() < 1e-4 ? new Vec3d(0, 0, 1) : flat.normalize();
		Vec3d adj = new Vec3d(base.x, 0, base.z).subtract(flat.multiply(1.5));
		double groundY = base.y;

		if (t < 6) {
			// rush to the target
			double k = ease((t + 1) / 6.0);
			Vec3d pos = new Vec3d(r.jFrom.x + (adj.x - r.jFrom.x) * k, r.jFrom.y + (groundY - r.jFrom.y) * k,
					r.jFrom.z + (adj.z - r.jFrom.z) * k);
			float[] yp = angles(pos.add(0, 1.6, 0), chest(target));
			moveTo(p, pos, yp[0], yp[1]);
			return;
		}
		if (t == 6) {
			// grab: the target is held still
			stun(target, r);
			FxNet.send(w, FxNet.GRAB, chest(target), flat, 1f, 0);
			p.swingHand(Hand.MAIN_HAND, true);
		}
		if (t < 20) {
			// throw into the sky
			double h = r.jTop * ease((t - 6) / 12.0);
			Vec3d pos = new Vec3d(base.x, groundY + h, base.z);
			moveTo(target, pos, target.getYaw(), target.getPitch());
			Vec3d pp = pos.subtract(flat.multiply(1.5));
			float[] yp = angles(pp.add(0, 1.6, 0), chest(target));
			moveTo(p, pp, yp[0], yp[1]);
			if (t == 7) {
				FxNet.send(w, FxNet.SHOCK, new Vec3d(base.x, groundY + 0.05, base.z), Vec3d.ZERO, 5f, 1);
			}
			return;
		}
		Vec3d top = new Vec3d(base.x, groundY + r.jTop, base.z);
		if (t < 52) {
			// spin around the target and cut it
			double ang = (t - 20) * 0.85;
			Vec3d pp = top.add(Math.cos(ang) * 2.1, -0.4 + Math.sin((t - 20) * 0.3) * 0.5, Math.sin(ang) * 2.1);
			float[] yp = angles(pp.add(0, 1.6, 0), chest(target));
			moveTo(p, pp, yp[0], yp[1]);
			moveTo(target, top, target.getYaw(), target.getPitch());
			if ((t - 20) % 2 == 0) {
				Vec3d dir = chest(target).subtract(pp.add(0, 1.4, 0)).normalize();
				p.swingHand(Hand.MAIN_HAND, true);
				float roll = (float) (Math.toDegrees(ang) * 1.7);
				FxNet.send(w, FxNet.SLASH, pp.add(0, 1.4, 0), dir, roll, t % 4 == 0 ? 1 : 0);
				strike(p, target, r.jEach);
				FxNet.send(w, FxNet.IMPACT, chest(target), dir, 1.3f, 0);
			}
			return;
		}
		if (t == 52) {
			// the last blow: slam it down
			Vec3d dir = new Vec3d(0, -1, 0);
			Vec3d pp = top.add(0.0, 1.2, 0.0);
			moveTo(p, pp, p.getYaw(), 70f);
			FxNet.send(w, FxNet.FLASH, chest(target), Vec3d.ZERO, 1f, 0);
			FxNet.send(w, FxNet.SLASH, chest(target).add(0, 1.2, 0), dir, 0f, 2);
			target.setNoGravity(false);
			target.setVelocity(0, -3.0, 0);
			target.velocityModified = true;
			p.swingHand(Hand.MAIN_HAND, true);
			return;
		}
		// falling
		if (r.jLandAt < 0) {
			if (tp.y <= groundY + 0.6 || target.isOnGround() || t >= 66) {
				r.jLandAt = now;
				strike(p, target, r.jFinal);
				FxNet.send(w, FxNet.SHOCK, new Vec3d(tp.x, groundY + 0.05, tp.z), Vec3d.ZERO, 8f, 0);
				FxNet.send(w, FxNet.IMPACT, chest(target), new Vec3d(0, 1, 0), 3.2f, 2);
				FxNet.send(w, FxNet.FLASH, chest(target), Vec3d.ZERO, 1f, 1);
			} else {
				Vec3d pp = new Vec3d(tp.x, Math.max(groundY, tp.y) + 1.8, tp.z);
				moveTo(p, pp, p.getYaw(), 70f);
			}
			return;
		}
		if (now - r.jLandAt >= 8) {
			endJudgment(p, r, target, now);
		}
	}

	private static void stun(LivingEntity target, PlayerRes r) {
		target.setNoGravity(true);
		if (target instanceof MobEntity m) {
			r.jAiWas = m.isAiDisabled();
			m.setAiDisabled(true);
		}
	}

	private static void endJudgment(ServerPlayerEntity p, PlayerRes r, LivingEntity target, long now) {
		if (target != null) {
			target.setNoGravity(false);
			if (target instanceof MobEntity m) {
				m.setAiDisabled(r.jAiWas);
			}
		}
		// back to the ground next to where the target was
		Vec3d base = r.jBase != null ? r.jBase : p.getPos();
		Vec3d from = r.jFrom != null ? r.jFrom : base;
		Vec3d flat = new Vec3d(base.x - from.x, 0, base.z - from.z);
		flat = flat.lengthSquared() < 1e-4 ? new Vec3d(0, 0, 1) : flat.normalize();
		Vec3d dest = new Vec3d(base.x, base.y, base.z).subtract(flat.multiply(2.2));
		ServerWorld w = p.getServerWorld();
		if (!w.isSpaceEmpty(p, p.getBoundingBox().offset(dest.subtract(p.getPos())))) {
			dest = new Vec3d(base.x, base.y, base.z);
		}
		p.networkHandler.requestTeleport(dest.x, dest.y, dest.z, p.getYaw(), 0f);
		p.setVelocity(Vec3d.ZERO);
		p.fallDistance = 0f;
		r.jTarget = null;
		r.iframeUntil = Math.max(r.iframeUntil, now + 10);
		FxNet.sendTo(p, FxNet.CUTSCENE_END, p.getPos(), Vec3d.ZERO, 0f, 0);
	}

	/** The player left in the middle of a cutscene: give the target back its AI / gravity. */
	public static void onLeave(ServerPlayerEntity p) {
		PlayerRes r = PassiveEngine.res(p.getUuid());
		if (r.jTarget != null) {
			Entity e = p.getServerWorld().getEntity(r.jTarget);
			if (e instanceof LivingEntity le) {
				le.setNoGravity(false);
				if (le instanceof MobEntity m) {
					m.setAiDisabled(r.jAiWas);
				}
			}
			r.jTarget = null;
		}
	}

	// ------------------------------------------------------------------ per tick

	public static void tick(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!is(r)) {
			if (r.rage > 0f || r.berserkUntil > 0 || r.jTarget != null) {
				onLeave(p);
				r.rage = 0f;
				r.berserkUntil = 0;
				r.pTarget = null;
			}
			return;
		}
		tickPursuit(p, r, now);
		tickCounter(p, r, now);
		tickJudgment(p, r, now);

		if (berserk(r, now)) {
			r.rage = MAX_RAGE * (r.berserkUntil - now) / BERSERK_TICKS;
			// no Stamina trouble while enraged
			r.stamina = Math.max(r.stamina, 30f);
			r.exhausted = false;
			if (now >= r.berserkUntil) {
				endBerserk(p, r, now);
			}
		} else if (r.berserkUntil > 0) {
			endBerserk(p, r, now);
		} else if (r.rage > 0f && now - r.lastRageGain > 160 && now % 10 == 0) {
			r.rage = Math.max(0f, r.rage - 1f);
		}
	}

	/** Bits for the client: which skills are on cooldown (used to decide when to send an update). */
	public static int cooldownBits(PlayerRes r, long now) {
		int bits = 0;
		for (int i = 0; i < r.cdEnd.length; i++) {
			if (now < r.cdEnd[i]) {
				bits |= 1 << i;
			}
		}
		return bits;
	}
}
