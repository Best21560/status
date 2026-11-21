package com.orv.window.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Removes the vanilla hearts: HP is shown by the player bar at the top left instead. */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
	@Inject(method = "renderHealthBar", at = @At("HEAD"), cancellable = true)
	private void orv$hideHearts(DrawContext context, PlayerEntity player, int x, int y, int lines, int regeneratingHeartIndex,
								float maxHealth, int lastHealth, int health, int absorption, boolean blinking, CallbackInfo ci) {
		ci.cancel();
	}

	/** There is no hunger in this mod, so the whole vanilla status row goes (armor and air are redrawn by VitalsHud). */
	@Inject(method = "renderStatusBars", at = @At("HEAD"), cancellable = true)
	private void orv$hideStatusBars(DrawContext context, CallbackInfo ci) {
		com.orv.window.VitalsHud.statusBarsHidden = true;
		ci.cancel();
	}

	/** The vanilla experience bar and level number are replaced by the mod Level (shown in the status window). */
	@Inject(method = "renderExperienceBar", at = @At("HEAD"), cancellable = true)
	private void orv$hideXpBar(DrawContext context, int x, CallbackInfo ci) {
		ci.cancel();
	}
}
