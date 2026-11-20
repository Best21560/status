package com.orv.window;

import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Optional link to Iron's Spells 'n Spellbooks: when that mod is present its Mana is the one real Mana pool
 * (our Mana bar shows and spends the same value). Everything goes through reflection and is wrapped so that a
 * missing or changed mod simply turns the link off instead of crashing.
 */
public final class IronsLink {
	private IronsLink() {
	}

	private static boolean tried = false;
	private static boolean ok = false;
	private static Method getData;
	private static Method getMana;
	private static Method setMana;
	private static Supplier<?> maxManaAttr;

	private static void init() {
		if (tried) {
			return;
		}
		tried = true;
		try {
			Class<?> md = Class.forName("io.redspace.ironsspellbooks.api.magic.MagicData");
			getData = md.getMethod("getPlayerMagicData", net.minecraft.entity.LivingEntity.class);
			getMana = md.getMethod("getMana");
			setMana = md.getMethod("setMana", float.class);
			Class<?> ar = Class.forName("io.redspace.ironsspellbooks.api.registry.AttributeRegistry");
			Object reg = ar.getField("MAX_MANA").get(null);
			Method get = reg.getClass().getMethod("get");
			maxManaAttr = () -> {
				try {
					return get.invoke(reg);
				} catch (Throwable t) {
					return null;
				}
			};
			ok = true;
		} catch (Throwable t) {
			ok = false;
		}
	}

	public static boolean available() {
		init();
		return ok;
	}

	/** Iron's current Mana, or -1 if the link is off. */
	public static float mana(ServerPlayerEntity p) {
		if (!available()) {
			return -1f;
		}
		try {
			Object data = getData.invoke(null, p);
			return data == null ? -1f : ((Number) getMana.invoke(data)).floatValue();
		} catch (Throwable t) {
			ok = false;
			return -1f;
		}
	}

	public static void setMana(ServerPlayerEntity p, float v) {
		if (!ok) {
			return;
		}
		try {
			Object data = getData.invoke(null, p);
			if (data != null) {
				setMana.invoke(data, v);
			}
		} catch (Throwable t) {
			ok = false;
		}
	}

	private static final java.util.UUID BONUS_ID = java.util.UUID.fromString("5b1f0c3a-0000-4000-8000-0000000000b1");

	/**
	 * Makes Iron's maximum Mana equal to the class value (Iron's own base is 100): adds or removes a modifier
	 * on its max-Mana attribute so that base + bonus = target. Items and effects from Iron's still stack on top.
	 */
	public static void setClassMax(ServerPlayerEntity p, double target) {
		if (!ok) {
			return;
		}
		try {
			Object a = maxManaAttr.get();
			if (a == null) {
				return;
			}
			EntityAttributeInstance inst = p.getAttributeInstance((EntityAttribute) a);
			if (inst == null) {
				return;
			}
			double bonus = target - inst.getBaseValue();
			net.minecraft.entity.attribute.EntityAttributeModifier old = inst.getModifier(BONUS_ID);
			if (old != null && Math.abs(old.getValue() - bonus) < 1e-6) {
				return;
			}
			if (old != null) {
				inst.removeModifier(BONUS_ID);
			}
			if (Math.abs(bonus) > 1e-6) {
				inst.addTemporaryModifier(new net.minecraft.entity.attribute.EntityAttributeModifier(BONUS_ID, "orv_class_mana", bonus,
						net.minecraft.entity.attribute.EntityAttributeModifier.Operation.ADDITION));
			}
		} catch (Throwable t) {
			// ignore: the link just keeps Iron's own maximum
		}
	}

	/** Iron's maximum Mana of the player, or -1 if unknown. */
	public static float maxMana(ServerPlayerEntity p) {
		if (!ok) {
			return -1f;
		}
		try {
			Object a = maxManaAttr.get();
			if (a == null) {
				return -1f;
			}
			EntityAttributeInstance inst = p.getAttributeInstance((EntityAttribute) a);
			return inst == null ? -1f : (float) inst.getValue();
		} catch (Throwable t) {
			ok = false;
			return -1f;
		}
	}
}
