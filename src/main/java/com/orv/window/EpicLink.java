package com.orv.window;

import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Method;

/**
 * Optional link to Epic Fight: when it is installed, the Slayer skills play Epic Fight's own great sword animations
 * on the player, so the body really moves like a sword fighter. Reflection only; if Epic Fight is missing or its
 * API changed, nothing happens (the skills still work, with our own effects only).
 */
public final class EpicLink {
	private EpicLink() {
	}

	private static boolean tried = false;
	private static boolean ok = false;
	private static Class<?> animations;
	private static Class<?> playerPatchClass;
	private static Method getPatch;
	private static Method playSync;

	private static void init() {
		if (tried) {
			return;
		}
		tried = true;
		try {
			animations = Class.forName("yesman.epicfight.gameasset.Animations");
			playerPatchClass = Class.forName("yesman.epicfight.world.capabilities.entitypatch.player.ServerPlayerPatch");
			Class<?> caps = Class.forName("yesman.epicfight.world.capabilities.EpicFightCapabilities");
			getPatch = caps.getMethod("getEntityPatch", net.minecraft.entity.Entity.class, Class.class);
			for (Method m : playerPatchClass.getMethods()) {
				if (m.getName().equals("playAnimationSynchronized") && m.getParameterCount() == 2
						&& m.getParameterTypes()[1] == float.class) {
					playSync = m;
					break;
				}
			}
			ok = playSync != null;
		} catch (Throwable t) {
			ok = false;
		}
	}

	/** Play one of Epic Fight's built-in animations (field name in its Animations class) on the player. */
	public static void play(ServerPlayerEntity p, String animation) {
		init();
		if (!ok) {
			return;
		}
		try {
			Object patch = getPatch.invoke(null, p, playerPatchClass);
			if (patch == null) {
				return;
			}
			Object anim = animations.getField(animation).get(null);
			if (anim == null) {
				return;
			}
			playSync.invoke(patch, anim, 0.05f);
		} catch (Throwable t) {
			// unsupported version or missing animation: just skip
		}
	}
}
