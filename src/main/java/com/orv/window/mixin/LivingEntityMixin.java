package com.orv.window.mixin;

import com.orv.window.PassiveEngine;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import com.orv.window.Extras;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hooks used by the passive skills. Only plain, standard injections are used on purpose. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

	/**
	 * Dodge / deflect (cancel the hit) and damage changes. The damage change is done by calling damage()
	 * again with the new amount; PassiveEngine.inDamage stops the second call from being changed again.
	 */
	@Inject(method = "damage", at = @At("HEAD"), cancellable = true)
	private void orv$onDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		if (PassiveEngine.inDamage) {
			return;
		}
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.getWorld().isClient) {
			return;
		}
		if (PassiveEngine.shouldNegate(self, source)) {
			cir.setReturnValue(false);
			return;
		}
		float changed = PassiveEngine.modifyDamage(self, source, amount);
		if (Math.abs(changed - amount) > 1.0E-4f) {
			PassiveEngine.inDamage = true;
			try {
				cir.setReturnValue(self.damage(source, changed));
			} finally {
				PassiveEngine.inDamage = false;
			}
		}
	}

	/** After a hit landed: lifesteal, combo, procs, reflect... */
	@Inject(method = "damage", at = @At("RETURN"))
	private void orv$afterDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) {
			PassiveEngine.afterDamage((LivingEntity) (Object) this, source, amount);
		}
	}

	/** Healing amount changes. */
	@ModifyVariable(method = "heal", at = @At("HEAD"), argsOnly = true)
	private float orv$modifyHeal(float amount) {
		return PassiveEngine.modifyHeal((LivingEntity) (Object) this, amount);
	}

	/** Silent Step / Shadow Meld: monsters notice you from a shorter distance. */
	@Inject(method = "getAttackDistanceScalingFactor", at = @At("RETURN"), cancellable = true)
	private void orv$stealth(Entity observer, CallbackInfoReturnable<Double> cir) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self.getWorld().isClient) {
			return;
		}
		double f = Extras.stealthFactor(self);
		if (f < 1.0) {
			cir.setReturnValue(cir.getReturnValue() * f);
		}
	}
}
