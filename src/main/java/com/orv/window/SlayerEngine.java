package com.orv.window;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
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
import net.minecraft.util.math.Box;

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
	public static final int[] CD = {80, 260, 60, 160, 1800};
	public static final int SLASH = 0;
	public static final int PURSUIT = 1;
	public static final int COUNTER = 2;
	public static final int FATAL = 3;
	public static final int JUDGMENT = 4;

	private static final int CUTSCENE_MAX = 232;
	/** The Berserk cut-scene (3 seconds): charge, chest beating, roar, ground slam. */
	private static final int INTRO_TICKS = 60;
	private static final double LEAP_VY = 0.8;

	public static boolean is(PlayerRes r) {
		return ID.equals(r.classId);
	}

	public static boolean berserk(PlayerRes r, long now) {
		return r.berserkUntil > now;
	}

	/** True while the player has no control: Final Judgment, the Berserk cut-scene and the Fatal Strike leap. */
	public static boolean locked(PlayerRes r) {
		return r.jTarget != null || r.bIntroStart > 0 || r.fAir || r.pLock;
	}

	private static void anim(ServerPlayerEntity p, int id) {
		FxNet.send(p.getServerWorld(), FxNet.ANIM, p.getPos(), Vec3d.ZERO, id, p.getId());
	}

	// ------------------------------------------------------------------ rage

	public static void addRage(ServerPlayerEntity p, PlayerRes r, float amount, long now) {
		if (!is(r) || amount <= 0f || berserk(r, now) || locked(r) || now < r.rageLockUntil) {
			return;
		}
		// Rage is slow to build: every gain event is worth exactly 1 point
		r.rage = Math.min(MAX_RAGE, r.rage + 1f);
		r.lastRageGain = now;
		if (r.rage >= MAX_RAGE && r.lastRageGain == now && !r.rageFullShown) {
			r.rageFullShown = true;
			p.sendMessage(Text.translatable("orvwindow.msg.rage_full"), true);
		}
	}

	/** Test / op command: start the Berserk cut-scene right now. */
	public static void forceBerserk(ServerPlayerEntity p) {
		PlayerRes r = PassiveEngine.res(p.getUuid());
		if (r.bIntroStart > 0 || berserk(r, PassiveEngine.nowOf(p))) {
			return;
		}
		startBerserk(p, r, PassiveEngine.nowOf(p));
	}

	private static void startBerserk(ServerPlayerEntity p, PlayerRes r, long now) {
		org.slf4j.LoggerFactory.getLogger("orvwindow").info("[orvwindow] Berserk cut-scene starts for {}", p.getName().getString());
		// first a cut-scene (charge, chest beating, roar); the 20 seconds of Berserk start when it ends
		r.rage = MAX_RAGE;
		r.bIntroStart = now;
		r.fAir = false;
		r.iframeUntil = Math.max(r.iframeUntil, now + INTRO_TICKS + 10);
		p.setVelocity(Vec3d.ZERO);
		p.velocityModified = true;
		FxNet.sendTo(p, FxNet.CUTSCENE_START, p.getPos(), Vec3d.ZERO, 0f, 1);
		FxNet.send(p.getServerWorld(), FxNet.CHARGE, p.getPos(), Vec3d.ZERO, 0f, 0);
		anim(p, PoseClient.ROAR);
	}

	private static void tickIntro(ServerPlayerEntity p, PlayerRes r, long now) {
		long t = now - r.bIntroStart;
		ServerWorld w = p.getServerWorld();
		p.fallDistance = 0f;
		if (t >= 6 && t < 22 && t % 5 == 1) {
			p.swingHand(t % 10 == 1 ? Hand.MAIN_HAND : Hand.OFF_HAND, true);
		}
		if (t == 24) {
			// the roar: everybody around is thrown back, the body jumps
			Vec3d c = p.getPos();
			FxNet.send(w, FxNet.BERSERK_START, c, Vec3d.ZERO, 0f, 0);
			for (LivingEntity e : candidates(p, 7.0)) {
				double dx = e.getX() - c.x;
				double dz = e.getZ() - c.z;
				e.takeKnockback(1.1, -dx, -dz);
			}
			p.setVelocity(0, 0.36, 0);
			p.velocityModified = true;
		} else if (t == 50) {
			EpicLink.play(p, "GREATSWORD_AIR_SLASH");
			FxNet.send(w, FxNet.SHOCK, p.getPos().add(0, 0.05, 0), Vec3d.ZERO, 9f, 2);
			FxNet.sendTo(p, FxNet.FLASH, p.getPos(), Vec3d.ZERO, 0.6f, 1);
		}
		if (t >= INTRO_TICKS) {
			r.bIntroStart = 0;
			r.berserkUntil = now + BERSERK_TICKS;
			r.rage = MAX_RAGE;
			r.iframeUntil = Math.max(r.iframeUntil, now + 10);
			FxNet.sendTo(p, FxNet.CUTSCENE_END, p.getPos(), Vec3d.ZERO, 0f, 0);
		}
	}

	private static void endBerserk(ServerPlayerEntity p, PlayerRes r, long now) {
		r.berserkUntil = 0;
		r.rage = 0f;
		r.rageFullShown = false;
		r.rageLockUntil = now + 100;
		// the price of the rage: 8 seconds of exhaustion
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 160, 0));
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 160, 0));
		r.stamina = Math.min(r.stamina, 100f);
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
			case 14 -> {
				// Berserk is started by its own key, and only with full Rage
				if (r.rage >= MAX_RAGE && now >= r.rageLockUntil) {
					startBerserk(p, r, now);
				} else {
					p.sendMessage(Text.translatable("orvwindow.msg.rage_not_full"), true);
				}
			}
			default -> {
			}
		}
	}

	private static float base(ServerPlayerEntity p) {
		float dmg = Math.max(2f, (float) p.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE));
		// comeback bonus: with this much Health the Slayer should hit harder the closer he is to the edge
		if (p.getHealth() < p.getMaxHealth() * 0.5f) {
			dmg *= 1.25f;
		}
		return dmg;
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
		if (r.slashAt > 0 || !ready(p, r, SLASH, now) || !payStamina(p, r, 45f)) {
			return;
		}
		r.cdEnd[SLASH] = now + CD[SLASH];
		p.swingHand(Hand.MAIN_HAND, true);
		EpicLink.play(p, r.slashFlip ? "GREATSWORD_AUTO2" : "GREATSWORD_AUTO1");
		// heavy swing: the sword is pulled back first, the cut lands 6 ticks (0.3 s) later
		anim(p, r.slashFlip ? PoseClient.SLASH_L : PoseClient.SLASH_R);
		r.slashFlipNow = r.slashFlip;
		r.slashFlip = !r.slashFlip;
		r.slashAt = now + 6;
	}

	private static void tickSlash(ServerPlayerEntity p, PlayerRes r, long now) {
		if (r.slashAt <= 0 || now < r.slashAt) {
			return;
		}
		r.slashAt = 0;
		ServerWorld w = p.getServerWorld();
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVec(1f);
		float dmg = base(p) * 1.8f;
		FxNet.send(w, FxNet.SLASH, eye.add(0, -0.3, 0), look, r.slashFlipNow ? -20f : 168f, 1);
		for (LivingEntity e : candidates(p, 4.2)) {
			Vec3d to = chest(e).subtract(eye);
			double d = to.length();
			if (d > 1e-3 && to.multiply(1.0 / d).dotProduct(look) >= 0.3) {
				float d2 = e.getHealth() < 0.4f * e.getMaxHealth() ? dmg * 1.5f : dmg;
				strike(p, e, d2);
				e.takeKnockback(0.6, -look.x, -look.z);
				FxNet.send(w, FxNet.IMPACT, chest(e), look, 1.2f, 0);
			}
		}
	}

	// ------------------------------------------------------------------ 2. Relentless Pursuit

	/** Relentless Pursuit as a 3.8 s cut-scene: charge (0.5 s), dash, four fast hits, a charge, a final slash. */
	private static final int[] P_HIT = {17, 22, 27, 32, 46};
	private static final int P_DASH = 10;
	private static final int P_END = 76;

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
		r.cdEnd[PURSUIT] = now + CD[PURSUIT];
		r.pTarget = t.getUuid();
		r.pHits = 0;
		r.pStart = now;
		r.pLock = true;
		r.iframeUntil = Math.max(r.iframeUntil, now + P_END + 10);
		p.setVelocity(Vec3d.ZERO);
		faceTo(p, t);
		Vec3d d = t.getPos().subtract(p.getPos());
		d = new Vec3d(d.x, 0, d.z);
		d = d.lengthSquared() < 1e-4 ? new Vec3d(0, 0, 1) : d.normalize();
		FxNet.sendTo(p, FxNet.CUTSCENE_START, p.getPos(), d, (float) t.getId(), 2);
		FxNet.send(p.getServerWorld(), FxNet.CHARGE, p.getPos(), Vec3d.ZERO, 0f, 0);
	}

	private static void tickPursuit(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!r.pLock) {
			return;
		}
		long t = now - r.pStart;
		ServerWorld w = p.getServerWorld();
		Entity e = r.pTarget == null ? null : w.getEntity(r.pTarget);
		LivingEntity target = e instanceof LivingEntity le && le.isAlive() ? le : null;
		if (t >= P_END) {
			r.pLock = false;
			r.pTarget = null;
			return;
		}
		if (t == P_DASH && target != null) {
			dashTo(p, target);
			anim(p, PoseClient.DASH);
		}
		if (target == null) {
			return;
		}
		if (t > P_DASH) {
			faceTo(p, target);
		}
		for (int i = 0; i < P_HIT.length; i++) {
			if (t != P_HIT[i]) {
				continue;
			}
			boolean last = i == P_HIT.length - 1;
			if (target.distanceTo(p) > 2.6f) {
				dashTo(p, target);
			}
			faceTo(p, target);
			Vec3d dir = chest(target).subtract(p.getEyePos()).normalize();
			p.swingHand(Hand.MAIN_HAND, true);
			EpicLink.play(p, i % 2 == 0 ? "GREATSWORD_AUTO1" : "GREATSWORD_AUTO2");
			anim(p, last ? PoseClient.SLASH_R : (i % 2 == 0 ? PoseClient.FAST_R : PoseClient.FAST_L));
			FxNet.send(w, FxNet.SLASH, p.getEyePos().add(0, -0.2, 0), dir, (i % 2 == 0 ? 40f : -40f) + i * 7f, last ? 2 : 0);
			strike(p, target, base(p) * (last ? 1.6f : 0.75f));
			if (last) {
				target.takeKnockback(1.2, -dir.x, -dir.z);
			}
			FxNet.send(w, FxNet.IMPACT, chest(target), dir, last ? 2f : 1f, last ? 2 : 0);
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
		EpicLink.play(p, "GREATSWORD_DASH");
		anim(p, PoseClient.DASH);
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
		EpicLink.play(p, "GREATSWORD_AUTO2");
		anim(p, PoseClient.COUNTER);
		FxNet.send(w, FxNet.SLASH, p.getEyePos().add(0, -0.2, 0), dir, -55f, 3);
		strike(p, t, base(p) * 2.2f);
		t.takeKnockback(0.8, -dir.x, -dir.z);
		t.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 50, 2), p);
		FxNet.send(w, FxNet.IMPACT, chest(t), dir, 1.4f, 3);
	}

	// ------------------------------------------------------------------ 4. Fatal Strike (jump slash)

	private static int flightTicks(double vy) {
		double y = 0;
		int t = 0;
		while (t < 80) {
			y += vy;
			vy = (vy - 0.08) * 0.98;
			t++;
			if (y <= 0) {
				break;
			}
		}
		return t;
	}

	/** Leap at the target (or 6 blocks ahead), come down with a heavy slash that splits the ground. */
	private static void fatal(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!ready(p, r, FATAL, now)) {
			return;
		}
		LivingEntity t = findTarget(p, 11.0, 0.55);
		if (!payStamina(p, r, 60f)) {
			return;
		}
		r.cdEnd[FATAL] = now + CD[FATAL];
		ServerWorld w = p.getServerWorld();
		Vec3d from = p.getPos();
		Vec3d flat;
		double dist;
		if (t != null) {
			Vec3d to = t.getPos().subtract(from);
			flat = new Vec3d(to.x, 0, to.z);
			dist = Math.max(0.0, flat.length() - 1.4);
		} else {
			Vec3d look = p.getRotationVec(1f);
			flat = new Vec3d(look.x, 0, look.z);
			dist = 6.0;
		}
		flat = flat.lengthSquared() < 1e-4 ? new Vec3d(0, 0, 1) : flat.normalize();
		// stop short of walls
		Vec3d eye = p.getEyePos();
		BlockHitResult wall = w.raycast(new RaycastContext(eye, eye.add(flat.multiply(dist + 0.6)), RaycastContext.ShapeType.COLLIDER,
				RaycastContext.FluidHandling.NONE, p));
		if (wall.getType() != HitResult.Type.MISS) {
			dist = Math.max(0.0, wall.getPos().subtract(eye).length() - 1.0);
		}
		int ticks = flightTicks(LEAP_VY);
		double drag = 0;
		for (int k = 0; k < ticks; k++) {
			drag += Math.pow(0.91, k);
		}
		double vh = Math.min(1.3, dist / drag);
		r.fAir = true;
		r.fPhase = 0;
		r.fStart = now;
		r.fMaxEnd = now + 100;
		r.fFlight = ticks;
		r.fFell = false;
		r.fLastY = from.y;
		r.fTarget = t != null ? t.getUuid() : null;
		r.fDest = from.add(flat.multiply(dist));
		r.iframeUntil = Math.max(r.iframeUntil, now + ticks + 50);
		p.setVelocity(flat.x * vh, LEAP_VY, flat.z * vh);
		p.velocityModified = true;
		p.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(p));
		p.fallDistance = 0f;
		float[] yp = angles(p.getEyePos(), p.getEyePos().add(flat));
		p.networkHandler.requestTeleport(p.getX(), p.getY(), p.getZ(), yp[0], 8f);
		p.swingHand(Hand.MAIN_HAND, true);
		EpicLink.play(p, "GREATSWORD_AIR_SLASH");
		anim(p, PoseClient.LEAP);
		FxNet.send(w, FxNet.SHOCK, from.add(0, 0.05, 0), Vec3d.ZERO, 3.2f, 0);
		FxNet.send(w, FxNet.LEAP, from, flat, ticks / 20f, p.getId());
		FxNet.send(w, FxNet.DASH, from.add(0, 1.0, 0), flat.multiply(1.5), 0f, 0);
	}

	private static void tickFatal(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!r.fAir) {
			return;
		}
		long t = now - r.fStart;
		ServerWorld w = p.getServerWorld();
		p.fallDistance = 0f;
		Vec3d pos = p.getPos();
		Vec3d facing = r.fDest != null ? new Vec3d(r.fDest.x - pos.x, 0, r.fDest.z - pos.z) : p.getRotationVec(1f);
		facing = facing.lengthSquared() < 0.04 ? new Vec3d(p.getRotationVec(1f).x, 0, p.getRotationVec(1f).z) : facing;
		facing = facing.lengthSquared() < 1e-6 ? new Vec3d(0, 0, 1) : facing.normalize();
		if (r.fPhase == 0 && t >= 12) {
			// at the top of the jump: hover, sword held overhead in both hands, the energy gathers
			r.fPhase = 1;
			r.fHoldStart = now;
			p.setNoGravity(true);
			FxNet.send(w, FxNet.WAVE_CHARGE, p.getPos().add(0, 2.4, 0), facing, 0f, p.getId());
		}
		if (r.fPhase == 1) {
			p.setVelocity(Vec3d.ZERO);
			p.velocityModified = true;
			p.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(p));
			if (now - r.fHoldStart >= HOLD_TICKS) {
				// the dive: the blade comes down
				r.fPhase = 2;
				r.fFell = true;
				p.setNoGravity(false);
				Vec3d rem = r.fDest == null ? Vec3d.ZERO : new Vec3d(r.fDest.x - pos.x, 0, r.fDest.z - pos.z);
				double vh = Math.min(1.3, rem.length() / 5.0);
				Vec3d hd = rem.lengthSquared() < 1e-4 ? Vec3d.ZERO : rem.normalize().multiply(vh);
				p.setVelocity(hd.x, -1.5, hd.z);
				p.velocityModified = true;
				p.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(p));
				anim(p, PoseClient.SLAM);
				p.swingHand(Hand.MAIN_HAND, true);
				FxNet.send(w, FxNet.SLASH, p.getEyePos().add(facing.multiply(0.6)).add(0, 0.2, 0), facing, -90f, 2);
				FxNet.send(w, FxNet.DASH, p.getPos().add(0, 1.0, 0), new Vec3d(0, -2.5, 0), 0f, 0);
			}
			return;
		}
		r.fLastY = pos.y;
		boolean landed = (r.fPhase == 2 && t >= 6 && p.isOnGround()) || now >= r.fMaxEnd;
		if (!landed) {
			return;
		}
		p.setNoGravity(false);
		r.fPhase = 0;
		r.fAir = false;
		p.fallDistance = 0f;
		Vec3d c = p.getPos();
		float dmg = base(p) * 3.2f;
		float edge = 1f;
		for (LivingEntity e : candidates(p, 4.4)) {
			boolean main = e.getUuid().equals(r.fTarget);
			double dx = e.getX() - c.x;
			double dz = e.getZ() - c.z;
			double d = Math.sqrt(dx * dx + dz * dz);
			float k = main ? 1f : (float) Math.max(0.45, 0.85 - d * 0.1) * edge;
			float hit = e.getHealth() < 0.4f * e.getMaxHealth() ? dmg * 1.5f * k : dmg * k;
			strike(p, e, hit);
			e.takeKnockback(1.2, -dx, -dz);
			e.setVelocity(e.getVelocity().add(0, 0.42, 0));
			e.velocityModified = true;
			FxNet.send(w, FxNet.IMPACT, chest(e), new Vec3d(0, 1, 0), main ? 2.6f : 1.6f, 2);
		}
		r.iframeUntil = Math.max(r.iframeUntil, now + 6);
		if (!r.fFell) {
			anim(p, PoseClient.SLAM);
		}
		p.swingHand(Hand.MAIN_HAND, true);
		FxNet.send(w, FxNet.SLASH, c.add(facing.multiply(1.4)).add(0, 0.9, 0), facing, -90f, 2);
		FxNet.send(w, FxNet.SHOCK, c.add(0, 0.05, 0), Vec3d.ZERO, 7.5f, 2);
		FxNet.send(w, FxNet.FLASH, c, Vec3d.ZERO, 0.4f, 1);
		// the red energy wave rushes forward along the ground
		r.wActive = true;
		r.wPos = c.add(facing.multiply(1.5)).add(0, 0.9, 0);
		r.wDir = facing;
		r.wLeft = WAVE_TICKS;
		r.wHit.clear();
		FxNet.send(w, FxNet.WAVE, r.wPos, facing, WAVE_TICKS / 20f, p.getId());
	}

	private static final int HOLD_TICKS = 16;
	private static final int WAVE_TICKS = 20;
	private static final double WAVE_STEP = 1.7;

	private static void tickWave(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!r.wActive || r.wPos == null) {
			return;
		}
		ServerWorld w = p.getServerWorld();
		Vec3d from = r.wPos;
		Vec3d to = from.add(r.wDir.multiply(WAVE_STEP));
		BlockHitResult hit = w.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
				RaycastContext.FluidHandling.NONE, p));
		boolean wall = hit.getType() != HitResult.Type.MISS;
		Vec3d end = wall ? hit.getPos() : to;
		Box box = new Box(from, end).expand(1.7, 1.5, 1.7);
		for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, box,
				x -> x.isAlive() && x != p && !x.isSpectator() && !(x instanceof TameableEntity t && t.isOwner(p)))) {
			if (r.wHit.add(e.getUuid())) {
				strike(p, e, base(p) * 2.6f);
				e.takeKnockback(1.4, -r.wDir.x, -r.wDir.z);
				e.setVelocity(e.getVelocity().add(0, 0.3, 0));
				e.velocityModified = true;
				FxNet.send(w, FxNet.IMPACT, chest(e), r.wDir, 1.8f, 2);
			}
		}
		r.wPos = end;
		r.wLeft--;
		if (wall || r.wLeft <= 0) {
			r.wActive = false;
			if (wall) {
				FxNet.send(w, FxNet.SHOCK, end, Vec3d.ZERO, 3f, 2);
			}
		}
	}

	// ------------------------------------------------------------------ 5. Final Judgment (cutscene)

	private static void judgment(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!ready(p, r, JUDGMENT, now)) {
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
		// skills no longer cost Rage; the more Rage you hold the harder it hits (x1.0 .. x1.5)
		float rageMult = 1f + r.rage / 200f;
		r.cdEnd[JUDGMENT] = now + CD[JUDGMENT];
		r.jTarget = t.getUuid();
		r.jStart = now;
		r.jFrom = p.getPos();
		r.jBase = t.getPos();
		r.jLandAt = -1;
		r.jTotal = base(p) * 14f * rageMult;
		r.iframeUntil = Math.max(r.iframeUntil, now + CUTSCENE_MAX + 20);
		p.setVelocity(Vec3d.ZERO);
		Vec3d flat = new Vec3d(r.jBase.x - r.jFrom.x, 0, r.jBase.z - r.jFrom.z);
		flat = flat.lengthSquared() < 1e-4 ? new Vec3d(0, 0, 1) : flat.normalize();
		stun(t, r);
		org.slf4j.LoggerFactory.getLogger("orvwindow").info("[orvwindow] Final Judgment (26.5 s cut-scene) started, build 2");
		FxNet.sendTo(p, FxNet.CUTSCENE_START, p.getPos(), flat, (float) t.getId(), 3);
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

	/** Final Judgment: an 11.5 second cut-scene (3 s star intro, then only the sword work). Times in ticks; CutsceneCam script 2 uses the same clock. */
	private static final int[] J_HIT_T = {70, 76, 82, 88, 94, 100, 106, 112, 130, 150, 156, 162, 168, 174, 186, 205};
	private static final float[] J_HIT_F = {.03f, .03f, .03f, .03f, .03f, .03f, .04f, .08f, .14f, .04f, .04f, .04f, .04f, .04f, .12f, .30f};

	private static Vec3d rotY(Vec3d v, double ang) {
		double c = Math.cos(ang);
		double s = Math.sin(ang);
		return new Vec3d(v.x * c - v.z * s, 0, v.x * s + v.z * c);
	}

	private static Vec3d lerp3(Vec3d a, Vec3d b, double k) {
		return a.add(b.subtract(a).multiply(k));
	}

	private static void tickJudgment(ServerPlayerEntity p, PlayerRes r, long now) {
		if (r.jTarget == null) {
			return;
		}
		ServerWorld w = p.getServerWorld();
		Entity e = w.getEntity(r.jTarget);
		LivingEntity target = e instanceof LivingEntity le ? le : null;
		boolean alive = target != null && target.isAlive();
		long t = now - r.jStart;
		if (t >= CUTSCENE_MAX) {
			endJudgment(p, r, target, now);
			return;
		}
		p.fallDistance = 0f;
		Vec3d q = r.jBase;
		Vec3d a = new Vec3d(q.x - r.jFrom.x, 0, q.z - r.jFrom.z);
		a = a.lengthSquared() < 1e-4 ? new Vec3d(0, 0, 1) : a.normalize();

		Vec3d tp = q;
		if (alive) {
			moveTo(target, tp, target.getYaw(), target.getPitch());
		}

		// where the Slayer is
		Vec3d sp;
		if (t < 64) {
			sp = q.subtract(a.multiply(3.2));
		} else if (t < 70) {
			sp = lerp3(q.subtract(a.multiply(3.2)), q.subtract(a.multiply(1.7)), ease((t - 64) / 6.0));
		} else if (t < 116) {
			double ang = (t - 70) / 46.0 * Math.PI * 1.6;
			sp = q.add(rotY(a.multiply(-1), ang).multiply(1.7));
		} else if (t < 130) {
			sp = q.subtract(a.multiply(2.2));
		} else if (t < 146) {
			sp = q.subtract(a.multiply(2.2));
		} else if (t < 152) {
			sp = lerp3(q.subtract(a.multiply(2.2)), q.add(a.multiply(1.8)), ease((t - 146) / 6.0));
		} else if (t < 200) {
			sp = q.add(rotY(a, (t - 152) * 0.13).multiply(1.8));
		} else {
			sp = q.subtract(a.multiply(3.0));
		}
		float[] yp = angles(sp.add(0, 1.6, 0), tp.add(0, 1.0, 0));
		moveTo(p, sp, yp[0], yp[1]);

		// events
		if (t == 60) {
			FxNet.sendTo(p, FxNet.FLASH, sp, Vec3d.ZERO, 1f, 0);
			FxNet.send(w, FxNet.BERSERK_START, sp, Vec3d.ZERO, 0f, 0);
		} else if (t == 64 || t == 146) {
			FxNet.send(w, FxNet.DASH, sp.add(0, 1.0, 0), a.multiply(1.6), 0f, 1);
			anim(p, PoseClient.DASH);
		} else if (t == 218) {
			FxNet.sendTo(p, FxNet.FLASH, sp, Vec3d.ZERO, 1f, 0);
		}

		for (int i = 0; i < J_HIT_T.length; i++) {
			if (t != J_HIT_T[i]) {
				continue;
			}
			Vec3d chest = alive ? chest(target) : tp.add(0, 1.0, 0);
			Vec3d dir = chest.subtract(sp.add(0, 1.4, 0));
			dir = dir.lengthSquared() < 1e-6 ? a : dir.normalize();
			boolean heavy = i == 7 || i == 8 || i == 14 || i == 15;
			p.swingHand(Hand.MAIN_HAND, true);
			EpicLink.play(p, heavy ? "GREATSWORD_AIR_SLASH" : (i % 2 == 0 ? "GREATSWORD_AUTO1" : "GREATSWORD_AUTO2"));
			anim(p, heavy ? PoseClient.SLASH_R : (i % 2 == 0 ? PoseClient.FAST_R : PoseClient.FAST_L));
			FxNet.send(w, FxNet.SLASH, sp.add(0, 1.4, 0), dir, i * 41f - 70f, heavy ? 2 : (i % 3 == 0 ? 1 : 0));
			if (alive) {
				strike(p, target, r.jTotal * J_HIT_F[i]);
			}
			FxNet.send(w, FxNet.IMPACT, chest, dir, heavy ? 2.4f : 1.2f, heavy ? 2 : 0);
			if (i == 7) {
				FxNet.send(w, FxNet.SHOCK, new Vec3d(q.x, q.y + 0.05, q.z), Vec3d.ZERO, 9f, 2);
				FxNet.sendTo(p, FxNet.FLASH, sp, Vec3d.ZERO, 0.7f, 0);
			} else if (i == 8 || i == 14) {
				FxNet.send(w, FxNet.WAVE, tp.add(0, 0.2, 0).subtract(a.multiply(1.0)), a, 1.0f, p.getId());
			} else if (i == 15) {
				FxNet.send(w, FxNet.SHOCK, new Vec3d(q.x, q.y + 0.05, q.z), Vec3d.ZERO, 14f, 2);
				FxNet.send(w, FxNet.WAVE, tp.subtract(a.multiply(1.0)), a, 1.0f, p.getId());
				FxNet.sendTo(p, FxNet.FLASH, sp, Vec3d.ZERO, 1f, 0);
			}
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
		r.bIntroStart = 0;
		r.fAir = false;
		r.pLock = false;
		r.slashAt = 0;
		r.pTarget = null;
		r.fPhase = 0;
		r.wActive = false;
		p.setNoGravity(false);
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
			if (r.rage > 0f || r.berserkUntil > 0 || r.jTarget != null || r.bIntroStart > 0 || r.fAir) {
				onLeave(p);
				r.rage = 0f;
				r.berserkUntil = 0;
				r.bIntroStart = 0;
				r.fAir = false;
				r.pTarget = null;
					}
			return;
		}
		tickPursuit(p, r, now);
		tickCounter(p, r, now);
		tickJudgment(p, r, now);
		tickFatal(p, r, now);
		tickWave(p, r, now);
		tickSlash(p, r, now);

		if (r.bIntroStart > 0) {
			tickIntro(p, r, now);
		} else if (berserk(r, now)) {
			r.rage = MAX_RAGE * (r.berserkUntil - now) / BERSERK_TICKS;
			// no Stamina trouble while enraged
			r.stamina = Math.max(r.stamina, 30f);
			r.exhausted = false;
			if (now >= r.berserkUntil) {
				endBerserk(p, r, now);
			}
		} else if (r.berserkUntil > 0) {
			endBerserk(p, r, now);
		}
		// Rage never drains by itself: it only goes away when Berserk is used
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
