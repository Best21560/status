package com.orv.window;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsage;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;

/** A drinkable recovery potion; the higher the tier, the faster it heals (see Vitality). */
public class RecoveryPotionItem extends Item {
	private final int tier;

	public RecoveryPotionItem(int tier) {
		super(new Item.Settings().maxCount(16));
		this.tier = tier;
	}

	@Override
	public UseAction getUseAction(ItemStack stack) {
		return UseAction.DRINK;
	}

	@Override
	public int getMaxUseTime(ItemStack stack) {
		return 32;
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		return ItemUsage.consumeHeldItem(world, user, hand);
	}

	@Override
	public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
		if (user instanceof ServerPlayerEntity sp) {
			Vitality.drink(sp, tier);
		}
		if (user instanceof PlayerEntity pe && !pe.getAbilities().creativeMode) {
			stack.decrement(1);
			ItemStack bottle = new ItemStack(Items.GLASS_BOTTLE);
			if (stack.isEmpty()) {
				return bottle;
			}
			pe.getInventory().insertStack(bottle);
		}
		return stack;
	}
}
