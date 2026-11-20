package com.orv.window;

import net.minecraft.entity.player.HungerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

/**
 * Survival rules of this mod: there is no hunger (food can be eaten at any time), and Health comes back by
 * standing still: after 10 seconds without moving or being hurt it heals 5 per second. Recovery potions heal
 * a percentage of the maximum Health per second and work while moving.
 */
public final class Vitality {
	private Vitality() {
	}

	/** Recovery potion tiers: percent of max Health per second, and duration in seconds. */
	public static final float[] POTION_PCT = {1.5f, 3f, 5f, 8f};
	public static final int[] POTION_SECONDS = {20, 20, 20, 25};

	public static final int IDLE_TICKS = 200;
	public static final float IDLE_HEAL = 5f;

	public static void tick(ServerPlayerEntity p, PlayerRes r, long now) {
		if (!p.isAlive() || p.isSpectator()) {
			return;
		}
		// no hunger: keep the food level just below the vanilla "natural regeneration" threshold, but never full,
		// so every food item can be eaten at any time and nobody starves
		HungerManager hm = p.getHungerManager();
		if (hm.getFoodLevel() != 17) {
			hm.setFoodLevel(17);
		}
		hm.setSaturationLevel(0f);

		Vec3d pos = p.getPos();
		float hp = p.getHealth();
		boolean moved = r.vitPos == null || pos.squaredDistanceTo(r.vitPos) > 0.0009;
		boolean hurt = hp < r.vitHp - 0.01f;
		r.vitPos = pos;
		r.vitHp = hp;
		if (moved || hurt || p.handSwinging) {
			r.idleTicks = 0;
		} else if (r.idleTicks < 100000) {
			r.idleTicks++;
		}

		if (now % 20 == 0 && hp < p.getMaxHealth()) {
			if (r.idleTicks >= IDLE_TICKS) {
				p.heal(IDLE_HEAL);
			}
			if (now < r.potionUntil) {
				p.heal(p.getMaxHealth() * r.potionPct / 100f);
			}
		}
		if (r.potionUntil != 0 && now >= r.potionUntil) {
			r.potionUntil = 0;
			r.potionPct = 0f;
		}
	}

	/** Drinking a recovery potion of the given tier (1..4). A weaker potion never replaces a stronger running one. */
	public static void drink(ServerPlayerEntity p, int tier) {
		int i = Math.max(0, Math.min(3, tier - 1));
		PlayerRes r = PassiveEngine.res(p.getUuid());
		long now = PassiveEngine.nowOf(p);
		if (now < r.potionUntil && r.potionPct > POTION_PCT[i]) {
			return;
		}
		r.potionPct = POTION_PCT[i];
		r.potionUntil = now + POTION_SECONDS[i] * 20L;
		p.sendMessage(Text.translatable("orvwindow.msg.potion", tier, POTION_PCT[i], POTION_SECONDS[i]), true);
	}
}
