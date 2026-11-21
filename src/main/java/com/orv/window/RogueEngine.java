package com.orv.window;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.List;
import java.util.Random;

/**
 * Rogue class.
 * Passives: faster movement (class stats), combo bonus after 5 hits in a row, speed burst on a kill, chance to dodge a hit.
 * Skills: Z Shadow Dash, X Blade Flurry, C Adrenaline, B Blade Wave, G Shadow Clone.
 */
public final class RogueEngine {
	private RogueEngine() {
	}

	public static final String ID = "rogue";
	/** Cooldowns (ticks): dash, flurry, adrenaline, wave, clone. They live in PlayerRes.cdEnd like the Slayer's. */
	public static final int[] CD = {60, 160, 500, 200, 600};
	private static final float[] COST = {25f, 45f, 40f, 50f, 60f};

	public static final int COMBO_HITS = 5;
	public static final int COMBO_WINDOW = 40;
	public static final float COMBO_BONUS = 1.25f;
	public static final float DODGE_CHANCE = 0.12f;
	public static final float BUFF_DAMAGE = 1.3f;

	private static final int DASH_RANGE = 8;
	private static final int FLURRY_HITS = 8;
	private static final int WAVE_TICKS = 20;
	private static final double WAVE_STEP = 1.7;
	private static final int CLONE_TICKS = 120;

	private static final Random RAND = new Random();

	public static boolean is(PlayerRes r) {
		return ID.equals(r.classId);
	}

	// ------------------------------------------------------------------ passives

	/** Damage dealt by the Rogue: combo bonus and Adrenaline. */
	public static float attackerAdjust(ServerPlayerEntity atk, DamageSource source, float amount, long now) {
		PlayerRes r = PassiveEngine.res(atk.getUuid());
		if (!is(r)) {
			return amount;
		}
		if (r.rgCombo >= COMBO_HITS && now - r.rgLastHit <= COMBO_WINDOW) {
			amount *= COMBO_BONUS;
		}
		if (now < r.rgBuffUntil) {
			amount *= BUFF_DAMAGE;
		}
		return amount;
	}

