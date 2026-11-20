package com.orv.window;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The four basic spells every player can cast with Mana: 0 Arcane Bolt, 1 Flame Burst, 2 Frost Shard, 3 Thunder Strike.
 * The magic / element / energy passives build on top of these.
 */
public final class Spells {
	private Spells() {
	}

	public static final int ARCANE = 0;
	public static final int FIRE = 1;
	public static final int FROST = 2;
	public static final int THUNDER = 3;

	public static final String[] NAME = {"arcane", "fire", "freeze", "lightning"};
	private static final float[] COST = {15f, 18f, 18f, 22f};
	private static final int[] CAST_TICKS = {10, 12, 12, 16};
	private static final float[] POWER = {6f, 5f, 5f, 6f};
	private static final float CAP_SPELL_BONUS = 0.75f;
	private static final Random RAND = new Random();

	/** True while a spell is dealing damage (so PassiveEngine does not count element bonuses twice). */
	public static boolean inSpell = false;

	// ------------------------------------------------------------------ casting

	public static void request(ServerPlayerEntity p, int spell) {
		if (spell < 0 || spell >= NAME.length) {
			return;
		}
		PlayerRes r = PassiveEngine.res(p.getUuid());
		long now = PassiveEngine.nowOf(p);
		if (r.castSpell >= 0 || p.isSpectator()) {
			return;
		}
		boolean dual = PassiveEngine.has(r, "dual_casting") && r.lastSpell >= 0 && r.lastSpell != spell
				&& now - r.lastSpellTick <= 60;
		float cost = COST[spell];
		if (PassiveEngine.has(r, "arcane_efficiency")) {
			cost *= 0.9f;
		}
		if (dual) {
			cost *= 0.8f;
		}
		if (PassiveEngine.has(r, "conservation") && RAND.nextFloat() < 0.10f) {
			cost = 0f;
		}
		if (r.mana < cost) {
			p.sendMessage(Text.translatable("orvwindow.msg.no_mana"), true);
			return;
		}
		if (PassiveEngine.has(r, "overflow") && r.mana >= PassiveEngine.maxMana(r) - 0.01f && now >= r.overflowIcd) {
			r.overflowUntil = now + 100;
			r.overflowIcd = now + 400;
		}
		r.mana -= cost;
		float cut = 0f;
		if (PassiveEngine.has(r, "efficient_casting")) {
			cut += 0.10f;
		}
		if (PassiveEngine.has(r, "quick_incantation")) {
			cut += 0.12f;
		}
		cut = Math.min(cut, 0.35f);
		float ticks = CAST_TICKS[spell] * (1f - cut) * (dual ? 0.8f : 1f);
		r.castSpell = spell;
		r.castEnd = now + Math.max(2, Math.round(ticks));
	}

	/** Called every server tick for every player. */
	public static void tick(ServerPlayerEntity p, PlayerRes r, long now) {
		if (r.castSpell >= 0) {
			ServerWorld w = p.getServerWorld();
			if (now % 3 == 0) {
				w.spawnParticles(ParticleTypes.ENCHANT, p.getX(), p.getY() + 1.0, p.getZ(), 4, 0.5, 0.6, 0.5, 0.3);
			}
			if (now >= r.castEnd) {
				int s = r.castSpell;
				r.castSpell = -1;
				r.spellRecent.put(NAME[s], now);
				r.lastSpell = s;
				r.lastSpellTick = now;
				fire(p, r, s, 1f, now);
				if (PassiveEngine.has(r, "spell_echo") && now >= r.echoIcd && RAND.nextFloat() < 0.10f) {
					r.echoIcd = now + 120;
					r.echoSpell = s;
					r.echoAt = now + 8;
				}
			}
		}
		if (r.echoSpell >= 0 && now >= r.echoAt) {
			int s = r.echoSpell;
			r.echoSpell = -1;
			fire(p, r, s, 0.5f, now);
		}
	}

	// ------------------------------------------------------------------ the spell itself

