package com.orv.window.mixin;

import com.orv.window.PassiveEngine;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hooks used by the passive skills (damage dealt/taken, healing, attack strength). */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

	/** Dodge / deflect: cancel the hit completely. */
	@Inject(method = "damage", at = @At("HEAD"), cancellable = true)
	private void orv$negate(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		if (PassiveEngine.shouldNegate((LivingEntity) (Object) this, source)) {
			cir.setReturnValue(false);
		}
	}

	/** Change the damage amount (attacker bonuses, defender reductions). */
	@ModifyVariable(method = "damage", at = @At("HEAD"), argsOnly = true)
	private float orv$modifyDamage(float amount, DamageSource source) {
		return PassiveEngine.modifyDamage((LivingEntity) (Object) this, source, amount);
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

	/** The attack cooldown is reset right before the hit; remember how charged it was. */
	@Inject(method = "resetLastAttackedTicks", at = @At("HEAD"))
	private void orv$beforeAttackReset(CallbackInfo ci) {
		PassiveEngine.beforeAttackReset((LivingEntity) (Object) this);
	}
}