	/** Every hit the Rogue lands counts towards the combo (the bonus starts on the 6th hit and holds while you keep hitting). */
	public static void onHit(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!is(r)) {
			return;
		}
		if (now - r.rgLastHit > COMBO_WINDOW) {
			r.rgCombo = 0;
		}
		if (now != r.rgLastHit) {
			r.rgCombo++;
		}
		r.rgLastHit = now;
		if (r.rgCombo == COMBO_HITS) {
			p.sendMessage(Text.translatable("orvwindow.msg.rogue_combo"), true);
			p.getServerWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 0.8f, 1.6f);
		}
	}

	public static void onKill(ServerPlayerEntity p, PlayerRes r, LivingEntity victim, long now) {
		if (!is(r)) {
			return;
		}
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 60, 1, false, false, true));
		FxNet.send(p.getServerWorld(), FxNet.SHADOW_BURST, victim.getPos(), Vec3d.ZERO, 1.0f, 26);
	}

	/** Chance to take no damage from an attack or projectile. Returns true when the hit is dodged. */
	public static boolean dodge(ServerPlayerEntity p, PlayerRes r, DamageSource source, long now) {
		if (!is(r) || source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY) || source.getAttacker() == null
				|| now < r.rgDodgeIcd || RAND.nextFloat() >= DODGE_CHANCE) {
			return false;
		}
		r.rgDodgeIcd = now + 10;
		ServerWorld w = p.getServerWorld();
		FxNet.send(w, FxNet.SHADOW_BURST, p.getPos(), Vec3d.ZERO, 0.7f, 18);
		w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_NODAMAGE, SoundCategory.PLAYERS, 1f, 1.4f);
		p.sendMessage(Text.translatable("orvwindow.msg.rogue_dodge"), true);
		return true;
	}

	// ------------------------------------------------------------------ helpers

	private static float base(ServerPlayerEntity p) {
		return Math.max(2f, (float) p.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE));
	}

	private static Vec3d chest(Entity e) {
		return e.getPos().add(0, e.getHeight() * 0.65, 0);
	}

	private static List<LivingEntity> enemies(ServerPlayerEntity p, Box box) {
		return p.getServerWorld().getEntitiesByClass(LivingEntity.class, box,
				e -> e.isAlive() && e != p && !e.isSpectator() && !(e instanceof ArmorStandEntity)
						&& !(e instanceof TameableEntity t && t.isOwner(p)));
	}

	private static LivingEntity findTarget(ServerPlayerEntity p, double range) {
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVec(1f);
		LivingEntity best = null;
		double bestD = Double.MAX_VALUE;
		LivingEntity near = null;
		double nearD = Double.MAX_VALUE;
		for (LivingEntity e : enemies(p, p.getBoundingBox().expand(range))) {
			Vec3d to = chest(e).subtract(eye);
			double d = to.length();
			if (d > range || d < 1e-3) {
				continue;
			}
			if (to.multiply(1.0 / d).dotProduct(look) >= 0.3 && d < bestD) {
				bestD = d;
				best = e;
			}
			if (d < nearD) {
				nearD = d;
				near = e;
			}
		}
		return best != null ? best : (nearD <= range * 0.8 ? near : null);
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

	private static void anim(ServerPlayerEntity p, int id) {
		FxNet.send(p.getServerWorld(), FxNet.ANIM, p.getPos(), Vec3d.ZERO, id, p.getId());
	}

	private static boolean ready(ServerPlayerEntity p, PlayerRes r, int i, long now) {
		if (now < r.cdEnd[i]) {
			p.sendMessage(Text.translatable("orvwindow.msg.cooldown"), true);
			return false;
		}
		if (r.exhausted || r.stamina < COST[i]) {
			p.sendMessage(Text.translatable("orvwindow.msg.no_stamina"), true);
			return false;
		}
		r.stamina -= COST[i];
		r.cdEnd[i] = now + CD[i];
		return true;
	}

	// ------------------------------------------------------------------ skills

	public static void action(ServerPlayerEntity p, PlayerRes r, int id, long now) {
		if (!is(r) || !p.isAlive() || p.isSpectator()) {
			return;
		}
		switch (id) {
			case 30 -> dash(p, r, now);
			case 31 -> flurry(p, r, now);
			case 32 -> adrenaline(p, r, now);
			case 33 -> wave(p, r, now);
			case 34 -> clone(p, r, now);
			default -> {
			}
		}
	}

	/** Z: dash 8 blocks forward, cutting everything on the way. */
	private static void dash(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!ready(p, r, 0, now)) {
			return;
		}
		ServerWorld w = p.getServerWorld();
		Vec3d look = p.getRotationVec(1f);
		Vec3d dir = new Vec3d(look.x, Math.max(-0.3, Math.min(0.3, look.y)), look.z);
		dir = dir.lengthSquared() < 1e-4 ? new Vec3d(0, 0, 1) : dir.normalize();
		Vec3d from = p.getPos();
		Vec3d dest = from;
		for (double d = 0.5; d <= DASH_RANGE; d += 0.5) {
			Vec3d c = from.add(dir.multiply(d));
			if (!w.isSpaceEmpty(p, p.getBoundingBox().offset(c.subtract(from)))) {
				break;
			}
			dest = c;
		}
		r.iframeUntil = Math.max(r.iframeUntil, now + 8);
		anim(p, PoseClient.DASH);
		FxNet.send(w, FxNet.DASH | FxNet.SHADOW, from.add(0, 1.0, 0), dest.subtract(from), 0f, 0);
		FxNet.send(w, FxNet.SHADOW_BURST, from, Vec3d.ZERO, 0.9f, 22);
		FxNet.send(w, FxNet.SHADOW_BURST, dest, Vec3d.ZERO, 0.8f, 18);
		Box path = new Box(from, dest).expand(1.2, 1.0, 1.2).stretch(0, 1.8, 0);
		Vec3d seg = dest.subtract(from);
		double len2 = Math.max(1e-4, seg.lengthSquared());
		for (LivingEntity e : enemies(p, path)) {
			Vec3d rel = e.getPos().subtract(from);
			double k = MathHelper.clamp(rel.dotProduct(seg) / len2, 0, 1);
			if (e.getPos().distanceTo(from.add(seg.multiply(k))) > 1.8) {
				continue;
			}
			SlayerEngine.strike(p, e, base(p) * 1.2f);
			FxNet.send(w, FxNet.SLASH | FxNet.SHADOW, chest(e), dir, RAND.nextFloat() * 60f - 30f, 3);
			FxNet.send(w, FxNet.IMPACT | FxNet.SHADOW, chest(e), dir, 1.0f, 0);
		}
		p.networkHandler.requestTeleport(dest.x, dest.y, dest.z, p.getYaw(), p.getPitch());
		p.setVelocity(dir.multiply(0.3));
		p.velocityModified = true;
		p.fallDistance = 0f;
	}

	/** X: eight very fast cuts on the target in front. */
	private static void flurry(ServerPlayerEntity p, PlayerRes r, long now) {
		LivingEntity t = findTarget(p, 5.0);
		if (t == null) {
			p.sendMessage(Text.translatable("orvwindow.msg.no_target"), true);
			return;
		}
		if (!ready(p, r, 1, now)) {
			return;
		}
		r.rgFTarget = t.getUuid();
		r.rgFHits = 0;
		r.rgFNext = now + 1;
		r.iframeUntil = Math.max(r.iframeUntil, now + 6);
	}

	private static void tickFlurry(ServerPlayerEntity p, PlayerRes r, long now) {
		if (r.rgFTarget == null || now < r.rgFNext) {
			return;
		}
		ServerWorld w = p.getServerWorld();
		Entity e = w.getEntity(r.rgFTarget);
		if (!(e instanceof LivingEntity t) || !t.isAlive() || r.rgFHits >= FLURRY_HITS || t.distanceTo(p) > 7f) {
			r.rgFTarget = null;
			return;
		}
		int i = r.rgFHits;
		boolean last = i == FLURRY_HITS - 1;
		if (t.distanceTo(p) > 2.4f) {
			Vec3d flat = new Vec3d(t.getX() - p.getX(), 0, t.getZ() - p.getZ());
			flat = flat.lengthSquared() < 1e-4 ? new Vec3d(0, 0, 1) : flat.normalize();
			Vec3d dest = t.getPos().subtract(flat.multiply(1.3));
			if (w.isSpaceEmpty(p, p.getBoundingBox().offset(dest.subtract(p.getPos())))) {
				FxNet.send(w, FxNet.DASH | FxNet.SHADOW, p.getPos().add(0, 1.0, 0), dest.subtract(p.getPos()), 0f, 0);
				p.networkHandler.requestTeleport(dest.x, dest.y, dest.z, p.getYaw(), p.getPitch());
			}
		}
		float[] yp = angles(p.getEyePos(), chest(t));
		p.networkHandler.requestTeleport(p.getX(), p.getY(), p.getZ(), yp[0], yp[1]);
		Vec3d dir = chest(t).subtract(p.getEyePos()).normalize();
		p.swingHand(i % 2 == 0 ? Hand.MAIN_HAND : Hand.OFF_HAND, true);
		anim(p, last ? PoseClient.SLASH_R : (i % 2 == 0 ? PoseClient.FAST_R : PoseClient.FAST_L));
		FxNet.send(w, FxNet.SLASH | FxNet.SHADOW, p.getEyePos().add(0, -0.25, 0), dir, (i % 2 == 0 ? 55f : -55f) + RAND.nextFloat() * 30f - 15f, last ? 2 : 3);
		SlayerEngine.strike(p, t, base(p) * (last ? 0.9f : 0.45f));
		FxNet.send(w, FxNet.IMPACT | FxNet.SHADOW, chest(t), dir, last ? 1.5f : 0.7f, 0);
		if (last) {
			t.takeKnockback(0.8, -dir.x, -dir.z);
		}
		r.rgFHits++;
		r.rgFNext = now + 2;
	}

	/** C: 8 seconds of more speed and damage. */
	private static void adrenaline(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!ready(p, r, 2, now)) {
			return;
		}
		r.rgBuffUntil = now + 160;
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 160, 1, false, false, true));
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.HASTE, 160, 1, false, false, true));
		ServerWorld w = p.getServerWorld();
		FxNet.send(w, FxNet.SHADOW_BURST, p.getPos(), Vec3d.ZERO, 2.2f, 50);
		FxNet.send(w, FxNet.SHADOW_AURA, p.getPos(), Vec3d.ZERO, 8f, p.getId());
		w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_EVOKER_PREPARE_ATTACK, SoundCategory.PLAYERS, 0.8f, 1.5f);
		FxNet.send(w, FxNet.SHOCK | FxNet.SHADOW, p.getPos().add(0, 0.05, 0), Vec3d.ZERO, 4f, 1);
	}

	/** B: a sword wave that flies 34 blocks and cuts through everything it meets. */
	private static void wave(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!ready(p, r, 3, now)) {
			return;
		}
		ServerWorld w = p.getServerWorld();
		Vec3d look = p.getRotationVec(1f);
		Vec3d dir = look.normalize();
		p.swingHand(Hand.MAIN_HAND, true);
		anim(p, PoseClient.SLASH_R);
		FxNet.send(w, FxNet.SLASH | FxNet.SHADOW, p.getEyePos().add(0, -0.2, 0).add(dir.multiply(1.0)), dir, -90f, 2);
		r.rgWActive = true;
		r.rgWPos = p.getEyePos().add(0, -0.5, 0).add(dir.multiply(1.2));
		r.rgWDir = dir;
		r.rgWLeft = WAVE_TICKS;
		r.rgWHit.clear();
		FxNet.send(w, FxNet.WAVE | FxNet.SHADOW, r.rgWPos, dir, WAVE_TICKS / 20f, p.getId());
	}

	private static void tickWave(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!r.rgWActive || r.rgWPos == null) {
			return;
		}
		ServerWorld w = p.getServerWorld();
		Vec3d from = r.rgWPos;
		Vec3d to = from.add(r.rgWDir.multiply(WAVE_STEP));
		BlockHitResult hit = w.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
				RaycastContext.FluidHandling.NONE, p));
		boolean wall = hit.getType() != HitResult.Type.MISS;
		Vec3d end = wall ? hit.getPos() : to;
		for (LivingEntity e : enemies(p, new Box(from, end).expand(1.4, 1.2, 1.4))) {
			if (r.rgWHit.add(e.getUuid())) {
				SlayerEngine.strike(p, e, base(p) * 1.8f);
				e.takeKnockback(0.7, -r.rgWDir.x, -r.rgWDir.z);
				FxNet.send(w, FxNet.IMPACT | FxNet.SHADOW, chest(e), r.rgWDir, 1.4f, 0);
				FxNet.send(w, FxNet.SHADOW_BURST, e.getPos(), Vec3d.ZERO, 0.8f, 16);
			}
		}
		r.rgWPos = end;
		r.rgWLeft--;
		if (wall || r.rgWLeft <= 0) {
			r.rgWActive = false;
		}
	}

	/** G: leave a shadow copy behind; monsters go for it while the Rogue turns invisible for a moment. */
	private static void clone(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!ready(p, r, 4, now)) {
			return;
		}
		removeClone(p, r);
		ServerWorld w = p.getServerWorld();
		ArmorStandEntity s = new ArmorStandEntity(w, p.getX(), p.getY(), p.getZ());
		s.setInvisible(true);
		s.setInvulnerable(true);
		s.setNoGravity(true);
		s.setSilent(true);
		s.setYaw(p.getYaw());
		s.setBodyYaw(p.bodyYaw);
		s.addCommandTag("orvwindow_clone");
		if (!w.spawnEntity(s)) {
			return;
		}
		r.rgClone = s.getUuid();
		r.rgCloneEnd = now + CLONE_TICKS;
		p.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, 80, 0, false, false, true));
		taunt(p, s);
		w.spawnParticles(ParticleTypes.SQUID_INK, p.getX(), p.getY() + 1.0, p.getZ(), 20, 0.4, 0.8, 0.4, 0.02);
		w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.PLAYERS, 1f, 1f);
		FxNet.send(w, FxNet.CLONE, p.getPos(), new Vec3d(p.bodyYaw, p.getPitch(), p.headYaw), CLONE_TICKS / 20f, p.getId());
	}

	/** Monsters near the clone that were after the Rogue go for the clone instead. */
	private static void taunt(ServerPlayerEntity p, LivingEntity s) {
		for (MobEntity m : p.getServerWorld().getEntitiesByClass(MobEntity.class, s.getBoundingBox().expand(16.0),
				m -> m.isAlive() && m.getTarget() == p)) {
			m.setTarget(s);
		}
	}

	private static void removeClone(ServerPlayerEntity p, PlayerRes r) {
		if (r.rgClone == null) {
			return;
		}
		ServerWorld w = p.getServerWorld();
		Entity e = w.getEntity(r.rgClone);
		if (e != null) {
			for (MobEntity m : w.getEntitiesByClass(MobEntity.class, e.getBoundingBox().expand(24.0), m -> m.getTarget() == e)) {
				m.setTarget(null);
			}
			w.spawnParticles(ParticleTypes.SQUID_INK, e.getX(), e.getY() + 1.0, e.getZ(), 20, 0.3, 0.7, 0.3, 0.03);
			e.discard();
		}
		r.rgClone = null;
	}

	// ------------------------------------------------------------------ per tick

	public static void tick(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!is(r)) {
			if (r.rgClone != null || r.rgFTarget != null || r.rgWActive) {
				onLeave(p);
			}
			return;
		}
		tickFlurry(p, r, now);
		tickWave(p, r, now);
		if (r.rgClone != null) {
			Entity e = p.getServerWorld().getEntity(r.rgClone);
			if (now >= r.rgCloneEnd || !(e instanceof LivingEntity s)) {
				removeClone(p, r);
			} else if (now % 5 == 0) {
				taunt(p, s);
			}
		}
		if (now < r.rgBuffUntil && now % 4 == 0) {
			p.getServerWorld().spawnParticles(ParticleTypes.WITCH, p.getX(), p.getY() + 0.8, p.getZ(), 2, 0.25, 0.5, 0.25, 0.0);
		}
	}

	public static void onLeave(ServerPlayerEntity p) {
		PlayerRes r = PassiveEngine.res(p.getUuid());
		removeClone(p, r);
		r.rgFTarget = null;
		r.rgWActive = false;
		r.rgBuffUntil = 0;
		r.rgCombo = 0;
	}
}
