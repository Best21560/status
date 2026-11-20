package com.orv.window;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * Element helpers (the castable spells were removed for now): element tracking for Elemental Harmony,
 * Chain Lightning and Static Charge.
 */
public final class Spells {
	private Spells() {
	}

	private static final Random RAND = new Random();

	/** True while extra element damage is being dealt (so PassiveEngine does not count bonuses twice). */
	public static boolean inSpell = false;

	/** Extra element damage dealt by the player, counted as magic damage from them. */
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
}