	private static void fire(ServerPlayerEntity p, PlayerRes r, int spell, float power, long now) {
		ServerWorld w = p.getServerWorld();
		double range = 20.0 * (1.0 + (PassiveEngine.has(r, "gale_force") ? 0.10 : 0.0)
				+ (PassiveEngine.has(r, "mana_manipulation") ? 0.15 : 0.0));
		Vec3d eye = p.getEyePos();
		Vec3d dir = p.getRotationVec(1f);
		Vec3d end = eye.add(dir.multiply(range));
		BlockHitResult bh = w.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER,
				RaycastContext.FluidHandling.NONE, p));
		if (bh.getType() != HitResult.Type.MISS) {
			end = bh.getPos();
		}
		double len = eye.distanceTo(end);
		Box box = p.getBoundingBox().stretch(dir.multiply(len)).expand(1.0);
		EntityHitResult eh = ProjectileUtil.raycast(p, eye, end, box,
				e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator() && e.canHit() && e != p, len * len);
		Vec3d impact = end;
		LivingEntity target = null;
		if (eh != null) {
			impact = eh.getPos();
			target = (LivingEntity) eh.getEntity();
		}

		// beam + sound
		ParticleEffect pe = particleOf(spell);
		int steps = (int) Math.max(1.0, impact.distanceTo(eye));
		for (int i = 1; i <= steps; i++) {
			Vec3d pt = eye.add(dir.multiply(i));
			w.spawnParticles(pe, pt.x, pt.y - 0.15, pt.z, 1, 0.0, 0.0, 0.0, 0.0);
		}
		w.playSound(null, p.getX(), p.getY(), p.getZ(), soundOf(spell), SoundCategory.PLAYERS, 0.8f, 1.1f);

		String el = NAME[spell];
		boolean elemental = spell != ARCANE;
		if (elemental) {
			noteElement(r, el, now);
		}

		float dmg = damageOf(p, r, spell, power, now, elemental);

		// who gets hit
		List<LivingEntity> victims = new ArrayList<>();
		float amp = PassiveEngine.has(r, "spell_amplifier") ? 1.10f : 1f;
		if (target != null) {
			victims.add(target);
		} else {
			Box area = new Box(impact, impact).expand(1.5 * amp);
			for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, area, e -> e.isAlive() && e != p && !e.isSpectator())) {
				victims.add(e);
			}
		}
		float rune = PassiveEngine.has(r, "rune_master") ? 1.25f : 1f;

		for (LivingEntity v : victims) {
			float d = dmg;
			if (PassiveEngine.has(r, "magic_penetration") && v.hasStatusEffect(StatusEffects.RESISTANCE)) {
				int lvl = v.getStatusEffect(StatusEffects.RESISTANCE).getAmplifier() + 1;
				float red = Math.min(0.95f, 0.2f * lvl);
				d *= 1f + 0.15f * red / (1f - red);
			}
			hit(p, v, d);
			double push = 0.25 * (PassiveEngine.has(r, "gale_force") ? 1.2 : 1.0);
			v.takeKnockback(push, -dir.x, -dir.z);
			switch (spell) {
				case FIRE -> {
					int secs = (int) Math.ceil((4 + (PassiveEngine.has(r, "inferno_soul") ? 1 : 0)) * rune * amp);
					v.setOnFireFor(secs);
				}
				case FROST -> {
					int ticks = Math.round(160 * (PassiveEngine.has(r, "absolute_zero") ? 1.3f : 1f) * rune * amp);
					v.setFrozenTicks(Math.max(v.getFrozenTicks(), ticks));
					v.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, Math.round(60 * rune * amp), 1), p);
					if (PassiveEngine.has(r, "glacial_armor") && now >= r.glacialIcd) {
						r.glacialIcd = now + 60;
						p.setAbsorptionAmount(Math.min(4f, p.getAbsorptionAmount() + 1f));
					}
				}
				case THUNDER -> {
					strike(w, v.getPos());
					chain(p, r, v, d, now);
				}
				default -> {
				}
			}
		}
		if (spell == THUNDER && victims.isEmpty()) {
			strike(w, impact);
		}
		if (spell == FIRE) {
			// splash
			Box splash = new Box(impact, impact).expand(2.0 * amp);
			for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, splash, e -> e.isAlive() && e != p && !e.isSpectator())) {
				if (victims.contains(e)) {
					continue;
				}
				hit(p, e, dmg * 0.5f);
				e.setOnFireFor(2);
			}
			w.spawnParticles(ParticleTypes.FLAME, impact.x, impact.y, impact.z, 18, 0.5, 0.5, 0.5, 0.05);
		}
		if (spell == FROST) {
			w.spawnParticles(ParticleTypes.SNOWFLAKE, impact.x, impact.y, impact.z, 18, 0.5, 0.5, 0.5, 0.05);
		}
	}

	private static float damageOf(ServerPlayerEntity p, PlayerRes r, int spell, float power, long now, boolean elemental) {
		float b = 0f;
		if (PassiveEngine.has(r, "arcane_mastery")) {
			b += 0.08f;
		}
		if (PassiveEngine.has(r, "forbidden_knowledge")) {
			b += 0.20f;
		}
		if (PassiveEngine.has(r, "overflow") && now < r.overflowUntil) {
			b += 0.10f;
		}
		if (PassiveEngine.has(r, "spell_weaver")) {
			int kinds = 0;
			for (long t : r.spellRecent.values()) {
				if (now - t <= 200) {
					kinds++;
				}
			}
			b += 0.06f * Math.min(3, kinds);
		}
		if (PassiveEngine.has(r, "ancient_bloodline")) {
			float sum = 0f;
			for (Passives.Def d : Passives.ALL) {
				if (!r.owned.contains(d.id())) {
					continue;
				}
				if (d.category().equals("magic") || d.category().equals("element")) {
					sum += switch (d.tier()) {
						case 1 -> 0.005f;
						case 2 -> 0.01f;
						case 3 -> 0.02f;
						default -> 0.03f;
					};
				}
			}
			b += Math.min(0.15f, sum);
		}
		if (PassiveEngine.has(r, "mystic_resonance") && r.nearMagic) {
			b += 0.10f;
		}
		if (PassiveEngine.has(r, "rune_master")) {
			b += 0.10f;
		}
		if (spell == FIRE && PassiveEngine.has(r, "inferno_soul")) {
			b += 0.12f;
		}
		if (spell == FROST && PassiveEngine.has(r, "frozen_heart")) {
			b += 0.12f;
		}
		if (spell == THUNDER && PassiveEngine.has(r, "thunder_core")) {
			b += 0.12f;
		}
		if (elemental && PassiveEngine.has(r, "elemental_harmony") && now < r.harmonyUntil) {
			b += 0.15f;
		}
		b = Math.min(CAP_SPELL_BONUS, b);
		float amp = PassiveEngine.has(r, "spell_amplifier") ? 1.10f : 1f;
		return POWER[spell] * power * (1f + b) * amp;
	}

	/** Damage dealt by a spell: counted as magic damage from the player. */
	static void hit(ServerPlayerEntity p, LivingEntity victim, float dmg) {
		inSpell = true;
		try {
			victim.damage(p.getDamageSources().indirectMagic(p, p), dmg);
		} finally {
			inSpell = false;
		}
	}

	// ------------------------------------------------------------------ elements

	/** Remember that the player used an element. Three different ones within 10 s trigger Elemental Harmony. */
	public static void noteElement(PlayerRes r, String element, long now) {
		r.elemRecent.put(element, now);
		if (!PassiveEngine.has(r, "elemental_harmony") || now < r.harmonyIcd) {
			return;
		}
		int n = 0;
		for (long t : r.elemRecent.values()) {
			if (now - t <= 200) {
				n++;
			}
		}
		if (n >= 3) {
			r.harmonyUntil = now + 120;
			r.harmonyIcd = now + 240;
		}
	}

	/** Chain Lightning: 20% to pass 50% of the damage to one nearby enemy (ICD 1.5 s). */
	public static void chain(ServerPlayerEntity p, PlayerRes r, LivingEntity from, float dmg, long now) {
		if (!PassiveEngine.has(r, "chain_lightning") || now < r.chainIcd || RAND.nextFloat() >= 0.20f) {
			return;
		}
		ServerWorld w = p.getServerWorld();
		LivingEntity best = null;
		double bestD = Double.MAX_VALUE;
		for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, from.getBoundingBox().expand(5.0),
				e -> e.isAlive() && e != p && e != from && !(e instanceof PlayerEntity))) {
			double d = e.squaredDistanceTo(from);
			if (d < bestD) {
				bestD = d;
				best = e;
			}
		}
		if (best == null) {
			return;
		}
		r.chainIcd = now + 30;
		Vec3d a = from.getPos().add(0, from.getHeight() * 0.5, 0);
		Vec3d b = best.getPos().add(0, best.getHeight() * 0.5, 0);
		int n = (int) Math.max(1, a.distanceTo(b) * 2);
		for (int i = 0; i <= n; i++) {
			Vec3d pt = a.lerp(b, i / (double) n);
			w.spawnParticles(ParticleTypes.ELECTRIC_SPARK, pt.x, pt.y, pt.z, 1, 0.05, 0.05, 0.05, 0.0);
		}
		hit(p, best, dmg * 0.5f);
	}

	/** Static Charge: ten hits release lightning around the player. */
	public static void discharge(ServerPlayerEntity p, PlayerRes r, long now) {
		ServerWorld w = p.getServerWorld();
		noteElement(r, "lightning", now);
		strike(w, p.getPos());
		for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(3.0),
				e -> e.isAlive() && e != p && !(e instanceof PlayerEntity))) {
			hit(p, e, 4f);
			chain(p, r, e, 4f, now);
		}
	}

	private static void strike(ServerWorld w, Vec3d at) {
		LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w);
		if (bolt != null) {
			bolt.refreshPositionAfterTeleport(at.x, at.y, at.z);
			bolt.setCosmetic(true);
			w.spawnEntity(bolt);
		}
	}

	private static ParticleEffect particleOf(int spell) {
		return switch (spell) {
			case FIRE -> ParticleTypes.FLAME;
			case FROST -> ParticleTypes.SNOWFLAKE;
			case THUNDER -> ParticleTypes.ELECTRIC_SPARK;
			default -> ParticleTypes.WITCH;
		};
	}

	private static SoundEvent soundOf(int spell) {
		return switch (spell) {
			case FIRE -> SoundEvents.ITEM_FIRECHARGE_USE;
			case FROST -> SoundEvents.ENTITY_PLAYER_HURT_FREEZE;
			case THUNDER -> SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER;
			default -> SoundEvents.ENTITY_EVOKER_CAST_SPELL;
		};
	}
}
