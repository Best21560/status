package com.orv.window;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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
import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.SwordItem;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The actual effects of the passives (server side).
 * Numbers follow the design document. Passives that are not listed in Passives.ACTIVE / PARTIAL are not implemented yet.
 */
public final class PassiveEffects {
	private static final Map<UUID, PlayerRuntime> RT = new ConcurrentHashMap<>();

	// armor changes on enemies (Armor Breaker / Corrosion)
	private static final class Brk {
		LivingEntity target;
		int stacks;
		int until;
	}

	private static final Map<UUID, Brk> BREAKS = new HashMap<>();
	private static final Map<UUID, Brk> CORRS = new HashMap<>();

	/** Set while the mod itself deals damage, so that it is not modified again. */
	private static boolean internal = false;

	// fixed attribute modifier ids
	private static final UUID U_ARMOR = UUID.fromString("5b9d7c3e-1a52-4c0e-9a41-0a0a00000001");
	private static final UUID U_HP = UUID.fromString("5b9d7c3e-1a52-4c0e-9a41-0a0a00000002");
	private static final UUID U_MOVE = UUID.fromString("5b9d7c3e-1a52-4c0e-9a41-0a0a00000003");
	private static final UUID U_ATK = UUID.fromString("5b9d7c3e-1a52-4c0e-9a41-0a0a00000004");
	private static final UUID U_KB = UUID.fromString("5b9d7c3e-1a52-4c0e-9a41-0a0a00000005");
	private static final UUID U_LUCK = UUID.fromString("5b9d7c3e-1a52-4c0e-9a41-0a0a00000006");
	private static final UUID U_BREAK = UUID.fromString("5b9d7c3e-1a52-4c0e-9a41-0a0a00000007");
	private static final UUID U_CORR = UUID.fromString("5b9d7c3e-1a52-4c0e-9a41-0a0a00000008");

	private PassiveEffects() {
	}

	// ------------------------------------------------------------------ helpers

	public static PlayerRuntime rt(ServerPlayerEntity p) {
		return RT.computeIfAbsent(p.getUuid(), k -> new PlayerRuntime());
	}

	public static void reset(UUID id) {
		RT.remove(id);
	}

	public static Set<String> skills(ServerPlayerEntity p) {
		MinecraftServer s = p.getServer();
		if (s == null) {
			return Set.of();
		}
		return SkillState.get(s).skillsOf(p.getUuid());
	}

	public static double maxMana(Set<String> s) {
		return 100 + (s.contains("mana_pool") ? 20 : 0);
	}

	public static double maxStamina(Set<String> s) {
		return 100 + (s.contains("stamina_mastery") ? 20 : 0);
	}

	private static boolean isNight(World w) {
		long t = w.getTimeOfDay() % 24000L;
		return t >= 13000L && t <= 23000L;
	}

	private static boolean isDark(LivingEntity e) {
		return e.getWorld().getLightLevel(e.getBlockPos()) <= 4;
	}

	private static boolean isBoss(LivingEntity e) {
		return e.getMaxHealth() >= 100f;
	}

	private static boolean isBeast(LivingEntity e) {
		EntityType<?> t = e.getType();
		return t == EntityType.SPIDER || t == EntityType.CAVE_SPIDER || t == EntityType.HOGLIN
				|| t == EntityType.ZOGLIN || t == EntityType.RAVAGER || t == EntityType.POLAR_BEAR
				|| t == EntityType.WOLF;
	}

	private static boolean isBehind(LivingEntity attacker, LivingEntity victim) {
		double yr = Math.toRadians(victim.getYaw());
		Vec3d look = new Vec3d(-Math.sin(yr), 0, Math.cos(yr));
		Vec3d to = new Vec3d(attacker.getX() - victim.getX(), 0, attacker.getZ() - victim.getZ()).normalize();
		return look.dotProduct(to) <= -0.5;
	}

	private static int ailments(LivingEntity e) {
		int n = 0;
		for (StatusEffectInstance i : e.getStatusEffects()) {
			if (i.getEffectType().getCategory() == StatusEffectCategory.HARMFUL) {
				n++;
			}
		}
		if (e.isOnFire()) {
			n++;
		}
		if (e.isFrozen()) {
			n++;
		}
		return n;
	}

	private static boolean hasBuff(LivingEntity e) {
		for (StatusEffectInstance i : e.getStatusEffects()) {
			if (i.getEffectType().isBeneficial()) {
				return true;
			}
		}
		return false;
	}

	private static int perfectionistCount(ServerPlayerEntity p, Set<String> s) {
		if (!s.contains("perfectionist")) {
			return 0;
		}
		int n = 0;
		if (p.getMaxHealth() >= 30f) {
			n++;
		}
		if (p.getArmor() >= 12) {
			n++;
		}
		if (critChance(s) >= 0.25) {
			n++;
		}
		return n;
	}

