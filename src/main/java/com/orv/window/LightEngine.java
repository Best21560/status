package com.orv.window;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Lightbender class: Divine Orb (Z), Seraph Domain (X), Halo Shatter (C) and the class passives
 * (more spell power, more Mana regen, takes 1.5x damage, deals 1/3 physical damage).
 */
public final class LightEngine {
	private LightEngine() {
	}

	public static final String ID = "lightbender";
	public static final float ORB_HP = 1200f;
	/** Cooldowns in ticks: Divine Orb, Seraph Domain, Halo Shatter. */
	public static final int[] CD = {60, 240, 1200};

	private static final Random RAND = new Random();
	private static final Map<UUID, Long> HALOS = new HashMap<>();

	/** A homing light of the shattered Halo, counted down until it hits. */
	public static final class Proj {
		UUID target;
		long hitAt;
		float dmg;
	}

	public static boolean is(PlayerRes r) {
		return ID.equals(r.classId);
	}

	// ------------------------------------------------------------------ class passives

	/** Damage dealt: magic 1.25x, physical 1/3. */
	public static float attackerAdjust(ServerPlayerEntity atk, DamageSource source, float amount) {
		PlayerRes r = PassiveEngine.res(atk.getUuid());
		if (!is(r)) {
			return amount;
		}
		if (source.isOf(DamageTypes.INDIRECT_MAGIC) || source.isOf(DamageTypes.MAGIC)) {
			return amount * 1.25f;
		}
		if (source.getSource() == atk || source.getSource() instanceof net.minecraft.entity.projectile.PersistentProjectileEntity) {
			return amount / 3f;
		}
		return amount;
	}

	/** Damage taken: 1.5x for the Lightbender; the Halo of any caster nearby cuts it (60% self, 20% allies within 10 blocks). */
	public static float defenderAdjust(ServerPlayerEntity vp, PlayerRes r, float amount, long now) {
		if (is(r)) {
			amount *= 1.5f;
		}
		if (HALOS.isEmpty()) {
			return amount;
		}
		float cut = 0f;
		for (Map.Entry<UUID, Long> e : HALOS.entrySet()) {
			if (e.getValue() <= now) {
				continue;
			}
			if (e.getKey().equals(vp.getUuid())) {
				cut = Math.max(cut, 0.6f);
				continue;
			}
			ServerPlayerEntity owner = vp.getServer() == null ? null : vp.getServer().getPlayerManager().getPlayer(e.getKey());
			if (owner != null && owner.getWorld() == vp.getWorld() && owner.squaredDistanceTo(vp) <= 100.0) {
				cut = Math.max(cut, 0.2f);
			}
		}
		return amount * (1f - cut);
	}

	// ------------------------------------------------------------------ skills

	public static void action(ServerPlayerEntity p, PlayerRes r, int id, long now) {
		if (!is(r) || !p.isAlive()) {
			return;
		}
		switch (id) {
			case 20 -> divineOrb(p, r, now);
			case 21 -> seraph(p, r, now);
			case 22 -> halo(p, r, now);
			case 23 -> shoot(p, r, now);
			default -> {
			}
		}
	}

	private static boolean spend(ServerPlayerEntity p, PlayerRes r, float cost) {
		if (r.mana < cost) {
			p.sendMessage(Text.translatable("orvwindow.msg.no_mana"), true);
			return false;
		}
		r.mana -= cost;
		return true;
	}

	private static boolean ready(ServerPlayerEntity p, PlayerRes r, int i, long now) {
		if (now < r.lbCd[i]) {
			p.sendMessage(Text.translatable("orvwindow.msg.cooldown"), true);
			return false;
		}
		return true;
	}

