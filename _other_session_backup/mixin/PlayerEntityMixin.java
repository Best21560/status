package com.orv.window.mixin;

import com.orv.window.PassiveEngine;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {
	@ModifyVariable(method = "addExperience", at = @At("HEAD"), argsOnly = true)
	private int orvwindow$modifyXp(int experience) {
		PlayerEntity self = (PlayerEntity) (Object) this;
		if (self.getWorld().isClient) {
			return experience;
		}
		return PassiveEngine.modifyXp(self, experience);
	}
}