	private static double critChance(Set<String> s) {
		double c = s.contains("critical_instinct") ? 0.07 : 0;
		double cap = 0.35 + (s.contains("limit_breaker") ? 0.05 : 0);
		return Math.min(cap, c);
	}

	private static void setMod(LivingEntity e, EntityAttribute attr, UUID id, String name, double value,
							   EntityAttributeModifier.Operation op) {
		EntityAttributeInstance inst = e.getAttributeInstance(attr);
		if (inst == null) {
			return;
		}
		EntityAttributeModifier cur = inst.getModifier(id);
		if (Math.abs(value) < 1.0E-9) {
			if (cur != null) {
				inst.removeModifier(id);
			}
			return;
		}
		if (cur != null && Math.abs(cur.getValue() - value) < 1.0E-6) {
			return;
		}
		if (cur != null) {
			inst.removeModifier(id);
		}
		inst.addTemporaryModifier(new EntityAttributeModifier(id, name, value, op));
	}

	private static void particles(LivingEntity e, net.minecraft.particle.ParticleEffect type, int count) {
		if (e.getWorld() instanceof ServerWorld sw) {
			sw.spawnParticles(type, e.getX(), e.getBodyY(0.6), e.getZ(), count, 0.25, 0.25, 0.25, 0.1);
		}
	}

	// ------------------------------------------------------------------ per-tick

