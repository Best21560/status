package com.orv.window;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.Set;

/**
 * Client-side movement passives (the client moves the player, so the physics lives here):
 * Double Jump, Wall Runner, Air Control, Gravity Defier, Water Strider, Climber, Acrobat and the Dash / Roll.
 * The server only charges the Stamina and grants the short invulnerability of the dash.
 */
public final class MoveClient {
	private MoveClient() {
	}

	private static boolean wasGround = true;
	private static boolean lastJump = false;
	private static int airTicks = 0;
	private static boolean djUsed = false;
	private static boolean wallUsed = false;
	private static boolean wallActive = false;
	private static int wallLeft = 0;
	private static long clientTick = 0;
	private static long dashReady = 0;

	public static void tick(MinecraftClient mc) {
		ClientPlayerEntity p = mc.player;
		clientTick++;
		if (p == null) {
			return;
		}
		boolean jump = mc.options.jumpKey.isPressed();
		boolean ground = p.isOnGround();
		boolean blocked = p.isSpectator() || p.getAbilities().flying || p.isFallFlying() || p.hasVehicle() || mc.currentScreen != null;
		if (blocked) {
			wasGround = ground;
			lastJump = jump;
			wallActive = false;
			return;
		}
		Set<String> o = OrvWindowClient.mySkills;
		Vec3d v = p.getVelocity();
		boolean inWater = p.isTouchingWater() || p.isInLava();
		float fwd = p.input.movementForward;
		float side = p.input.movementSideways;
		boolean moving = fwd != 0f || side != 0f;

		if (ground) {
			djUsed = false;
			wallUsed = false;
			wallActive = false;
			airTicks = 0;
		} else {
			airTicks++;
		}

		// Acrobat: jump a little higher
		if (wasGround && !ground && v.y > 0.3 && o.contains("acrobat")) {
			v = new Vec3d(v.x, v.y * 1.08, v.z);
			p.setVelocity(v);
		}

		// Double Jump
		if (!ground && !inWater && !p.isClimbing() && o.contains("double_jump") && jump && !lastJump
				&& airTicks >= 3 && !djUsed && !OrvWindowClient.exhausted && OrvWindowClient.stamina >= 15f) {
			djUsed = true;
			v = new Vec3d(v.x, 0.38, v.z);
			p.setVelocity(v);
			p.fallDistance = 0f;
			OrvWindowClient.sendAction(8);
		}

		// Wall Runner: run into a wall in mid air and climb it for up to 3 seconds
		if (o.contains("wall_runner")) {
			boolean pushing = mc.options.forwardKey.isPressed() && p.horizontalCollision;
			if (!wallActive && !ground && !inWater && pushing && !wallUsed && !OrvWindowClient.exhausted
					&& OrvWindowClient.stamina >= 25f) {
				wallActive = true;
				wallUsed = true;
				wallLeft = 60;
				OrvWindowClient.sendAction(9);
			}
			if (wallActive) {
				if (pushing && wallLeft > 0 && !ground) {
					v = new Vec3d(v.x, 0.2, v.z);
					p.setVelocity(v);
					p.fallDistance = 0f;
					wallLeft--;
				} else {
					wallActive = false;
				}
			}
		}

		// Gravity Defier: fall 25% slower
		if (!ground && !inWater && !wallActive && v.y < -0.08 && o.contains("gravity_defier")) {
			v = new Vec3d(v.x, v.y + 0.02, v.z);
			p.setVelocity(v);
		}

		// Air Control: +30% steering in the air
		if (!ground && !inWater && moving && o.contains("air_control")) {
			double len = Math.sqrt(fwd * fwd + side * side);
			double add = 0.02 * 0.3 * (p.isSprinting() ? 1.3 : 1.0);
			double yaw = Math.toRadians(p.getYaw());
			double sn = Math.sin(yaw);
			double cs = Math.cos(yaw);
			double ix = side / len;
			double iz = fwd / len;
			v = new Vec3d(v.x + (ix * cs - iz * sn) * add, v.y, v.z + (iz * cs + ix * sn) * add);
			p.setVelocity(v);
		}

		// Water Strider: +25% speed in water
		if (p.isTouchingWater() && moving && o.contains("water_strider")) {
			v = new Vec3d(v.x * 1.04, v.y, v.z * 1.04);
			p.setVelocity(v);
		}

		// Climber: +30% climbing speed on ladders and vines
		if (p.isClimbing() && v.y > 0.1 && o.contains("climber")) {
			v = new Vec3d(v.x, Math.min(0.3, v.y * 1.3), v.z);
			p.setVelocity(v);
		}

		wasGround = ground;
		lastJump = jump;
	}

	/** Dash / Roll: a quick burst in the direction you are moving (forward if you stand still). */
	public static void dash(MinecraftClient mc) {
		ClientPlayerEntity p = mc.player;
		if (p == null || p.isSpectator() || p.isFallFlying() || p.hasVehicle()) {
			return;
		}
		Set<String> o = OrvWindowClient.mySkills;
		float cost = 20f * (o.contains("acrobat") ? 0.85f : 1f);
		if (clientTick < dashReady || OrvWindowClient.exhausted || OrvWindowClient.stamina < cost) {
			return;
		}
		float cd = 40f * (o.contains("dash_master") ? 0.8f : 1f);
		dashReady = clientTick + Math.round(cd);
		float fwd = p.input.movementForward;
		float side = p.input.movementSideways;
		if (fwd == 0f && side == 0f) {
			fwd = 1f;
		}
		double len = Math.sqrt(fwd * fwd + side * side);
		double yaw = Math.toRadians(p.getYaw());
		double sn = Math.sin(yaw);
		double cs = Math.cos(yaw);
		double ix = side / len;
		double iz = fwd / len;
		double speed = 0.95 * (o.contains("combat_roll") ? 1.2 : 1.0);
		Vec3d v = p.getVelocity();
		p.setVelocity(new Vec3d((ix * cs - iz * sn) * speed, p.isOnGround() ? 0.1 : v.y, (iz * cs + ix * sn) * speed));
		OrvWindowClient.sendAction(7);
	}
}
