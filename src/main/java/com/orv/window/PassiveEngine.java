package com.orv.window;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityGroup;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.AxeItem;
import net.minecraft.item.SwordItem;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes the passive skills work (server side).
 * Numbers follow the design document. Passives not handled here are listed as "not active yet" in Passives.IMPLEMENTED.
 */
public final class PassiveEngine {
	private static final Random RAND = new Random();
	private static final Map<UUID, PlayerRes> RES = new ConcurrentHashMap<>();

	private static final UUID U_ARMOR = UUID.fromString("5b1f0c3a-0000-4000-8000-0000000000a1");
	private static final UUID U_HP = UUID.fromString("5b1f0c3a-0000-4000-8000-0000000000a2");
	private static final UUID U_SPEED = UUID.fromString("5b1f0c3a-0000-4000-8000-0000000000a3");
	private static final UUID U_ATKSPD = UUID.fromString("5b1f0c3a-0000-4000-8000-0000000000a4");
	private static final UUID U_KB = UUID.fromString("5b1f0c3a-0000-4000-8000-0000000000a5");
	private static final UUID U_LUCK = UUID.fromString("5b1f0c3a-0000-4000-8000-0000000000a6");
	private static final UUID U_BREAK = UUID.fromString("5b1f0c3a-0000-4000-8000-0000000000a7");
	private static final UUID U_CORR = UUID.fromString("5b1f0c3a-0000-4000-8000-0000000000a8");

	// ---- caps (see design doc) ----
	private static final float CAP_DAMAGE_BONUS = 0.50f;
	private static final float CAP_DAMAGE_REDUCTION = 0.40f;
	private static final float CAP_LIFESTEAL = 0.12f;
	private static final double CAP_SPEED = 0.25;
	private static final double CAP_ATTACK_SPEED = 0.25;
	private static final double CAP_ARMOR = 8.0;
	private static final double CAP_MAX_HP = 4.0;

	private static boolean inReflect = false;

	private PassiveEngine() {
	}

