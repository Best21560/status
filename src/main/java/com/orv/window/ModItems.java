package com.orv.window;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/** The four recovery potions (tier 1 to 4). */
public final class ModItems {
	private ModItems() {
	}

	public static final Item[] POTIONS = new Item[4];

	public static void register() {
		for (int i = 0; i < 4; i++) {
			POTIONS[i] = Registry.register(Registries.ITEM, new Identifier("orvwindow", "recovery_potion_" + (i + 1)),
					new RecoveryPotionItem(i + 1));
		}
	}
}
