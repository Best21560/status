package com.orv.window;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BrewingStandBlockEntity;
import net.minecraft.block.entity.EnchantingTableBlockEntity;
import net.minecraft.block.entity.EndGatewayBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Server-side parts of the passives that are not plain damage / attribute maths. */
public final class Extras {
	private Extras() {
	}

	private static final Random RAND = new Random();

	// ------------------------------------------------------------------ key actions from the client

	/** 6 Soul Eater, 7 dash / roll, 8 double jump (cost), 9 wall run (cost). */
	public static void action(ServerPlayerEntity p, PlayerRes r, int id, long now) {
		switch (id) {
			case 6 -> soulEater(p, r, now);
			case 10, 11, 12, 13 -> SlayerEngine.action(p, r, id, now);
			case 7 -> dash(p, r, now);
			case 8 -> {
				if (PassiveEngine.has(r, "double_jump")) {
					pay(r, 15f);
				}
			}
			case 9 -> {
				if (PassiveEngine.has(r, "wall_runner")) {
					pay(r, 25f);
				}
			}
			default -> {
			}
		}
	}

	/** Takes Stamina; Conservation gives a 10% chance that a skill is free. */
	private static void pay(PlayerRes r, float cost) {
		if (PassiveEngine.has(r, "conservation") && RAND.nextFloat() < 0.10f) {
			return;
		}
		r.stamina = Math.max(0f, r.stamina - cost);
	}