	// ------------------------------------------------------------------ setup

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(PassiveEngine::serverTick);
		ServerLivingEntityEvents.AFTER_DEATH.register(PassiveEngine::afterDeath);
		PlayerBlockBreakEvents.AFTER.register(PassiveEngine::afterBlockBreak);
	}

	public static PlayerRes res(UUID id) {
		return RES.computeIfAbsent(id, k -> new PlayerRes());
	}

	public static void setOwned(ServerPlayerEntity p, Set<String> owned) {
		PlayerRes r = res(p.getUuid());
		r.owned = Set.copyOf(owned);
	}

	/** Same as setOwned(player, owned) but only needs the id (used when the skill list changes). */
	public static void setOwned(UUID id, Set<String> owned) {
		res(id).owned = Set.copyOf(owned);
	}

	public static void forget(UUID id) {
		RES.remove(id);
	}

	/** Called when a player leaves. */
	public static void clear(UUID id) {
		forget(id);
	}

	/** Key presses sent by the client: 0 = Blood Conversion, 1 = Mana Shield on/off. */
	public static void action(ServerPlayerEntity p, int id) {
		PlayerRes r = res(p.getUuid());
		long now = nowOf(p);
		if (id == 0) {
			if (!has(r, "blood_conversion")) {
				return;
			}
			if (now < r.bloodIcd || p.getHealth() <= 6f) {
				p.sendMessage(Text.translatable("orvwindow.msg.blood_conversion_fail"), true);
				return;
			}
			p.setHealth(p.getHealth() - 2f);
			r.mana = Math.min(maxMana(r), r.mana + 20f);
			r.bloodIcd = now + 200;
		} else if (id == 1) {
			if (!has(r, "mana_shield")) {
				return;
			}
			r.shieldOn = !r.shieldOn;
			p.sendMessage(Text.translatable(r.shieldOn ? "orvwindow.msg.manashield_on" : "orvwindow.msg.manashield_off"), true);
		}
	}

	private static boolean has(PlayerRes r, String id) {
		return r.owned.contains(id);
	}

	private static long nowOf(Entity e) {
		MinecraftServer s = e.getServer();
		return s == null ? 0L : s.getTicks();
	}

	private static boolean isBoss(LivingEntity e) {
		return e.getMaxHealth() >= 100f;
	}

	private static float maxMana(PlayerRes r) {
		return 100f + (has(r, "mana_pool") ? 20f : 0f);
	}

	private static float maxStamina(PlayerRes r) {
		return 100f + (has(r, "stamina_mastery") ? 20f : 0f);
	}

	// ------------------------------------------------------------------ attack strength

	public static void beforeAttackReset(LivingEntity e) {
		if (e.getWorld().isClient || !(e instanceof ServerPlayerEntity p)) {
			return;
		}
		PlayerRes r = res(p.getUuid());
		r.lastStrength = p.getAttackCooldownProgress(0.5f);
		r.lastStrengthTick = nowOf(p);
	}

	// ------------------------------------------------------------------ dodge / deflect

	public static boolean shouldNegate(LivingEntity victim, DamageSource source) {
		if (victim.getWorld().isClient || !(victim instanceof ServerPlayerEntity p)) {
			return false;
		}
		PlayerRes r = res(p.getUuid());
		if (r.owned.isEmpty()) {
			return false;
		}
		long now = nowOf(p);
		boolean projectile = source.isIn(DamageTypeTags.IS_PROJECTILE);
		boolean attackHit = source.getAttacker() != null && (source.getSource() == source.getAttacker() || projectile);
		if (projectile && has(r, "deflection") && now >= r.deflectIcd && RAND.nextFloat() < 0.15f) {
			r.deflectIcd = now + 40;
			return true;
		}
		if (attackHit && has(r, "evasive_instinct") && now >= r.dodgeIcd && RAND.nextFloat() < 0.06f) {
			r.dodgeIcd = now + 30;
			return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ damage

	public static float modifyDamage(LivingEntity victim, DamageSource source, float amount) {
		if (victim.getWorld().isClient || amount <= 0f || inReflect) {
			return amount;
		}
		long now = nowOf(victim);
		float out = amount;
		Entity a = source.getAttacker();
		if (a instanceof ServerPlayerEntity atk) {
			out = attackerBonus(atk, victim, source, out, now);
		}
		if (victim instanceof ServerPlayerEntity vp) {
			out = defenderReduction(vp, source, out, now);
		}
		return out;
	}

	private static float attackerBonus(ServerPlayerEntity atk, LivingEntity victim, DamageSource source, float amount, long now) {
		PlayerRes r = res(atk.getUuid());
		if (r.owned.isEmpty()) {
			return amount;
		}
		boolean melee = source.getSource() == atk;
		boolean ranged = source.getSource() instanceof PersistentProjectileEntity;
		float strength = (melee && now - r.lastStrengthTick <= 1) ? r.lastStrength : 1f;
		boolean heavy = melee && strength >= 0.9f;

		boolean vanillaCrit = melee && strength > 0.9f && atk.fallDistance > 0f && !atk.isOnGround()
				&& !atk.isClimbing() && !atk.isTouchingWater() && !atk.hasStatusEffect(StatusEffects.BLINDNESS)
				&& !atk.hasVehicle() && !atk.isSprinting();

		float out = amount;

		// critical hits
		boolean crit = vanillaCrit;
		if (melee && !vanillaCrit && has(r, "critical_instinct") && RAND.nextFloat() < 0.07f) {
			crit = true;
			out *= 1.5f;
		}
		if (crit && has(r, "fatal_blow")) {
			out *= 1.7f / 1.5f;
		}

		// additive "+%" pool (capped)
		float bonus = 0f;
		if (melee && has(r, "power_strike")) {
			bonus += 0.06f;
		}
		if (melee && has(r, "weapon_mastery")) {
			bonus += 0.04f;
		}
		if (heavy && has(r, "heavy_impact")) {
			bonus += 0.12f;
		}
		float hp = victim.getHealth();
		float maxHp = victim.getMaxHealth();
		if (has(r, "executioner") && hp <= 0.30f * maxHp) {
			bonus += 0.15f;
		}
		if (melee && has(r, "backstabber")) {
			Vec3d look = victim.getRotationVec(1f);
			Vec3d to = atk.getPos().subtract(victim.getPos());
			double lx = look.x, lz = look.z;
			double tx = to.x, tz = to.z;
			double ll = Math.sqrt(lx * lx + lz * lz);
			double tl = Math.sqrt(tx * tx + tz * tz);
			if (ll > 1e-4 && tl > 1e-4 && (lx * tx + lz * tz) / (ll * tl) <= -0.5) {
				bonus += 0.15f;
			}
		}
		Long lastOnTarget = r.firstBlood.get(victim.getUuid());
		boolean freshTarget = lastOnTarget == null || now - lastOnTarget > 300;
		if (freshTarget && has(r, "first_blood")) {
			bonus += 0.25f;
		}
		if (freshTarget && has(r, "ambusher") && victim instanceof MobEntity m && m.getTarget() == null) {
			bonus += 0.20f;
		}
		if (melee && has(r, "combo_master") && now - r.lastHitTick <= 40) {
			bonus += 0.03f * Math.min(5, r.combo);
		}
		if (has(r, "berserker")) {
			float missingPct = (1f - hp / Math.max(1f, maxHp)) * 100f;
			bonus += Math.min(0.14f, 0.002f * missingPct);
		}
		if (has(r, "bloodlust") && now < r.bloodlustUntil) {
			bonus += 0.06f * r.bloodlustStacks;
		}
		if (has(r, "ruthless")) {
			int ailments = 0;
			for (StatusEffectInstance e : victim.getStatusEffects()) {
				if (e.getEffectType().getCategory() == StatusEffectCategory.HARMFUL) {
					ailments++;
				}
			}
			if (victim.isOnFire()) {
				ailments++;
			}
			bonus += 0.08f * Math.min(2, ailments);
		}
		if (has(r, "dark_embrace") && r.dark) {
			bonus += 0.08f;
		}
		if (has(r, "void_resonance")) {
			for (StatusEffectInstance e : victim.getStatusEffects()) {
				if (e.getEffectType().getCategory() == StatusEffectCategory.BENEFICIAL) {
					bonus += 0.12f;
					break;
				}
			}
		}
		if (has(r, "night_predator") && r.night) {
			bonus += 0.08f;
		}
		if (has(r, "predator") && victim.getUuid().equals(r.markedTarget) && now < r.markedUntil) {
			bonus += 0.12f;
		}
		if (has(r, "monster_hunter") && victim instanceof Monster) {
			bonus += 0.08f;
		}
		if (has(r, "beast_slayer") && isBeast(victim)) {
			bonus += 0.15f;
		}
		if (has(r, "holy_light") && victim.getGroup() == EntityGroup.UNDEAD) {
			bonus += 0.20f;
		}
		if (ranged && has(r, "sniper_instinct") && atk.distanceTo(victim) > 20f) {
			bonus += 0.12f;
		}
		if (has(r, "battle_focus") && now < r.focusUntil) {
			bonus += 0.02f * r.focus;
		}
		if (melee && r.overchargeReady) {
			bonus += 0.30f;
			r.overchargeReady = false;
			r.overchargeIcd = now + 200;
		}
		out *= 1f + Math.min(CAP_DAMAGE_BONUS, bonus);

		// ignore part of the armor
		if (has(r, "piercing_force") && !source.isIn(DamageTypeTags.BYPASSES_ARMOR)) {
			float armor = (float) victim.getArmor();
			float tough = (float) victim.getAttributeValue(EntityAttributes.GENERIC_ARMOR_TOUGHNESS);
			float left = DamageUtil.getDamageLeft(out, armor, tough);
			if (left > 0.01f && out > left) {
				float extra = 0.10f * (out - left) * out / left;
				out += Math.min(extra, out * 0.25f);
			}
		}
		return out;
	}

	private static boolean isBeast(LivingEntity e) {
		EntityType<?> t = e.getType();
		return t == EntityType.SPIDER || t == EntityType.CAVE_SPIDER || t == EntityType.HOGLIN
				|| t == EntityType.ZOGLIN || t == EntityType.RAVAGER || t == EntityType.POLAR_BEAR
				|| t == EntityType.PHANTOM;
	}

	private static String elementOf(DamageSource s) {
		if (s.isIn(DamageTypeTags.IS_FIRE)) {
			return "fire";
		}
		if (s.isIn(DamageTypeTags.IS_FREEZING)) {
			return "freeze";
		}
		if (s.isIn(DamageTypeTags.IS_LIGHTNING)) {
			return "lightning";
		}
		if (s.isOf(DamageTypes.WITHER)) {
			return "wither";
		}
		return null;
	}

	private static float defenderReduction(ServerPlayerEntity vp, DamageSource source, float amount, long now) {
		PlayerRes r = res(vp.getUuid());
		if (r.owned.isEmpty()) {
			return amount;
		}
		float hpFrac = vp.getHealth() / Math.max(1f, vp.getMaxHealth());
		boolean fall = source.isIn(DamageTypeTags.IS_FALL);
		boolean magic = source.isOf(DamageTypes.MAGIC) || source.isOf(DamageTypes.INDIRECT_MAGIC);
		String element = elementOf(source);

		float red = 0f;
		if (has(r, "thick_skin")) {
			red += 0.03f;
		}
		if (has(r, "last_stand") && hpFrac <= 0.30f) {
			red += 0.08f;
		}
		if (has(r, "fortress") && r.stillTicks >= 30) {
			red += 0.10f;
		}
		if (has(r, "pain_suppression") && now < r.painUntil) {
			red += 0.15f;
		}
		if (has(r, "unyielding") && source.getAttacker() != null && source.getAttacker().getUuid().equals(r.lastAttackerId)
				&& now - r.unyieldTick <= 100) {
			red += 0.05f * r.unyieldStacks;
		}
		if (magic && has(r, "magic_barrier")) {
			red += 0.20f;
		}
		if (element != null && has(r, "elemental_resistance")) {
			red += 0.10f;
		}
		if ("fire".equals(element) && has(r, "ashen_skin")) {
			red += 0.20f;
		}
		if (element != null && has(r, "adaptation") && element.equals(r.lastElement) && now < r.adaptUntil) {
			red += 0.08f * r.adaptStacks;
		}
		if (fall) {
			if (has(r, "lightweight")) {
				red += 0.20f;
			}
			if (has(r, "gravity_defier")) {
				red += 0.15f;
			}
		}
		float out = amount * (1f - Math.min(CAP_DAMAGE_REDUCTION, red));

		// Mana Shield: 20% of the damage is paid with Mana (1 Mana = 0.5 HP)
		if (r.shieldOn && has(r, "mana_shield") && r.mana > 0f) {
			float absorb = out * 0.20f;
			float need = absorb * 2f;
			if (need > r.mana) {
				need = r.mana;
				absorb = need / 2f;
			}
			r.mana -= need;
			out -= absorb;
		}

		// lethal hits
		boolean bypass = source.isIn(DamageTypeTags.BYPASSES_ARMOR);
		float pool = vp.getHealth() + vp.getAbsorptionAmount();
		float armor = (float) vp.getArmor();
		float tough = (float) vp.getAttributeValue(EntityAttributes.GENERIC_ARMOR_TOUGHNESS);
		float dealt = bypass ? out : DamageUtil.getDamageLeft(out, armor, tough);
		if (dealt >= pool) {
			if (has(r, "death_ward") && now >= r.deathWardIcd) {
				out *= 0.8f;
				r.deathWardIcd = now + 600;
				dealt = bypass ? out : DamageUtil.getDamageLeft(out, armor, tough);
			}
			if (dealt >= pool && has(r, "second_chance") && now >= r.secondChanceIcd && RAND.nextFloat() < 0.20f) {
				r.secondChanceIcd = now + 6000;
				vp.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 40, 4, false, true, true));
				if (vp.getHealth() < 2f) {
					vp.setHealth(2f);
				}
				return 0f;
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ after a hit

	public static void afterDamage(LivingEntity victim, DamageSource source, float amount) {
		if (victim.getWorld().isClient) {
			return;
		}
		long now = nowOf(victim);
		Entity a = source.getAttacker();

		if (victim instanceof ServerPlayerEntity vp) {
			PlayerRes r = res(vp.getUuid());
			r.lastDamagedTick = now;
			r.moveTicks = 0;
			if (has(r, "pain_suppression") && amount >= 4f && now >= r.painIcd) {
				r.painUntil = now + 60;
				r.painIcd = now + 160;
			}
			if (a != null) {
				UUID id = a.getUuid();
				if (id.equals(r.lastAttackerId) && now - r.unyieldTick <= 100) {
					r.unyieldStacks = Math.min(4, r.unyieldStacks + 1);
				} else {
					r.unyieldStacks = 1;
				}
				r.lastAttackerId = id;
				r.unyieldTick = now;
			}
			String el = elementOf(source);
			if (el != null) {
				if (el.equals(r.lastElement) && now < r.adaptUntil) {
					r.adaptStacks = Math.min(3, r.adaptStacks + 1);
				} else {
					r.adaptStacks = 1;
				}
				r.lastElement = el;
				r.adaptUntil = now + 200;
			}
			if (has(r, "reflective_armor") && a instanceof LivingEntity attacker && source.getSource() == a && !inReflect) {
				float back = Math.min(4f, 0.12f * amount);
				if (back > 0.05f) {
					inReflect = true;
					try {
						attacker.damage(vp.getDamageSources().thorns(vp), back);
					} finally {
						inReflect = false;
					}
				}
			}
		}

		if (a instanceof ServerPlayerEntity atk) {
			PlayerRes r = res(atk.getUuid());
			if (r.owned.isEmpty()) {
				return;
			}
			boolean melee = source.getSource() == atk;
			if (melee) {
				if (now - r.lastHitTick <= 40) {
					r.combo++;
				} else {
					r.combo = 1;
				}
				r.lastHitTick = now;
				r.lastAttackTick = now;
				if (has(r, "adrenaline_rush") && r.combo >= 8 && now >= r.adrenalineIcd) {
					r.adrenalineUntil = now + 100;
					r.adrenalineIcd = now + 300;
				}
				if (has(r, "burning_blood") && RAND.nextFloat() < 0.15f) {
					victim.setOnFireFor(3);
				}
				if (has(r, "toxic_touch") && RAND.nextFloat() < 0.15f) {
					victim.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 80, 0), atk);
				}
				if (has(r, "armor_breaker") && RAND.nextFloat() < 0.12f) {
					applyArmorBreak(victim, now);
				}
				if (has(r, "corrosion") && victim.hasStatusEffect(StatusEffects.POISON)) {
					applyCorrosion(victim, now);
				}
			}
			if (has(r, "battle_focus")) {
				r.focus = (now < r.focusUntil) ? Math.min(5, r.focus + 1) : 1;
				r.focusUntil = now + 120;
			}
			r.firstBlood.put(victim.getUuid(), now);
			if (r.firstBlood.size() > 64) {
				r.firstBlood.values().removeIf(t -> now - t > 300);
			}
			if (has(r, "marked_prey") && (r.markedTarget == null || now >= r.markedUntil)) {
				r.markedTarget = victim.getUuid();
				r.markedUntil = now + 200;
				victim.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 200, 0, false, false, false));
			}

			// life steal
			if (victim.getGroup() != EntityGroup.UNDEAD) {
				float pct = 0f;
				if (has(r, "vampirism")) {
					pct += 0.04f;
				}
				if (has(r, "soul_drain") && hasHarmful(victim)) {
					pct += 0.05f;
				}
				if (has(r, "blood_pact") && atk.getHealth() <= 0.40f * atk.getMaxHealth()) {
					pct += 0.04f;
				}
				pct = Math.min(CAP_LIFESTEAL, pct);
				if (pct > 0f) {
					atk.heal(amount * pct);
				}
			}
		}
	}

	private static boolean hasHarmful(LivingEntity e) {
		for (StatusEffectInstance i : e.getStatusEffects()) {
			if (i.getEffectType().getCategory() == StatusEffectCategory.HARMFUL) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ armor break

	private static final class Break {
		int stacks;
		long until;
	}

	private static final Map<LivingEntity, Break> BREAKS = new HashMap<>();

	private static void applyArmorBreak(LivingEntity victim, long now) {
		EntityAttributeInstance inst = victim.getAttributeInstance(EntityAttributes.GENERIC_ARMOR);
		if (inst == null) {
			return;
		}
		Break b = BREAKS.computeIfAbsent(victim, k -> new Break());
		int max = isBoss(victim) ? 1 : 3;
		b.stacks = now < b.until ? Math.min(max, b.stacks + 1) : 1;
		b.until = now + 120;
		inst.removeModifier(U_BREAK);
		inst.addTemporaryModifier(new EntityAttributeModifier(U_BREAK, "orv_armor_break", -2.0 * b.stacks,
				EntityAttributeModifier.Operation.ADDITION));
	}

	private static final Map<LivingEntity, Long> CORR = new HashMap<>();

	private static void applyCorrosion(LivingEntity victim, long now) {
		EntityAttributeInstance inst = victim.getAttributeInstance(EntityAttributes.GENERIC_ARMOR);
		if (inst == null) {
			return;
		}
		inst.removeModifier(U_CORR);
		inst.addTemporaryModifier(new EntityAttributeModifier(U_CORR, "orv_corrosion", isBoss(victim) ? -1.0 : -3.0,
				EntityAttributeModifier.Operation.ADDITION));
		CORR.put(victim, now + 120);
	}

	private static void tickBreaks(long now) {
		Iterator<Map.Entry<LivingEntity, Long>> ci = CORR.entrySet().iterator();
		while (ci.hasNext()) {
			Map.Entry<LivingEntity, Long> e = ci.next();
			if (e.getKey().isRemoved() || now >= e.getValue()) {
				EntityAttributeInstance inst = e.getKey().getAttributeInstance(EntityAttributes.GENERIC_ARMOR);
				if (inst != null) {
					inst.removeModifier(U_CORR);
				}
				ci.remove();
			}
		}
		Iterator<Map.Entry<LivingEntity, Break>> it = BREAKS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<LivingEntity, Break> e = it.next();
			LivingEntity ent = e.getKey();
			if (ent.isRemoved() || now >= e.getValue().until) {
				EntityAttributeInstance inst = ent.getAttributeInstance(EntityAttributes.GENERIC_ARMOR);
				if (inst != null) {
					inst.removeModifier(U_BREAK);
				}
				it.remove();
			}
		}
	}

	// ------------------------------------------------------------------ healing

	public static float modifyHeal(LivingEntity e, float amount) {
		if (e.getWorld().isClient || !(e instanceof ServerPlayerEntity p) || amount <= 0f) {
			return amount;
		}
		PlayerRes r = res(p.getUuid());
		if (r.owned.isEmpty()) {
			return amount;
		}
		long now = nowOf(p);
		float hpFrac = p.getHealth() / Math.max(1f, p.getMaxHealth());
		float mult = 1f;
		if (has(r, "natures_blessing")) {
			mult += 0.15f;
		}
		if (has(r, "desperate_recovery") && hpFrac <= 0.30f) {
			mult += 0.30f;
		}
		float out = amount * mult;
		if (has(r, "life_bond")) {
			out += Math.max(0.5f, 0.01f * p.getMaxHealth());
		}
		if (has(r, "overheal")) {
			float over = p.getHealth() + out - p.getMaxHealth();
			if (over > 0f) {
				float cur = p.getAbsorptionAmount();
				float add = Math.min(4f - cur, over * 0.5f);
				if (add > 0f) {
					p.setAbsorptionAmount(cur + add);
					r.overhealAbs += add;
					r.overhealUntil = now + 400;
				}
			}
		}
		if (has(r, "purification") && out >= 2f && now >= r.purifyIcd && RAND.nextFloat() < 0.25f) {
			for (StatusEffectInstance i : new ArrayList<>(p.getStatusEffects())) {
				if (i.getEffectType().getCategory() == StatusEffectCategory.HARMFUL) {
					p.removeStatusEffect(i.getEffectType());
					r.purifyIcd = now + 160;
					break;
				}
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ kills

	private static void afterDeath(LivingEntity entity, DamageSource source) {
		if (entity.getWorld().isClient) {
			return;
		}
		if (entity instanceof ServerPlayerEntity dead) {
			PlayerRes r = res(dead.getUuid());
			r.souls /= 2;
		}
		Entity a = source.getAttacker();
		if (!(a instanceof ServerPlayerEntity p)) {
			return;
		}
		PlayerRes r = res(p.getUuid());
		if (r.owned.isEmpty()) {
			return;
		}
		long now = nowOf(p);
		boolean boss = isBoss(entity);
		if (has(r, "bloodlust")) {
			r.bloodlustStacks = now < r.bloodlustUntil ? Math.min(3, r.bloodlustStacks + 1) : 1;
			r.bloodlustUntil = now + 160;
		}
		if (has(r, "blood_harvest")) {
			p.heal(boss ? 4f : 1f);
		}
		if (has(r, "energy_absorption")) {
			r.mana = Math.min(maxMana(r), r.mana + 5f);
			r.stamina = Math.min(maxStamina(r), r.stamina + 8f);
		}
		if (has(r, "soul_collector") && entity instanceof Monster) {
			r.souls = Math.min(20, r.souls + (boss ? 5 : 1));
		}
		if (has(r, "killing_rhythm")) {
			r.rhythmStacks = (now - r.lastKillTick <= 120) ? Math.min(4, r.rhythmStacks + 1) : 1;
			r.rhythmUntil = now + 120;
			r.lastKillTick = now;
		}
	}

	// ------------------------------------------------------------------ extra drops

	private static void afterBlockBreak(World world, PlayerEntity player, BlockPos pos, BlockState state, BlockEntity be) {
		if (world.isClient || be != null || !(player instanceof ServerPlayerEntity sp) || sp.isCreative()
				|| !(world instanceof ServerWorld sw)) {
			return;
		}
		PlayerRes r = res(sp.getUuid());
		if (r.owned.isEmpty()) {
			return;
		}
		boolean extra = false;
		if (has(r, "forager") && (state.getBlock() instanceof PlantBlock || state.getBlock() instanceof SweetBerryBushBlock)
				&& RAND.nextFloat() < 0.20f) {
			extra = true;
		}
		if (!extra && has(r, "resourceful") && RAND.nextFloat() < 0.08f) {
			extra = true;
		}
		if (!extra) {
			return;
		}
		List<ItemStack> drops = Block.getDroppedStacks(state, sw, pos, null, sp, sp.getMainHandStack());
		if (drops.isEmpty()) {
			return;
		}
		ItemStack first = drops.get(0);
		// blocks that drop themselves are skipped (place/break would duplicate items)
		if (first.getItem() == state.getBlock().asItem()) {
			return;
		}
		ItemStack one = first.copy();
		one.setCount(1);
		Block.dropStack(sw, pos, one);
	}

	// ------------------------------------------------------------------ per-tick work

	private static void serverTick(MinecraftServer server) {
		long now = server.getTicks();
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			if (p.isAlive()) {
				tickPlayer(server, p, now);
			}
		}
		tickBreaks(now);
	}

	private static void tickPlayer(MinecraftServer server, ServerPlayerEntity p, long now) {
		PlayerRes r = res(p.getUuid());
		Set<String> o = r.owned;
		float maxMana = maxMana(r);
		float maxSta = maxStamina(r);
		float hpFrac = p.getHealth() / Math.max(1f, p.getMaxHealth());

		// ---- movement tracking
		Vec3d pos = p.getPos();
		if (r.lastPos != null) {
			double dx = pos.x - r.lastPos.x;
			double dz = pos.z - r.lastPos.z;
			double h = Math.sqrt(dx * dx + dz * dz);
			if (h > 0.03) {
				r.moveTicks++;
				r.stillTicks = 0;
			} else {
				r.stillTicks++;
				if (r.stillTicks > 10) {
					r.moveTicks = 0;
				}
			}
		}
		r.lastPos = pos;

		// ---- environment (cheap, every 5 ticks)
		if (now % 5 == 0) {
			World w = p.getWorld();
			r.dark = w.getLightLevel(p.getBlockPos()) <= 4;
			long t = w.getTimeOfDay() % 24000L;
			r.night = t >= 13000L && t <= 23000L;
			if (has(r, "hunters_pursuit")) {
				HostileEntity nearest = null;
				double best = Double.MAX_VALUE;
				Box box = p.getBoundingBox().expand(12.0);
				for (HostileEntity h : w.getEntitiesByClass(HostileEntity.class, box, e -> e.isAlive())) {
					double d = h.squaredDistanceTo(p);
					if (d < best) {
						best = d;
						nearest = h;
					}
				}
				double dist = nearest == null ? -1 : Math.sqrt(best);
				r.pursuing = nearest != null && r.lastEnemyDist >= 0 && dist < r.lastEnemyDist - 0.05
						&& r.moveTicks > 0 && p.isSprinting();
				r.lastEnemyDist = dist;
			} else {
				r.pursuing = false;
			}
		}

		// ---- stamina
		if (p.isCreative() || p.isSpectator()) {
			r.stamina = maxSta;
			r.exhausted = false;
		} else {
			if (p.isSprinting()) {
				r.stamina -= (6f / 20f) * (has(r, "fleet_footed") ? 0.75f : 1f);
				r.lastSprintTick = now;
			} else if (now - r.lastSprintTick > 20) {
				r.stamina += (12f / 20f) * (has(r, "swift_recovery") ? 1.25f : 1f);
			}
			if (has(r, "second_wind") && r.secondWindLeft == 0 && r.stamina <= 0.2f * maxSta && now >= r.secondWindIcd) {
				r.secondWindLeft = 40;
				r.secondWindIcd = now + 900;
			}
			if (r.secondWindLeft > 0) {
				r.stamina += 0.5f;
				r.secondWindLeft--;
			}
			if (r.stamina <= 0f) {
				r.stamina = 0f;
				r.exhausted = true;
			}
			if (r.exhausted && r.stamina >= 20f) {
				r.exhausted = false;
			}
			if (r.exhausted && p.isSprinting()) {
				p.setSprinting(false);
			}
		}
		r.stamina = Math.max(0f, Math.min(maxSta, r.stamina));

		// ---- mana
		float manaMult = 1f;
		if (has(r, "mana_flow")) {
			manaMult += 0.25f;
		}
		if (has(r, "arcane_recovery") && now - r.lastDamagedTick > 100) {
			manaMult += 0.5f;
		}
		r.mana = Math.min(maxMana, r.mana + (1f / 20f) * manaMult);

		// ---- overcharge readiness
		r.overchargeReady = r.overchargeReady
				|| (has(r, "overcharge") && now >= r.overchargeIcd
				&& r.mana >= maxMana - 0.01f && r.stamina >= maxSta - 0.01f);
		if (!has(r, "overcharge")) {
			r.overchargeReady = false;
		}

		// ---- slow heals
		long since = now - r.lastDamagedTick;
		if (has(r, "regeneration") && since >= 100 && now % 160 == 0) {
			p.heal(1f);
		}
		if (has(r, "rejuvenation") && since >= 200 && now % 80 == 0) {
			p.heal(1f);
		}
		if (has(r, "natural_healing") && now % 240 == 0) {
			p.heal(1f);
		}
		if (has(r, "guardian_spirit") && hpFrac <= 0.25f && now >= r.guardianIcd) {
			p.setAbsorptionAmount(Math.max(p.getAbsorptionAmount(), 4f));
			r.guardianIcd = now + 2400;
		}
		if (r.overhealAbs > 0f && now >= r.overhealUntil) {
			p.setAbsorptionAmount(Math.max(0f, p.getAbsorptionAmount() - r.overhealAbs));
			r.overhealAbs = 0f;
		}

		// ---- survival
		if (has(r, "tenacity")) {
			applyTenacity(p, r);
		}
		if (has(r, "survivor")) {
			HungerManager hm = p.getHungerManager();
			float ex = hm.getExhaustion();
			if (r.lastExhaustion >= 0f && ex > r.lastExhaustion) {
				hm.addExhaustion(-0.15f * (ex - r.lastExhaustion));
			}
			if (hm.getExhaustion() < 0f) {
				hm.addExhaustion(-hm.getExhaustion());
			}
			r.lastExhaustion = hm.getExhaustion();
		}
		if (has(r, "cold_resistance")) {
			int fz = p.getFrozenTicks();
			if (r.lastFrozen >= 0 && fz > r.lastFrozen) {
				r.frozenAcc += (fz - r.lastFrozen) * 0.4f;
				int cut = (int) r.frozenAcc;
				r.frozenAcc -= cut;
				if (cut > 0) {
					p.setFrozenTicks(Math.max(0, fz - cut));
				}
			}
			r.lastFrozen = p.getFrozenTicks();
		}
		if (has(r, "deep_diver") && p.isSubmergedInWater() && now % 3 == 0 && p.getAir() < p.getMaxAir()) {
			p.setAir(p.getAir() + 1);
		}

		// ---- attributes (every 5 ticks)
		if (now % 5 == 0) {
			applyAttributes(p, r, now, hpFrac);
		}

		// ---- sync to client
		syncResources(p, r, now, maxMana, maxSta);
	}

	private static void applyTenacity(ServerPlayerEntity p, PlayerRes r) {
		r.lastEffDur.keySet().removeIf(t -> !p.hasStatusEffect(t));
		for (StatusEffectInstance inst : new ArrayList<>(p.getStatusEffects())) {
			StatusEffect t = inst.getEffectType();
			if (t.getCategory() != StatusEffectCategory.HARMFUL) {
				continue;
			}
			int d = inst.getDuration();
			if (d < 0) {
				continue;
			}
			Integer last = r.lastEffDur.get(t);
			if ((last == null || d > last) && d > 2) {
				int nd = Math.max(1, (int) (d * 0.75f));
				p.removeStatusEffect(t);
				p.addStatusEffect(new StatusEffectInstance(t, nd, inst.getAmplifier(), inst.isAmbient(),
						inst.shouldShowParticles(), inst.shouldShowIcon()));
				r.lastEffDur.put(t, nd);
			} else {
				r.lastEffDur.put(t, d);
			}
		}
	}

	private static void applyAttributes(ServerPlayerEntity p, PlayerRes r, long now, float hpFrac) {
		if (r.attrOwner != p) {
			r.attrOwner = p;
			r.lastAttr.clear();
		}
		double armor = 0;
		if (has(r, "iron_body")) {
			armor += 2;
		}
		if (has(r, "earth_heart")) {
			armor += 1;
		}
		if (has(r, "last_stand") && hpFrac <= 0.30f) {
			armor += 4;
		}
		if (has(r, "dark_embrace") && r.dark) {
			armor += 1;
		}
		if (has(r, "vitality_surge") && hpFrac >= 0.95f) {
			armor += 3;
		}
		armor = Math.min(CAP_ARMOR, armor);

		double maxHp = has(r, "toughness") ? Math.min(CAP_MAX_HP, 2.0) : 0.0;

		double speed = 0;
		if (has(r, "swift_feet")) {
			speed += 0.06;
		}
		if (has(r, "wind_soul")) {
			speed += 0.05;
		}
		if (has(r, "shadow_walker") && r.dark) {
			speed += 0.10;
		}
		if (has(r, "escape_artist") && hpFrac <= 0.30f) {
			speed += 0.15;
		}
		if (has(r, "momentum")) {
			speed += Math.min(0.12, 0.015 * (r.moveTicks / 20.0));
		}
		if (has(r, "hunters_pursuit") && r.pursuing) {
			speed += 0.10;
		}
		speed = Math.min(CAP_SPEED, speed);

		double atkSpeed = 0;
		if (now < r.adrenalineUntil) {
			atkSpeed += 0.20;
		}
		if (has(r, "killing_rhythm") && now < r.rhythmUntil) {
			atkSpeed += 0.04 * r.rhythmStacks;
		}
		if (has(r, "night_predator") && r.night) {
			atkSpeed += 0.05;
		}
		if (has(r, "dual_wielder") && isWeapon(p.getMainHandStack()) && isWeapon(p.getOffHandStack())) {
			atkSpeed += 0.10;
		}
		atkSpeed = Math.min(CAP_ATTACK_SPEED, atkSpeed);

		double kb = 0;
		if (has(r, "steadfast")) {
			kb += 0.30;
		}
		if (has(r, "earth_heart")) {
			kb += 0.10;
		}
		if (has(r, "unstoppable") && now - r.lastAttackTick <= 20) {
			kb += 0.60;
		}
		kb = Math.min(1.0, kb);

		double luck = has(r, "lucky_finder") ? 1.0 : 0.0;

		setMod(p, r, EntityAttributes.GENERIC_ARMOR, U_ARMOR, "orv_armor", armor, EntityAttributeModifier.Operation.ADDITION);
		setMod(p, r, EntityAttributes.GENERIC_MAX_HEALTH, U_HP, "orv_hp", maxHp, EntityAttributeModifier.Operation.ADDITION);
		setMod(p, r, EntityAttributes.GENERIC_MOVEMENT_SPEED, U_SPEED, "orv_speed", speed, EntityAttributeModifier.Operation.MULTIPLY_TOTAL);
		setMod(p, r, EntityAttributes.GENERIC_ATTACK_SPEED, U_ATKSPD, "orv_atkspd", atkSpeed, EntityAttributeModifier.Operation.MULTIPLY_TOTAL);
		setMod(p, r, EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, U_KB, "orv_kb", kb, EntityAttributeModifier.Operation.ADDITION);
		setMod(p, r, EntityAttributes.GENERIC_LUCK, U_LUCK, "orv_luck", luck, EntityAttributeModifier.Operation.ADDITION);

		if (p.getHealth() > p.getMaxHealth()) {
			p.setHealth(p.getMaxHealth());
		}
	}

	private static boolean isWeapon(ItemStack s) {
		return s.getItem() instanceof SwordItem || s.getItem() instanceof AxeItem;
	}

	private static void setMod(ServerPlayerEntity p, PlayerRes r, EntityAttribute attr, UUID id, String name,
							   double value, EntityAttributeModifier.Operation op) {
		Double prev = r.lastAttr.get(name);
		if (prev != null && Math.abs(prev - value) < 1e-6) {
			return;
		}
		EntityAttributeInstance inst = p.getAttributeInstance(attr);
		if (inst == null) {
			return;
		}
		inst.removeModifier(id);
		if (value != 0.0) {
			inst.addTemporaryModifier(new EntityAttributeModifier(id, name, value, op));
		}
		r.lastAttr.put(name, value);
	}

	// ------------------------------------------------------------------ client sync

	private static void syncResources(ServerPlayerEntity p, PlayerRes r, long now, float maxMana, float maxSta) {
		boolean changed = Math.abs(r.mana - r.sentMana) >= 0.5f || Math.abs(r.stamina - r.sentStamina) >= 0.5f
				|| maxMana != r.sentMaxMana || maxSta != r.sentMaxStamina || r.exhausted != r.sentExhausted;
		if (!changed && now - r.sentAt < 100) {
			return;
		}
		if (changed && now - r.sentAt < 4) {
			return;
		}
		if (!ServerPlayNetworking.canSend(p, OrvWindowMod.VITALS)) {
			return;
		}
		PacketByteBuf out = PacketByteBufs.create();
		out.writeFloat(r.mana);
		out.writeFloat(maxMana);
		out.writeFloat(r.stamina);
		out.writeFloat(maxSta);
		out.writeBoolean(r.exhausted);
		ServerPlayNetworking.send(p, OrvWindowMod.VITALS, out);
		r.sentMana = r.mana;
		r.sentStamina = r.stamina;
		r.sentMaxMana = maxMana;
		r.sentMaxStamina = maxSta;
		r.sentExhausted = r.exhausted;
		r.sentAt = now;
	}
}