	private static void divineOrb(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!ready(p, r, 0, now) || !spend(p, r, 100f)) {
			return;
		}
		r.lbCd[0] = now + CD[0];
		if (r.orbsOn) {
			// recast: every orb gets its Health back
			for (int i = 0; i < 8; i++) {
				if (r.orbHp[i] > 0f || r.orbWave2At > 0 || i < 4) {
					r.orbHp[i] = ORB_HP;
				}
			}
			if (r.orbWave2At > 0) {
				r.orbWave2At = 0;
				for (int i = 4; i < 8; i++) {
					r.orbHp[i] = ORB_HP;
				}
			}
		} else {
			for (int i = 0; i < 8; i++) {
				r.orbHp[i] = i < 4 ? ORB_HP : 0f;
			}
			r.orbsOn = true;
			r.orbWave2At = now + 12;
		}
		circle(p, 0);
	}

	private static void circle(ServerPlayerEntity p, int variant) {
		double yaw = Math.toRadians(p.bodyYaw);
		Vec3d fwd = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
		Vec3d c = p.getPos().add(fwd.multiply(-1.0)).add(0, 1.7, 0);
		FxNet.send(p.getServerWorld(), FxNet.LB_CIRCLE, c, fwd, p.bodyYaw, variant);
	}

	private static int alive(PlayerRes r) {
		int n = 0;
		for (int i = 0; i < 8; i++) {
			if (r.orbHp[i] > 0f) {
				n++;
			}
		}
		return n;
	}

	/** M1 while the orbs are out: one orb flies along the line of sight (15 blocks) and hits for 10 x 0.25 per orb left. */
	private static void shoot(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!r.orbsOn || now < r.lbShootAt) {
			return;
		}
		int alive = alive(r);
		if (alive <= 0) {
			return;
		}
		int pick = -1;
		for (int i = 0; i < 8; i++) {
			if (r.orbHp[i] > 0f && (pick < 0 || r.orbHp[i] > r.orbHp[pick])) {
				pick = i;
			}
		}
		r.lbShootAt = now + 8;
		ServerWorld w = p.getServerWorld();
		Vec3d eye = p.getEyePos();
		Vec3d look = p.getRotationVec(1f);
		Vec3d from = LightGeo.orbPos(p.getPos(), p.bodyYaw, pick, w.getTime() / 20.0);
		LivingEntity hit = null;
		Vec3d end = eye.add(look.multiply(15.0));
		for (double d = 0.8; d <= 15.0 && hit == null; d += 0.5) {
			Vec3d pt = eye.add(look.multiply(d));
			List<Entity> list = w.getOtherEntities(p, new Box(pt.x - 0.6, pt.y - 0.6, pt.z - 0.6, pt.x + 0.6, pt.y + 0.6, pt.z + 0.6),
					e -> e instanceof LivingEntity le && le.isAlive() && !(e instanceof PlayerEntity));
			if (!list.isEmpty()) {
				hit = (LivingEntity) list.get(0);
				end = hit.getPos().add(0, hit.getHeight() * 0.6, 0);
			}
		}
		r.orbHp[pick] = Math.max(0f, r.orbHp[pick] - 100f);
		FxNet.send(w, FxNet.ORB_SHOT, from, end.subtract(from), 0f, 0);
		if (hit != null) {
			hit.timeUntilRegen = 0;
			Spells.hit(p, hit, 10f * 0.25f * alive);
			FxNet.send(w, FxNet.IMPACT, end, look.multiply(-1), 1.0f, 3);
		}
		if (alive(r) == 0) {
			r.orbsOn = false;
		}
	}

	private static final float[] SERAPH_RADIUS = {25f, 20f, 15f};
	private static final float[] SERAPH_PCT = {0.15f, 0.20f, 0.25f};

	private static void seraph(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!ready(p, r, 1, now) || !spend(p, r, 50f)) {
			return;
		}
		r.lbCd[1] = now + CD[1];
		seraphRound(p, r, 0, now);
		r.seraphStage = 1;
		r.seraphAt = now + 20;
	}

	private static void seraphRound(ServerPlayerEntity p, PlayerRes r, int k, long now) {
		ServerWorld w = p.getServerWorld();
		float radius = SERAPH_RADIUS[k];
		float pct = SERAPH_PCT[k];
		FxNet.send(w, FxNet.HEAL_PULSE, p.getPos(), Vec3d.ZERO, radius, k);
		for (ServerPlayerEntity sp : w.getPlayers()) {
			if (sp.isSpectator() || !sp.isAlive() || sp.squaredDistanceTo(p) > radius * radius) {
				continue;
			}
			healPlayer(r, sp, pct, now);
		}
		for (int i = 0; i < 8; i++) {
			if (r.orbHp[i] > 0f) {
				r.orbHp[i] = Math.min(ORB_HP, r.orbHp[i] + ORB_HP * pct);
				r.healPct += pct * 100f;
				r.lastHealAt = now;
			}
		}
	}

	private static void healPlayer(PlayerRes casterRes, ServerPlayerEntity target, float frac, long now) {
		target.heal(target.getMaxHealth() * frac);
		casterRes.healPct += frac * 100f;
		casterRes.lastHealAt = now;
	}

	private static void halo(ServerPlayerEntity p, PlayerRes r, long now) {
		if (r.haloUntil > now) {
			return;
		}
		if (!ready(p, r, 2, now)) {
			return;
		}
		if (r.healPct < 200f) {
			p.sendMessage(Text.translatable("orvwindow.msg.no_heal"), true);
			return;
		}
		if (!spend(p, r, 350f)) {
			return;
		}
		r.haloPct = r.healPct;
		r.healPct -= 200f;
		r.lbCd[2] = now + CD[2];
		r.haloUntil = now + 400;
		HALOS.put(p.getUuid(), r.haloUntil);
		p.setHealth(p.getMaxHealth());
		for (StatusEffectInstance e : new ArrayList<>(p.getStatusEffects())) {
			if (e.getEffectType().getCategory() == StatusEffectCategory.HARMFUL) {
				p.removeStatusEffect(e.getEffectType());
			}
		}
		p.extinguish();
		FxNet.send(p.getServerWorld(), FxNet.HALO_BURST, p.getPos(), Vec3d.ZERO, 1f, 0);
	}

	/** The Halo breaks: haloPct / 3 lights; 40% hunt enemies (5 damage each), 60% circle around the caster and heal allies they touch. */
	private static void shatter(ServerPlayerEntity p, PlayerRes r, long now) {
		r.haloUntil = 0;
		HALOS.remove(p.getUuid());
		ServerWorld w = p.getServerWorld();
		int total = Math.min(150, (int) (r.haloPct / 3f));
		int homing = (int) (total * 0.4f);
		int ring = total - homing;
		List<LivingEntity> foes = new ArrayList<>(w.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(30.0),
				e -> e.isAlive() && e != p && e instanceof Monster));
		foes.sort((a, b) -> Double.compare(a.squaredDistanceTo(p), b.squaredDistanceTo(p)));
		if (foes.isEmpty()) {
			ring += homing;
			homing = 0;
		}
		for (int k = 0; k < homing; k++) {
			LivingEntity t = foes.get(k % foes.size());
			Proj pr = new Proj();
			pr.target = t.getUuid();
			double dist = t.distanceTo(p);
			int ticks = (int) Math.max(8, dist / 1.4);
			pr.hitAt = now + ticks;
			pr.dmg = 5f;
			r.projs.add(pr);
			if (k < 40) {
				Vec3d start = p.getPos().add((RAND.nextDouble() - 0.5) * 1.2, 2.6 + RAND.nextDouble() * 0.5, (RAND.nextDouble() - 0.5) * 1.2);
				Vec3d end = t.getPos().add(0, t.getHeight() * 0.6, 0);
				FxNet.send(w, FxNet.PROJ, start, end.subtract(start), ticks / 20f, 0);
			}
		}
		r.ringCount += ring;
		r.ringUntil = now + 600;
		FxNet.send(w, FxNet.HALO_BURST, p.getPos().add(0, 1.0, 0), Vec3d.ZERO, 1f, 1);
	}

	// ------------------------------------------------------------------ per tick

	public static void tick(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!is(r)) {
			if (r.orbsOn || r.haloUntil > 0 || r.ringCount > 0 || r.extraUntil > 0) {
				onLeave(p);
			}
			return;
		}
		ServerWorld w = p.getServerWorld();
		// Mana regeneration +0.5 per second
		float maxMana = PassiveEngine.maxMana(r);
		r.mana = Math.min(maxMana, r.mana + 0.5f / 20f);

		if (r.orbWave2At > 0 && now >= r.orbWave2At) {
			r.orbWave2At = 0;
			for (int i = 4; i < 8; i++) {
				r.orbHp[i] = ORB_HP;
			}
			circle(p, 1);
		}
		if (r.healPct > 0f && now - r.lastHealAt > 200) {
			r.healPct = 0f;
		}
		if (r.orbsOn && alive(r) == 0 && r.orbWave2At == 0) {
			r.orbsOn = false;
		}

		// Seraph Domain follow-up rounds
		if (r.seraphStage > 0 && now >= r.seraphAt) {
			if (r.mana >= 25f) {
				r.mana -= 25f;
				seraphRound(p, r, r.seraphStage, now);
				r.seraphStage++;
				r.seraphAt = now + 20;
				if (r.seraphStage > 2) {
					r.seraphStage = 0;
				}
			} else {
				r.seraphStage = 0;
			}
		}

		// Seraph passive: over 150% heal summons four free orbs for 15 s
		if (r.healPct > 150f && r.extraUntil <= now && now >= r.extraIcd) {
			r.extraUntil = now + 300;
			r.extraIcd = now + 300;
		}
		if (r.extraUntil > now && now >= r.extraHitAt) {
			r.extraHitAt = now + 6;
			double t = w.getTime() / 20.0;
			for (int k = 0; k < 4; k++) {
				Vec3d pos = LightGeo.extraPos(p.getPos(), k, t);
				for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class,
						new Box(pos.x - 2.2, pos.y - 2.2, pos.z - 2.2, pos.x + 2.2, pos.y + 2.2, pos.z + 2.2),
						e -> e.isAlive() && e != p && !(e instanceof PlayerEntity))) {
					e.timeUntilRegen = 0;
					Spells.hit(p, e, 10f);
				}
			}
		}

		// Halo
		if (r.haloUntil > 0) {
			if (now >= r.haloUntil) {
				shatter(p, r, now);
			} else {
				HALOS.put(p.getUuid(), r.haloUntil);
			}
		}
		// homing lights arriving
		if (!r.projs.isEmpty()) {
			for (int i = r.projs.size() - 1; i >= 0; i--) {
				Proj pr = r.projs.get(i);
				if (now < pr.hitAt) {
					continue;
				}
				r.projs.remove(i);
				Entity t = w.getEntity(pr.target);
				if (t instanceof LivingEntity le && le.isAlive()) {
					le.timeUntilRegen = 0;
					Spells.hit(p, le, pr.dmg);
					if (i % 3 == 0) {
						FxNet.send(w, FxNet.IMPACT, le.getPos().add(0, le.getHeight() * 0.6, 0), Vec3d.ZERO, 0.7f, 3);
					}
				}
			}
		}
		// ring lights heal whoever they touch
		if (r.ringCount > 0) {
			if (now >= r.ringUntil) {
				r.ringCount = 0;
			} else if (now % 4 == 0) {
				ringHeal(p, r, w, now);
			}
		}

		sendState(p, r, now);
	}

	private static void ringHeal(ServerPlayerEntity p, PlayerRes r, ServerWorld w, long now) {
		for (ServerPlayerEntity sp : w.getPlayers()) {
			if (r.ringCount <= 0) {
				return;
			}
			if (sp == p || sp.isSpectator() || !sp.isAlive() || Math.abs(sp.getY() - p.getY()) > 2.0) {
				continue;
			}
			double dx = sp.getX() - p.getX();
			double dz = sp.getZ() - p.getZ();
			double d = Math.sqrt(dx * dx + dz * dz);
			if (d < 1.2 || d > 3.6 || now - r.ringTouch.getOrDefault(sp.getUuid(), 0L) < 10) {
				continue;
			}
			r.ringTouch.put(sp.getUuid(), now);
			r.ringCount--;
			healPlayer(r, sp, 0.05f, now);
		}
		Vec3d base = p.getPos();
		double t = w.getTime() / 20.0;
		for (int i = 0; i < 8 && r.ringCount > 0; i++) {
			if (r.orbHp[i] <= 0f) {
				continue;
			}
			Vec3d o = LightGeo.orbPos(base, p.bodyYaw, i, t);
			double d = Math.sqrt((o.x - base.x) * (o.x - base.x) + (o.z - base.z) * (o.z - base.z));
			UUID key = new UUID(0L, i);
			if (d < 1.2 || d > 3.6 || now - r.ringTouch.getOrDefault(key, 0L) < 10) {
				continue;
			}
			r.ringTouch.put(key, now);
			r.ringCount--;
			r.orbHp[i] = Math.min(ORB_HP, r.orbHp[i] + ORB_HP * 0.05f);
			r.healPct += 5f;
			r.lastHealAt = now;
		}
	}

	// ------------------------------------------------------------------ state for the clients

	private static boolean active(PlayerRes r, long now) {
		return r.orbsOn || r.haloUntil > now || r.extraUntil > now || r.ringCount > 0 || r.healPct > 0f
				|| r.lbCd[0] > now || r.lbCd[1] > now || r.lbCd[2] > now;
	}

	private static void sendState(ServerPlayerEntity p, PlayerRes r, long now) {
		boolean act = active(r, now);
		int mask = 0;
		int h = 17;
		for (int i = 0; i < 8; i++) {
			if (r.orbHp[i] > 0f) {
				mask |= 1 << i;
			}
			h = h * 31 + Math.round(r.orbHp[i] / ORB_HP * 40f);
		}
		int haloLeft = r.haloUntil > now ? (int) (r.haloUntil - now) : 0;
		int extraLeft = r.extraUntil > now ? (int) (r.extraUntil - now) : 0;
		int decay = r.healPct > 0f ? (int) Math.max(0, 200 - (now - r.lastHealAt)) : 0;
		h = h * 31 + mask;
		h = h * 31 + haloLeft / 10;
		h = h * 31 + extraLeft / 10;
		h = h * 31 + r.ringCount;
		h = h * 31 + (int) r.healPct;
		for (int i = 0; i < 3; i++) {
			h = h * 31 + (int) Math.max(0, (r.lbCd[i] - now) / 10);
		}
		boolean changed = h != r.sentLbHash;
		if (!act && !changed) {
			return;
		}
		if (!changed && now - r.sentLbAt < 10) {
			return;
		}
		r.sentLbHash = h;
		r.sentLbAt = now;
		PacketByteBuf out = PacketByteBufs.create();
		out.writeVarInt(p.getId());
		out.writeVarInt(mask);
		for (int i = 0; i < 8; i++) {
			out.writeByte(Math.round(Math.max(0f, r.orbHp[i]) / ORB_HP * 100f));
		}
		out.writeVarInt(haloLeft);
		out.writeVarInt(extraLeft);
		out.writeVarInt(r.ringCount);
		out.writeFloat(r.healPct);
		out.writeVarInt(decay);
		for (int i = 0; i < 3; i++) {
			out.writeVarInt((int) Math.max(0, r.lbCd[i] - now));
		}
		for (ServerPlayerEntity sp : PlayerLookup.around(p.getServerWorld(), p.getPos(), 80.0)) {
			if (ServerPlayNetworking.canSend(sp, OrvWindowMod.LIGHT)) {
				PacketByteBuf copy = PacketByteBufs.copy(out);
				ServerPlayNetworking.send(sp, OrvWindowMod.LIGHT, copy);
			}
		}
	}

	public static void onLeave(ServerPlayerEntity p) {
		PlayerRes r = PassiveEngine.res(p.getUuid());
		HALOS.remove(p.getUuid());
		r.orbsOn = false;
		java.util.Arrays.fill(r.orbHp, 0f);
		r.orbWave2At = 0;
		r.healPct = 0f;
		r.haloUntil = 0;
		r.ringCount = 0;
		r.extraUntil = 0;
		r.seraphStage = 0;
		r.projs.clear();
		r.ringTouch.clear();
		java.util.Arrays.fill(r.lbCd, 0L);
		// final empty state so clients stop drawing
		r.sentLbHash = 0;
		PacketByteBuf out = PacketByteBufs.create();
		out.writeVarInt(p.getId());
		out.writeVarInt(0);
		for (int i = 0; i < 8; i++) {
			out.writeByte(0);
		}
		out.writeVarInt(0);
		out.writeVarInt(0);
		out.writeVarInt(0);
		out.writeFloat(0f);
		out.writeVarInt(0);
		for (int i = 0; i < 3; i++) {
			out.writeVarInt(0);
		}
		if (p.getServer() != null) {
			for (ServerPlayerEntity sp : PlayerLookup.around(p.getServerWorld(), p.getPos(), 80.0)) {
				if (ServerPlayNetworking.canSend(sp, OrvWindowMod.LIGHT)) {
					ServerPlayNetworking.send(sp, OrvWindowMod.LIGHT, PacketByteBufs.copy(out));
				}
			}
		}
	}
}
