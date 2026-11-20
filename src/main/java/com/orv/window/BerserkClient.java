package com.orv.window;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Berserk Mode: while enraged (or during the Final Judgment cutscene) the player's keys are ignored and a simple AI
 * runs the character: charge the creature that hit us (or the nearest one) and keep attacking it.
 */
public final class BerserkClient {
	private BerserkClient() {
	}

	/** UUID (string) of whoever hit the player last, from the server; "" if nobody. */
	public static volatile String attacker = "";

	private static final double RANGE = 32.0;

	/** Called before the game reads the keys: let go of everything while the player has no control. */
	public static void suppress(MinecraftClient mc) {
		if (mc.player == null || !(Fx.locked || Fx.berserkTicks > 0)) {
			return;
		}
		mc.options.forwardKey.setPressed(false);
		mc.options.backKey.setPressed(false);
		mc.options.leftKey.setPressed(false);
		mc.options.rightKey.setPressed(false);
		mc.options.jumpKey.setPressed(false);
		mc.options.sneakKey.setPressed(false);
		mc.options.sprintKey.setPressed(false);
		mc.options.attackKey.setPressed(false);
		mc.options.useKey.setPressed(false);
	}

	public static void tick(MinecraftClient mc) {
		ClientPlayerEntity p = mc.player;
		if (p == null || mc.world == null || Fx.locked || Fx.berserkTicks <= 0 || mc.currentScreen != null || !p.isAlive()) {
			return;
		}
		LivingEntity t = pickTarget(mc, p);
		if (t == null) {
			return;
		}
		Vec3d to = t.getPos().subtract(p.getPos());
		double flat = Math.sqrt(to.x * to.x + to.z * to.z);
		// smooth turn toward the target (eye to chest)
		double dy = t.getY() + t.getHeight() * 0.6 - p.getEyeY();
		float wantYaw = (float) (MathHelper.atan2(to.z, to.x) * 180.0 / Math.PI) - 90f;
		float wantPitch = (float) -(MathHelper.atan2(dy, Math.max(0.01, flat)) * 180.0 / Math.PI);
		p.setYaw(p.getYaw() + MathHelper.clamp(MathHelper.wrapDegrees(wantYaw - p.getYaw()), -35f, 35f));
		p.setPitch(p.getPitch() + MathHelper.clamp(wantPitch - p.getPitch(), -20f, 20f));

		double reach = flat - t.getWidth() * 0.5;
		if (reach > 1.8) {
			double speed = p.isOnGround() ? 0.30 : 0.22;
			Vec3d v = p.getVelocity();
			p.setVelocity(to.x / flat * speed, v.y, to.z / flat * speed);
			p.setSprinting(true);
			if (p.horizontalCollision && p.isOnGround()) {
				p.jump();
			}
		}
		if (p.distanceTo(t) - t.getWidth() * 0.5 <= 3.1 && p.getAttackCooldownProgress(0f) >= 0.95f && mc.interactionManager != null) {
			mc.interactionManager.attackEntity(p, t);
			p.swingHand(Hand.MAIN_HAND);
		}
	}

	private static LivingEntity pickTarget(MinecraftClient mc, ClientPlayerEntity p) {
		String a = attacker;
		LivingEntity best = null;
		double bestD = RANGE * RANGE;
		for (Entity e : mc.world.getEntities()) {
			if (!(e instanceof LivingEntity le) || e == p || !le.isAlive() || e instanceof ArmorStandEntity) {
				continue;
			}
			if (le instanceof PlayerEntity pl && (pl.isCreative() || pl.isSpectator())) {
				continue;
			}
			if (!a.isEmpty() && e.getUuid().toString().equals(a)) {
				return le;
			}
			double d = e.squaredDistanceTo(p);
			if (d < bestD) {
				bestD = d;
				best = le;
			}
		}
		return best;
	}
}