	public static void serverTick(MinecraftServer server) {
		int now = server.getTicks();
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			if (p.isDead() || p.isSpectator()) {
				continue;
			}
			tickPlayer(server, p, now);
		}
		if (now % 10 == 0) {
			expire(BREAKS, U_BREAK, now);
			expire(CORRS, U_CORR, now);
		}
	}

	private static void expire(Map<UUID, Brk> map, UUID modId, int now) {
		Iterator<Map.Entry<UUID, Brk>> it = map.entrySet().iterator();
		while (it.hasNext()) {
			Brk b = it.next().getValue();
			if (b.target.isRemoved() || !b.target.isAlive() || now >= b.until) {
				setMod(b.target, EntityAttributes.GENERIC_ARMOR, modId, "orv_armor_debuff", 0, EntityAttributeModifier.Operation.ADDITION);
				it.remove();
			}
		}
	}

	private static void tickPlayer(MinecraftServer server, ServerPlayerEntity p, int now) {
		Set<String> s = skills(p);
		PlayerRuntime rt = rt(p);
		double maxM = maxMana(s);
		double maxS = maxStamina(s);

		if (!rt.init) {
			rt.init = true;
			rt.mana = maxM;
			rt.stamina = maxS;
			rt.lastX = p.getX();
			rt.lastZ = p.getZ();
		}

		// ---- movement tracking
		double dx = p.getX() - rt.lastX;
		double dz = p.getZ() - rt.lastZ;
		rt.lastX = p.getX();
		rt.lastZ = p.getZ();
		boolean moving = dx * dx + dz * dz > 1.0E-4;
		if (moving) {
			rt.stillTicks = 0;
			rt.movingTicks++;
		} else {
			rt.stillTicks++;
			if (rt.stillTicks > 10) {
				rt.movingTicks = 0;
			}
		}
		rt.momentum = s.contains("momentum") ? Math.min(0.12, rt.movingTicks / 20.0 * 0.015) : 0;

		// ---- Mana
		double manaMult = 1.0;
		if (s.contains("mana_flow")) {
			manaMult += 0.25;
		}
		if (s.contains("arcane_recovery") && now - rt.lastDamagedTick >= 100) {
			manaMult += 0.5;
		}
		rt.mana = Math.min(maxM, rt.mana + (1.0 / 20.0) * manaMult);

		// ---- Stamina
		if (rt.stamina < 1.0 && p.isSprinting()) {
			p.setSprinting(false);
		}
		if (p.isSprinting()) {
			double drain = (100.0 / 16.0) / 20.0;
			if (s.contains("fleet_footed")) {
				drain *= 0.75;
			}
			rt.stamina = Math.max(0, rt.stamina - drain);
			rt.lastSprintTick = now;
		} else if (now - rt.lastSprintTick >= 20) {
			double regen = 10.0 / 20.0;
			if (s.contains("swift_recovery")) {
				regen *= 1.25;
			}
			rt.stamina = Math.min(maxS, rt.stamina + regen);
		}
		rt.stamina = Math.min(rt.stamina, maxS);

		if (s.contains("second_wind")) {
			if (rt.stamina <= maxS * 0.2 && now >= rt.secondWindIcd && rt.secondWindLeft <= 0) {
				rt.secondWindLeft = 20;
				rt.secondWindIcd = now + 900;
			}
			if (rt.secondWindLeft > 0) {
				double add = Math.min(rt.secondWindLeft, 0.5);
				rt.stamina = Math.min(maxS, rt.stamina + add);
				rt.secondWindLeft -= add;
			}
		}

		// ---- healing over time
		if (p.getHealth() < p.getMaxHealth()) {
			if (s.contains("regeneration") && now - rt.lastDamagedTick >= 100 && now - rt.regenTick >= 160) {
				p.heal(1f);
				rt.regenTick = now;
			}
			if (s.contains("rejuvenation") && now - rt.lastDamagedTick >= 200 && now - rt.rejuvTick >= 80) {
				p.heal(1f);
				rt.rejuvTick = now;
			}
			if (s.contains("natural_healing") && now - rt.naturalTick >= 240) {
				p.heal(1f);
				rt.naturalTick = now;
			}
		}
		if (s.contains("guardian_spirit") && now >= rt.guardianIcd
				&& p.getHealth() <= p.getMaxHealth() * 0.25f) {
			p.setAbsorptionAmount(Math.max(p.getAbsorptionAmount(), 4f));
			rt.guardianIcd = now + 2400;
		}
		if (rt.overhealUntil > 0 && now >= rt.overhealUntil) {
			p.setAbsorptionAmount(Math.max(0f, p.getAbsorptionAmount() - rt.overhealAmount));
			rt.overhealUntil = 0;
			rt.overhealAmount = 0f;
		}

		// ---- timers
		if (now > rt.bloodlustUntil) {
			rt.bloodlustStacks = 0;
		}
		if (now > rt.rhythmUntil) {
			rt.rhythm = 0;
		}
		if (now > rt.focusUntil) {
			rt.focus = 0;
		}

		// ---- environment
		if (s.contains("deep_diver") && p.isSubmergedInWater() && p.getAir() < p.getMaxAir() && now % 3 == 0) {
			p.setAir(p.getAir() + 1);
		}
		if (s.contains("cold_resistance")) {
			int f = p.getFrozenTicks();
			if (f > rt.lastFrozen && p.getRandom().nextFloat() < 0.4f) {
				f--;
				p.setFrozenTicks(f);
			}
			rt.lastFrozen = f;
		}

		if (now % 5 == 0) {
			applyStats(p, s, rt, now);
		}
		if (now % 2 == 0) {
			syncResources(p, s, rt);
		}
	}

	private static void applyStats(ServerPlayerEntity p, Set<String> s, PlayerRuntime rt, int now) {
		double hpFrac = p.getMaxHealth() > 0 ? p.getHealth() / p.getMaxHealth() : 1.0;
		boolean dark = isDark(p);
		boolean night = isNight(p.getWorld());
		double capUp = s.contains("limit_breaker") ? 0.05 : 0;

		// armor
		double armor = 0;
		if (s.contains("iron_body")) {
			armor += 2;
		}
		if (s.contains("earth_heart")) {
			armor += 1;
		}
		if (s.contains("vitality_surge") && hpFrac >= 0.95) {
			armor += 3;
		}
		if (s.contains("last_stand") && hpFrac <= 0.30) {
			armor += 4;
		}
		if (s.contains("dark_embrace") && dark) {
			armor += 1;
		}
		armor = Math.min(8, armor);

		// max HP
		double maxHp = s.contains("toughness") ? 2 : 0;

		// movement speed
		double move = 0;
		if (s.contains("swift_feet")) {
			move += 0.06;
		}
		if (s.contains("wind_soul")) {
			move += 0.05;
		}
		if (s.contains("shadow_walker") && dark) {
			move += 0.10;
		}
		if (s.contains("escape_artist") && hpFrac <= 0.30) {
			move += 0.15;
		}
		move += rt.momentum;
		move = Math.min(0.25 + capUp, move);

		// attack speed
		double atk = 0;
		if (s.contains("dual_wielder") && isWeapon(p.getMainHandStack().getItem()) && isWeapon(p.getOffHandStack().getItem())) {
			atk += 0.10;
		}
		if (s.contains("night_predator") && night) {
			atk += 0.05;
		}
		if (s.contains("killing_rhythm") && now < rt.rhythmUntil) {
			atk += 0.04 * Math.min(4, rt.rhythm);
		}
		if (s.contains("adrenaline_rush") && now < rt.adrenalineUntil) {
			atk += 0.20;
		}
		atk = Math.min(0.25 + capUp, atk);

		// knockback resistance
		double kb = 0;
		if (s.contains("earth_heart")) {
			kb += 0.1;
		}
		if (s.contains("steadfast")) {
			kb += 0.3;
		}
		if (s.contains("unstoppable") && now - rt.lastAttackTick <= 20) {
			kb += 0.6;
		}
		kb = Math.min(1.0, kb);

		double luck = s.contains("lucky_finder") ? 1 : 0;

		setMod(p, EntityAttributes.GENERIC_ARMOR, U_ARMOR, "orv_armor", armor, EntityAttributeModifier.Operation.ADDITION);
		setMod(p, EntityAttributes.GENERIC_MAX_HEALTH, U_HP, "orv_hp", maxHp, EntityAttributeModifier.Operation.ADDITION);
		setMod(p, EntityAttributes.GENERIC_MOVEMENT_SPEED, U_MOVE, "orv_move", move, EntityAttributeModifier.Operation.MULTIPLY_BASE);
		setMod(p, EntityAttributes.GENERIC_ATTACK_SPEED, U_ATK, "orv_atk_speed", atk, EntityAttributeModifier.Operation.MULTIPLY_BASE);
		setMod(p, EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, U_KB, "orv_kb", kb, EntityAttributeModifier.Operation.ADDITION);
		setMod(p, EntityAttributes.GENERIC_LUCK, U_LUCK, "orv_luck", luck, EntityAttributeModifier.Operation.ADDITION);
	}

	private static boolean isWeapon(net.minecraft.item.Item item) {
		return item instanceof SwordItem || item instanceof AxeItem;
	}

	private static void syncResources(ServerPlayerEntity p, Set<String> s, PlayerRuntime rt) {
		int m = (int) Math.round(rt.mana);
		int mm = (int) Math.round(maxMana(s));
		int st = (int) Math.round(rt.stamina);
		int ms = (int) Math.round(maxStamina(s));
		if (m == rt.sentMana && mm == rt.sentMaxMana && st == rt.sentStamina && ms == rt.sentMaxStamina) {
			return;
		}
		rt.sentMana = m;
		rt.sentMaxMana = mm;
		rt.sentStamina = st;
		rt.sentMaxStamina = ms;
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeVarInt(m);
		buf.writeVarInt(mm);
		buf.writeVarInt(st);
		buf.writeVarInt(ms);
		ServerPlayNetworking.send(p, OrvWindowMod.RES, buf);
	}

	// ------------------------------------------------------------------ events

	public static ActionResult onAttackEntity(PlayerEntity player, World world, Hand hand, Entity entity, EntityHitResult hit) {
		if (player instanceof ServerPlayerEntity sp && !world.isClient) {
			rt(sp).lastSwingProgress = sp.getAttackCooldownProgress(0.5f);
		}
		return ActionResult.PASS;
	}

	public static void doubleJump(ServerPlayerEntity p) {
		MinecraftServer server = p.getServer();
		if (server == null) {
			return;
		}
		int now = server.getTicks();
		Set<String> s = skills(p);
		PlayerRuntime rt = rt(p);
		if (!s.contains("double_jump") || rt.stamina < 15 || now - rt.djIcd < 5) {
			return;
		}
		rt.stamina -= 15;
		rt.djIcd = now;
		p.fallDistance = 0f;
	}

	public static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
		if (internal || !(entity instanceof ServerPlayerEntity p)) {
			return true;
		}
		MinecraftServer server = p.getServer();
		if (server == null) {
			return true;
		}
		Set<String> s = skills(p);
		if (s.isEmpty()) {
			return true;
		}
		int now = server.getTicks();
		PlayerRuntime rt = rt(p);
		boolean projectile = source.getSource() instanceof ProjectileEntity;
		boolean direct = source.getSource() != null && source.getAttacker() != null;

		if (projectile && s.contains("deflection") && now >= rt.deflectIcd && p.getRandom().nextFloat() < 0.15f) {
			rt.deflectIcd = now + 40;
			particles(p, ParticleTypes.CRIT, 6);
			return false;
		}
		if ((projectile || direct) && s.contains("evasive_instinct") && now >= rt.evasiveIcd
				&& p.getRandom().nextFloat() < 0.06f) {
			rt.evasiveIcd = now + 30;
			particles(p, ParticleTypes.CLOUD, 6);
			return false;
		}
		return true;
	}

	public static boolean allowDeath(LivingEntity entity, DamageSource source, float amount) {
		if (!(entity instanceof ServerPlayerEntity p)) {
			return true;
		}
		MinecraftServer server = p.getServer();
		if (server == null) {
			return true;
		}
		Set<String> s = skills(p);
		if (!s.contains("second_chance")) {
			return true;
		}
		int now = server.getTicks();
		PlayerRuntime rt = rt(p);
		if (now >= rt.secondChanceIcd && p.getRandom().nextFloat() < 0.20f) {
			rt.secondChanceIcd = now + 6000;
			p.setHealth(2f);
			p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 40, 4));
			particles(p, ParticleTypes.TOTEM_OF_UNDYING, 20);
			return false;
		}
		return true;
	}

	public static void afterDeath(LivingEntity entity, DamageSource source) {
		if (!(source.getAttacker() instanceof ServerPlayerEntity killer) || killer == entity) {
			return;
		}
		MinecraftServer server = killer.getServer();
		if (server == null) {
			return;
		}
		int now = server.getTicks();
		Set<String> s = skills(killer);
		if (s.isEmpty()) {
			return;
		}
		PlayerRuntime rt = rt(killer);

		if (s.contains("bloodlust")) {
			int stacks = now < rt.bloodlustUntil ? rt.bloodlustStacks : 0;
			rt.bloodlustStacks = Math.min(3, stacks + 1);
			rt.bloodlustUntil = now + 160;
		}
		if (s.contains("killing_rhythm")) {
			int r = (now - rt.lastKill <= 120 && now < rt.rhythmUntil) ? rt.rhythm : 0;
			rt.rhythm = Math.min(4, r + 1);
			rt.rhythmUntil = now + 120;
		}
		rt.lastKill = now;
		if (s.contains("blood_harvest")) {
			killer.heal(isBoss(entity) ? 4f : 1f);
		}
		if (s.contains("energy_absorption")) {
			rt.mana = Math.min(maxMana(s), rt.mana + 5);
			rt.stamina = Math.min(maxStamina(s), rt.stamina + 8);
		}
	}

	// ------------------------------------------------------------------ damage

	/** Called from the mixin after armor has been applied. */
	public static float modifyDamage(LivingEntity victim, DamageSource source, float amount, float raw) {
		if (internal || amount <= 0f) {
			return amount;
		}
		MinecraftServer server = victim.getServer();
		if (server == null) {
			return amount;
		}
		int now = server.getTicks();
		if (source.getAttacker() instanceof ServerPlayerEntity atk && atk != victim) {
			amount = outgoing(atk, victim, source, amount, raw, now);
		}
		if (victim instanceof ServerPlayerEntity vp) {
			amount = incoming(vp, source, amount, now);
		}
		return amount;
	}

	private static float outgoing(ServerPlayerEntity atk, LivingEntity victim, DamageSource source,
								  float amount, float raw, int now) {
		Set<String> s = skills(atk);
		if (s.isEmpty()) {
			return amount;
		}
		boolean melee = source.isOf(DamageTypes.PLAYER_ATTACK);
		boolean proj = source.getSource() instanceof PersistentProjectileEntity;
		boolean fireSrc = source.isIn(DamageTypeTags.IS_FIRE);
		if (!melee && !proj && !fireSrc) {
			return amount;
		}
		PlayerRuntime rt = rt(atk);
		boolean heavy = melee && rt.lastSwingProgress >= 0.9f;

		// ---- additive damage bonus pool
		double pool = 0;
		if (melee) {
			if (s.contains("power_strike")) {
				pool += 0.06;
			}
			if (s.contains("weapon_mastery")) {
				pool += 0.04;
			}
			if (heavy && s.contains("heavy_impact")) {
				pool += 0.12;
			}
			if (s.contains("combo_master")) {
				int stacks = (now - rt.comboLastTick <= 40) ? rt.combo : 0;
				pool += 0.03 * Math.min(5, stacks);
			}
			if (s.contains("backstabber") && isBehind(atk, victim)) {
				pool += 0.15;
			}
		}
		if (fireSrc && s.contains("inferno_soul")) {
			pool += 0.12;
		}
		if (proj && s.contains("sniper_instinct") && atk.distanceTo(victim) > 20f) {
			pool += 0.12;
		}
		if (s.contains("executioner") && victim.getMaxHealth() > 0 && victim.getHealth() / victim.getMaxHealth() <= 0.30f) {
			pool += 0.15;
		}
		if (s.contains("first_blood")) {
			Integer last = rt.firstHit.get(victim.getUuid());
			if (last == null || now - last > 300) {
				pool += 0.25;
			}
		}
		if (s.contains("monster_hunter") && victim instanceof Monster) {
			pool += 0.08;
		}
		if (s.contains("beast_slayer") && isBeast(victim)) {
			pool += 0.15;
		}
		if (s.contains("holy_light") && victim.getGroup() == EntityGroup.UNDEAD) {
			pool += 0.20;
		}
		if (s.contains("void_resonance") && hasBuff(victim)) {
			pool += 0.12;
		}
		if (s.contains("ruthless")) {
			pool += Math.min(0.16, 0.08 * ailments(victim));
		}
		if (s.contains("predator") && victim.getUuid().equals(rt.markedTarget) && now < rt.markUntil) {
			pool += 0.12;
		}
		if (s.contains("ambusher") && victim instanceof MobEntity mob && mob.getTarget() != atk) {
			Integer last = rt.ambushHit.get(victim.getUuid());
			if (last == null || now - last > 300) {
				pool += 0.20;
			}
		}
		if (s.contains("berserker") && atk.getMaxHealth() > 0) {
			double lostPct = (1.0 - atk.getHealth() / atk.getMaxHealth()) * 100.0;
			pool += Math.min(0.14, 0.002 * lostPct);
		}
		if (s.contains("bloodlust") && now < rt.bloodlustUntil) {
			pool += 0.06 * rt.bloodlustStacks;
		}
		if (s.contains("battle_focus") && now < rt.focusUntil) {
			pool += 0.02 * rt.focus;
		}
		if (s.contains("night_predator") && isNight(atk.getWorld())) {
			pool += 0.08;
		}
		if (s.contains("dark_embrace") && isDark(atk)) {
			pool += 0.08;
		}
		boolean overcharged = false;
		if (s.contains("overcharge") && now >= rt.overchargeIcd
				&& rt.mana >= maxMana(s) - 0.01 && rt.stamina >= maxStamina(s) - 0.01) {
			pool += 0.30;
			overcharged = true;
		}
		pool += perfectionistCount(atk, s) * 0.03;
		pool = Math.min(0.50, pool);
		amount *= (float) (1.0 + pool);

		// ---- armor penetration
		if (s.contains("piercing_force") && raw > 0f && (melee || proj)) {
			float armor = victim.getArmor();
			float tough = (float) victim.getAttributeValue(EntityAttributes.GENERIC_ARMOR_TOUGHNESS);
			float base = DamageUtil.getDamageLeft(raw, armor, tough);
			float pierced = DamageUtil.getDamageLeft(raw, armor * 0.9f, tough);
			if (base > 0.001f && pierced > base) {
				amount *= pierced / base;
			}
		}

		// ---- critical hits
		if (melee) {
			double critMult = Math.min(2.2, 1.5 + (s.contains("fatal_blow") ? 0.2 : 0));
			boolean vanillaCrit = heavy && atk.fallDistance > 0.0F && !atk.isOnGround() && !atk.isClimbing()
					&& !atk.isTouchingWater() && !atk.hasStatusEffect(StatusEffects.BLINDNESS)
					&& !atk.hasVehicle() && !atk.isSprinting();
			if (vanillaCrit) {
				if (critMult > 1.5) {
					amount *= (float) (critMult / 1.5);
				}
			} else if (s.contains("critical_instinct") && atk.getRandom().nextDouble() < critChance(s)) {
				amount *= (float) critMult;
				particles(victim, ParticleTypes.CRIT, 10);
			}
			if (heavy && s.contains("heavy_impact")) {
				victim.takeKnockback(0.1, atk.getX() - victim.getX(), atk.getZ() - victim.getZ());
			}
		}

		// ---- side effects
		if (overcharged) {
			rt.overchargeIcd = now + 200;
		}
		if (melee) {
			int stacks = (now - rt.comboLastTick <= 40) ? rt.combo : 0;
			rt.combo = Math.min(6, stacks + 1);
			rt.comboLastTick = now;
			rt.lastAttackTick = now;

			if (s.contains("adrenaline_rush")) {
				rt.adrenalineHits = (now - rt.adrenalineLast <= 40) ? rt.adrenalineHits + 1 : 1;
				rt.adrenalineLast = now;
				if (rt.adrenalineHits >= 8 && now >= rt.adrenalineIcd) {
					rt.adrenalineUntil = now + 100;
					rt.adrenalineIcd = now + 300;
					rt.adrenalineHits = 0;
				}
			}
			if (s.contains("battle_focus")) {
				int f = now < rt.focusUntil ? rt.focus : 0;
				rt.focus = Math.min(5, f + 1);
				rt.focusUntil = now + 120;
			}
			if (s.contains("static_charge")) {
				rt.charge++;
				if (rt.charge >= 10) {
					rt.charge = 0;
					releaseCharge(atk, victim);
				}
			}
			if (s.contains("burning_blood") && now >= rt.burnIcd && atk.getRandom().nextFloat() < 0.15f) {
				rt.burnIcd = now + 20;
				victim.setOnFireFor(3 + (s.contains("inferno_soul") ? 1 : 0));
			}
			if (s.contains("toxic_touch") && now >= rt.toxicIcd && atk.getRandom().nextFloat() < 0.15f) {
				rt.toxicIcd = now + 20;
				victim.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 80, 0), atk);
			}
		}
		if (melee || proj) {
			if (s.contains("first_blood")) {
				if (rt.firstHit.size() > 64) {
					rt.firstHit.clear();
				}
				rt.firstHit.put(victim.getUuid(), now);
			}
			if (s.contains("ambusher")) {
				if (rt.ambushHit.size() > 64) {
					rt.ambushHit.clear();
				}
				rt.ambushHit.put(victim.getUuid(), now);
			}
			if (s.contains("marked_prey") && (rt.markedTarget == null || now >= rt.markUntil)) {
				rt.markedTarget = victim.getUuid();
				rt.markUntil = now + 200;
				victim.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 200, 0, false, false));
			}
			if (s.contains("armor_breaker") && atk.getRandom().nextFloat() < 0.12f) {
				int max = isBoss(victim) ? 1 : 3;
				Brk b = BREAKS.computeIfAbsent(victim.getUuid(), k -> new Brk());
				b.target = victim;
				b.stacks = Math.min(max, (b.until > now ? b.stacks : 0) + 1);
				b.until = now + 120;
				setMod(victim, EntityAttributes.GENERIC_ARMOR, U_BREAK, "orv_armor_break", -2.0 * b.stacks,
						EntityAttributeModifier.Operation.ADDITION);
			}
			if (s.contains("corrosion") && victim.hasStatusEffect(StatusEffects.POISON)) {
				Brk b = CORRS.computeIfAbsent(victim.getUuid(), k -> new Brk());
				b.target = victim;
				b.stacks = 1;
				b.until = now + 100;
				setMod(victim, EntityAttributes.GENERIC_ARMOR, U_CORR, "orv_corrosion", isBoss(victim) ? -1.0 : -3.0,
						EntityAttributeModifier.Operation.ADDITION);
			}
		}

		// ---- life steal
		double steal = 0;
		if (s.contains("vampirism")) {
			steal += 0.04;
		}
		if (s.contains("soul_drain") && ailments(victim) > 0) {
			steal += 0.05;
		}
		if (s.contains("blood_pact") && atk.getHealth() <= atk.getMaxHealth() * 0.40f) {
			steal += 0.04;
		}
		steal = Math.min(0.12, steal);
		if (steal > 0 && victim.getGroup() != EntityGroup.UNDEAD) {
			float healed = (float) (Math.min(amount, victim.getHealth()) * steal);
			if (healed > 0.01f) {
				atk.heal(healed);
			}
		}
		return amount;
	}

	private static void releaseCharge(ServerPlayerEntity atk, LivingEntity hitVictim) {
		World w = atk.getWorld();
		Box box = atk.getBoundingBox().expand(3.0);
		internal = true;
		try {
			for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, box, x -> x != atk && x != hitVictim && x.isAlive())) {
				e.damage(atk.getDamageSources().lightningBolt(), 4f);
			}
		} finally {
			internal = false;
		}
		if (w instanceof ServerWorld sw) {
			sw.spawnParticles(ParticleTypes.ELECTRIC_SPARK, atk.getX(), atk.getBodyY(0.5), atk.getZ(), 24, 0.8, 0.5, 0.8, 0.2);
		}
	}

	private static String elementKey(DamageSource src) {
		if (src.isIn(DamageTypeTags.IS_FIRE)) {
			return "fire";
		}
		if (src.isIn(DamageTypeTags.IS_FREEZING)) {
			return "freeze";
		}
		if (src.isIn(DamageTypeTags.IS_LIGHTNING)) {
			return "lightning";
		}
		if (src.isOf(DamageTypes.WITHER)) {
			return "wither";
		}
		return null;
	}

	private static float incoming(ServerPlayerEntity vp, DamageSource source, float amount, int now) {
		Set<String> s = skills(vp);
		if (s.isEmpty()) {
			return amount;
		}
		PlayerRuntime rt = rt(vp);
		double hpFrac = vp.getMaxHealth() > 0 ? vp.getHealth() / vp.getMaxHealth() : 1.0;
		double reduce = 0;

		if (s.contains("thick_skin")) {
			reduce += 0.03;
		}
		if (s.contains("last_stand") && hpFrac <= 0.30) {
			reduce += 0.08;
		}
		if (s.contains("fortress") && rt.stillTicks >= 30) {
			reduce += 0.10;
		}
		if (s.contains("pain_suppression") && now < rt.painUntil) {
			reduce += 0.15;
		}
		if (s.contains("magic_barrier") && (source.isOf(DamageTypes.MAGIC) || source.isOf(DamageTypes.INDIRECT_MAGIC))) {
			reduce += 0.20;
		}
		String element = elementKey(source);
		if (element != null) {
			if (s.contains("elemental_resistance")) {
				reduce += 0.10;
			}
			if (s.contains("ashen_skin") && element.equals("fire")) {
				reduce += 0.20;
			}
			if (s.contains("adaptation")) {
				int[] a = rt.adapt.get(element);
				int stacks = (a != null && now < a[1]) ? a[0] : 0;
				reduce += 0.08 * stacks;
				rt.adapt.put(element, new int[]{Math.min(3, stacks + 1), now + 200});
			}
		}
		if (source.isIn(DamageTypeTags.IS_FALL)) {
			if (s.contains("lightweight")) {
				reduce += 0.20;
			}
			if (s.contains("gravity_defier")) {
				reduce += 0.15;
			}
		}
		Entity attacker = source.getAttacker();
		if (s.contains("unyielding") && attacker != null) {
			if (attacker.getUuid().equals(rt.lastAttacker) && now - rt.repeatTick <= 100) {
				rt.repeat = Math.min(4, rt.repeat + 1);
			} else {
				rt.repeat = 0;
			}
			rt.lastAttacker = attacker.getUuid();
			rt.repeatTick = now;
			reduce += 0.05 * rt.repeat;
		}
		reduce += perfectionistCount(vp, s) * 0.03;

		double cap = 0.40 + (s.contains("limit_breaker") ? 0.05 : 0);
		amount *= (float) (1.0 - Math.min(cap, reduce));

		if (s.contains("death_ward") && now >= rt.deathWardIcd && vp.getHealth() - amount <= 0f) {
			amount *= 0.8f;
			rt.deathWardIcd = now + 600;
		}
		if (s.contains("pain_suppression") && amount >= 4f && now >= rt.painIcd) {
			rt.painUntil = now + 60;
			rt.painIcd = now + 160;
		}
		rt.lastDamagedTick = now;
		rt.movingTicks = 0;

		if (s.contains("reflective_armor") && attacker instanceof LivingEntity la
				&& source.getSource() == attacker && la != vp) {
			float reflect = Math.min(4f, amount * 0.12f);
			if (reflect >= 0.1f) {
				internal = true;
				try {
					la.damage(vp.getDamageSources().thorns(vp), reflect);
				} finally {
					internal = false;
				}
			}
		}
		return amount;
	}

	// ------------------------------------------------------------------ healing & xp

	public static float modifyHeal(LivingEntity e, float amount) {
		if (internal || !(e instanceof ServerPlayerEntity p) || amount <= 0f) {
			return amount;
		}
		MinecraftServer server = p.getServer();
		if (server == null) {
			return amount;
		}
		Set<String> s = skills(p);
		if (s.isEmpty()) {
			return amount;
		}
		int now = server.getTicks();
		PlayerRuntime rt = rt(p);
		double hpFrac = p.getMaxHealth() > 0 ? p.getHealth() / p.getMaxHealth() : 1.0;

		double mult = 1.0;
		if (s.contains("natures_blessing")) {
			mult += 0.15;
		}
		if (s.contains("desperate_recovery") && hpFrac <= 0.30) {
			mult += 0.30;
		}
		float a = (float) (amount * mult);
		if (s.contains("life_bond")) {
			a += Math.max(0.5f, p.getMaxHealth() * 0.01f);
		}

		if (s.contains("overheal")) {
			float excess = p.getHealth() + a - p.getMaxHealth();
			if (excess > 0.5f) {
				float gain = Math.min(4f - rt.overhealAmount, excess * 0.5f);
				if (gain > 0f) {
					p.setAbsorptionAmount(p.getAbsorptionAmount() + gain);
					rt.overhealAmount += gain;
					rt.overhealUntil = now + 400;
				}
			}
		}
		if (s.contains("purification") && a >= 2f && now >= rt.purifyIcd && p.getRandom().nextFloat() < 0.25f) {
			for (StatusEffectInstance i : new ArrayList<>(p.getStatusEffects())) {
				if (i.getEffectType().getCategory() == StatusEffectCategory.HARMFUL) {
					p.removeStatusEffect(i.getEffectType());
					rt.purifyIcd = now + 160;
					break;
				}
			}
		}
		return a;
	}

	public static int modifyXp(PlayerEntity player, int xp) {
		if (!(player instanceof ServerPlayerEntity p) || xp <= 0) {
			return xp;
		}
		if (skills(p).contains("fast_learner")) {
			return (int) Math.round(xp * 1.10);
		}
		return xp;
	}
}