	private static void soulEater(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!PassiveEngine.has(r, "soul_eater")) {
			return;
		}
		if (r.souls < 5 || now < r.soulEaterIcd) {
			p.sendMessage(Text.translatable("orvwindow.msg.soul_eater_fail"), true);
			return;
		}
		r.souls -= 5;
		r.soulEaterIcd = now + 300;
		p.heal(6f);
		p.getServerWorld().spawnParticles(ParticleTypes.SOUL, p.getX(), p.getY() + 1.0, p.getZ(), 14, 0.4, 0.6, 0.4, 0.05);
		p.sendMessage(Text.translatable("orvwindow.msg.souls", r.souls), true);
	}

	/** The client moves the player; the server pays the Stamina and grants the short invulnerability. */
	private static void dash(ServerPlayerEntity p, PlayerRes r, long now) {
		if (p.isSpectator() || now < r.dashIcd || r.exhausted) {
			return;
		}
		float cost = 20f * (PassiveEngine.has(r, "acrobat") ? 0.85f : 1f);
		if (r.stamina < cost) {
			return;
		}
		pay(r, cost);
		float cd = 40f * (PassiveEngine.has(r, "dash_master") ? 0.8f : 1f);
		r.dashIcd = now + Math.round(cd);
		r.iframeUntil = now + 5 + (PassiveEngine.has(r, "combat_roll") ? 2 : 0);
		r.dashIframeUntil = r.iframeUntil;
		r.lastSprintTick = now; // no stamina regen right after a dash
	}

	// ------------------------------------------------------------------ stealth (used by the mixin)

	public static double stealthFactor(LivingEntity self) {
		if (!(self instanceof ServerPlayerEntity p)) {
			return 1.0;
		}
		PlayerRes r = PassiveEngine.res(p.getUuid());
		if (r.owned.isEmpty()) {
			return 1.0;
		}
		double f = 1.0;
		if (PassiveEngine.has(r, "silent_step") && !p.isSprinting()) {
			f *= 0.75;
		}
		if (PassiveEngine.has(r, "shadow_meld") && p.isSneaking() && r.dark) {
			f *= 0.65;
		}
		return f;
	}

	// ------------------------------------------------------------------ kills

	public static void onKill(ServerPlayerEntity p, PlayerRes r, LivingEntity victim, long now) {
		ServerWorld w = p.getServerWorld();
		if (PassiveEngine.has(r, "silent_killer") && victim instanceof MobEntity) {
			// monsters that only noticed you because of this kill forget about you
			for (MobEntity m : w.getEntitiesByClass(MobEntity.class, victim.getBoundingBox().expand(16.0),
					m -> m.isAlive() && m != victim)) {
				if (m.getTarget() == p && !r.aware.contains(m.getUuid())) {
					m.setTarget(null);
					m.getNavigation().stop();
				}
			}
		}
		if (PassiveEngine.has(r, "hunters_trophy") && !(victim instanceof PlayerEntity) && RAND.nextFloat() < 0.12f) {
			List<ItemEntity> drops = w.getEntitiesByClass(ItemEntity.class, victim.getBoundingBox().expand(2.0),
					i -> i.age <= 1 && !i.getStack().isEmpty());
			if (!drops.isEmpty()) {
				ItemStack extra = drops.get(RAND.nextInt(drops.size())).getStack().copy();
				extra.setCount(1);
				w.spawnEntity(new ItemEntity(w, victim.getX(), victim.getY() + 0.3, victim.getZ(), extra));
			}
		}
	}

	// ------------------------------------------------------------------ per tick

	public static void tick(ServerPlayerEntity p, PlayerRes r, long now) {
		SlayerEngine.tick(p, r, now);

		// Weapon Mastery: give back the durability that the hit just cost
		if (r.refundStack != null) {
			ItemStack s = r.refundStack;
			if (s.isDamageable() && s.getDamage() > r.refundTo) {
				s.setDamage(r.refundTo);
			}
			r.refundStack = null;
		}

		// Combat Medic: healing items heal 20% more
		if (PassiveEngine.has(r, "combat_medic")) {
			combatMedic(p, r);
		} else {
			r.useItem = null;
		}
		r.hpPrev = p.getHealth();

		ServerWorld w = p.getServerWorld();

		if (now % 10 == 0) {
			if (PassiveEngine.has(r, "silent_killer")) {
				Set<UUID> aware = new HashSet<>();
				for (MobEntity m : w.getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(24.0), m -> m.isAlive())) {
					if (m.getTarget() == p) {
						aware.add(m.getUuid());
					}
				}
				r.aware = aware;
			}
			if (PassiveEngine.has(r, "predators_instinct")) {
				for (HostileEntity h : w.getEntitiesByClass(HostileEntity.class, p.getBoundingBox().expand(20.0),
						h -> h.isAlive() && h.getHealth() <= 0.25f * h.getMaxHealth())) {
					w.spawnParticles(p, DustParticleEffect.DEFAULT, false, h.getX(), h.getY() + h.getHeight() + 0.3, h.getZ(),
							3, 0.2, 0.1, 0.2, 0.0);
				}
			}
			if (PassiveEngine.has(r, "survival_instinct")) {
				survival(p, r, w, now);
			}
			bloodTracker(p, r, w, now);
		}

		if (now % 2 == 0 && PassiveEngine.has(r, "weak_point_detection")) {
			weakPoint(p, r, w, now);
		}

		if (now % 20 == 0) {
			boolean sight = PassiveEngine.has(r, "mystic_sight");
			boolean reson = PassiveEngine.has(r, "mystic_resonance");
			if (sight || reson) {
				scanMagic(p, r, w, sight, reson);
			} else {
				r.nearMagic = false;
			}
			if (PassiveEngine.has(r, "trap_expert")) {
				showTraps(p, w);
			}
		}
	}

	// ------------------------------------------------------------------ combat medic

	private static void combatMedic(ServerPlayerEntity p, PlayerRes r) {
		if (p.isUsingItem()) {
			r.useItem = p.getActiveItem().getItem();
			r.useLeft = p.getItemUseTimeLeft();
			return;
		}
		Item it = r.useItem;
		r.useItem = null;
		if (it == null || r.useLeft > 1) {
			return;
		}
		if (it == Items.GOLDEN_APPLE || it == Items.ENCHANTED_GOLDEN_APPLE || it == Items.POTION
				|| it == Items.SUSPICIOUS_STEW || it == Items.HONEY_BOTTLE) {
			if (r.hpPrev >= 0f) {
				float gain = p.getHealth() - r.hpPrev;
				if (gain > 0f) {
					p.heal(gain * 0.2f);
				}
			}
			StatusEffectInstance reg = p.getStatusEffect(StatusEffects.REGENERATION);
			if (reg != null && reg.getDuration() > 0) {
				p.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, Math.round(reg.getDuration() * 1.2f),
						reg.getAmplifier(), reg.isAmbient(), reg.shouldShowParticles(), reg.shouldShowIcon()));
			}
		}
	}

	// ------------------------------------------------------------------ hunter

	private static void weakPoint(ServerPlayerEntity p, PlayerRes r, ServerWorld w, long now) {
		Vec3d eye = p.getEyePos();
		Vec3d dir = p.getRotationVec(1f);
		Vec3d end = eye.add(dir.multiply(20.0));
		Box box = p.getBoundingBox().stretch(dir.multiply(20.0)).expand(1.0);
		var hit = ProjectileUtil.raycast(p, eye, end, box,
				e -> e instanceof LivingEntity && e.isAlive() && !e.isSpectator() && e != p, 400.0);
		LivingEntity t = hit != null && hit.getEntity() instanceof LivingEntity le && p.canSee(le) ? le : null;
		if (t == null) {
			r.aimTarget = null;
			return;
		}
		if (!t.getUuid().equals(r.aimTarget)) {
			r.aimTarget = t.getUuid();
			r.aimSince = now;
			return;
		}
		if (now - r.aimSince >= 30 && now >= r.weakIcd && !t.getUuid().equals(r.weakTarget)) {
			r.weakTarget = t.getUuid();
			r.weakUntil = now + 60;
		}
		if (t.getUuid().equals(r.weakTarget) && now < r.weakUntil) {
			w.spawnParticles(p, ParticleTypes.CRIT, false, t.getX(), t.getY() + t.getHeight() * 0.6, t.getZ(),
					2, 0.15, 0.15, 0.15, 0.0);
		}
	}

	private static void bloodTracker(ServerPlayerEntity p, PlayerRes r, ServerWorld w, long now) {
		if (r.bleeders.isEmpty()) {
			return;
		}
		Iterator<Map.Entry<UUID, PlayerRes.Blood>> it = r.bleeders.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, PlayerRes.Blood> en = it.next();
			PlayerRes.Blood b = en.getValue();
			if (now >= b.until) {
				it.remove();
				continue;
			}
			Entity e = w.getEntity(en.getKey());
			if (e instanceof LivingEntity le && le.isAlive()) {
				Vec3d last = b.trail.peekLast();
				if (last == null || last.squaredDistanceTo(le.getPos()) > 0.6) {
					b.trail.addLast(le.getPos());
					if (b.trail.size() > 40) {
						b.trail.pollFirst();
					}
				}
			}
			for (Vec3d pt : b.trail) {
				if (pt.squaredDistanceTo(p.getPos()) <= 40.0 * 40.0) {
					w.spawnParticles(p, DustParticleEffect.DEFAULT, false, pt.x, pt.y + 0.1, pt.z, 1, 0.05, 0.0, 0.05, 0.0);
				}
			}
		}
	}

	private static void survival(ServerPlayerEntity p, PlayerRes r, ServerWorld w, long now) {
		if (now < r.warnIcd) {
			return;
		}
		boolean danger = false;
		for (CreeperEntity c : w.getEntitiesByClass(CreeperEntity.class, p.getBoundingBox().expand(10.0), c -> c.isAlive())) {
			if (!p.canSee(c)) {
				danger = true;
				break;
			}
		}
		if (!danger) {
			BlockPos base = p.getBlockPos();
			for (BlockPos pos : BlockPos.iterate(base.add(-2, -1, -2), base.add(2, 1, 2))) {
				BlockState s = w.getBlockState(pos);
				if (s.isOf(Blocks.LAVA) || isTrapBlock(s)) {
					danger = true;
					break;
				}
			}
		}
		if (danger) {
			r.warnIcd = now + 60;
			p.sendMessage(Text.translatable("orvwindow.msg.danger"), true);
			p.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 0.6f);
		}
	}

	private static boolean isTrapBlock(BlockState s) {
		Block b = s.getBlock();
		return b == Blocks.TRIPWIRE_HOOK || b == Blocks.TRIPWIRE || b == Blocks.TNT || b == Blocks.DISPENSER
				|| b == Blocks.SCULK_SENSOR || b == Blocks.SCULK_SHRIEKER || s.isIn(BlockTags.PRESSURE_PLATES);
	}

	private static void showTraps(ServerPlayerEntity p, ServerWorld w) {
		BlockPos base = p.getBlockPos();
		for (BlockPos pos : BlockPos.iterate(base.add(-6, -3, -6), base.add(6, 3, 6))) {
			if (isTrapBlock(w.getBlockState(pos))) {
				w.spawnParticles(p, DustParticleEffect.DEFAULT, false, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5,
						3, 0.2, 0.1, 0.2, 0.0);
			}
		}
	}

	// ------------------------------------------------------------------ magic sources

	private static boolean isMagicSource(BlockEntity be) {
		return be instanceof EnchantingTableBlockEntity || be instanceof BeaconBlockEntity
				|| be instanceof MobSpawnerBlockEntity || be instanceof BrewingStandBlockEntity
				|| be instanceof EndGatewayBlockEntity;
	}

	/** Mystic Sight: soft sparkles on magic blocks and enchanted items within 12 blocks. Mystic Resonance: power source within 8. */
	private static void scanMagic(ServerPlayerEntity p, PlayerRes r, ServerWorld w, boolean sight, boolean reson) {
		int rad = 12;
		BlockPos c = p.getBlockPos();
		boolean near = false;
		for (int cx = (c.getX() - rad) >> 4; cx <= (c.getX() + rad) >> 4; cx++) {
			for (int cz = (c.getZ() - rad) >> 4; cz <= (c.getZ() + rad) >> 4; cz++) {
				WorldChunk ch = w.getChunkManager().getWorldChunk(cx, cz);
				if (ch == null) {
					continue;
				}
				for (BlockEntity be : new ArrayList<>(ch.getBlockEntities().values())) {
					if (!isMagicSource(be)) {
						continue;
					}
					BlockPos bp = be.getPos();
					double d2 = bp.getSquaredDistance(p.getBlockPos());
					if (d2 <= 8.0 * 8.0) {
						near = true;
					}
					if (sight && d2 <= (double) rad * rad) {
						w.spawnParticles(p, ParticleTypes.ENCHANT, false, bp.getX() + 0.5, bp.getY() + 1.0, bp.getZ() + 0.5,
								8, 0.4, 0.4, 0.4, 0.4);
					}
				}
			}
		}
		Box box = p.getBoundingBox().expand(rad);
		for (EndCrystalEntity e : w.getEntitiesByClass(EndCrystalEntity.class, box, e -> true)) {
			if (e.squaredDistanceTo(p) <= 64.0) {
				near = true;
			}
			if (sight) {
				w.spawnParticles(p, ParticleTypes.ENCHANT, false, e.getX(), e.getY() + 1.0, e.getZ(), 8, 0.4, 0.4, 0.4, 0.4);
			}
		}
		if (sight) {
			for (ItemEntity ie : w.getEntitiesByClass(ItemEntity.class, box, i -> i.getStack().hasEnchantments())) {
				w.spawnParticles(p, ParticleTypes.END_ROD, false, ie.getX(), ie.getY() + 0.4, ie.getZ(), 2, 0.1, 0.1, 0.1, 0.01);
			}
		}
		r.nearMagic = near;
	}
}
