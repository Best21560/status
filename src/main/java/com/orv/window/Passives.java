package com.orv.window;

import java.util.List;

/** The passive skills. Descriptions live in the lang files: orvwindow.passive.<id> */
public final class Passives {
	public record Def(String id, String name) {
	}

	public static final List<Def> ALL = List.of(
			new Def("power_strike", "Power Strike"),
			new Def("heavy_impact", "Heavy Impact"),
			new Def("critical_instinct", "Critical Instinct"),
			new Def("fatal_blow", "Fatal Blow"),
			new Def("armor_breaker", "Armor Breaker"),
			new Def("executioner", "Executioner"),
			new Def("backstabber", "Backstabber"),
			new Def("combo_master", "Combo Master"),
			new Def("first_blood", "First Blood"),
			new Def("counter_attack", "Counter Attack"),
			new Def("piercing_force", "Piercing Force"),
			new Def("weapon_mastery", "Weapon Mastery"),
			new Def("berserker", "Berserker"),
			new Def("bloodlust", "Bloodlust"),
			new Def("unstoppable", "Unstoppable"),
			new Def("predator", "Predator"),
			new Def("ruthless", "Ruthless"),
			new Def("dual_wielder", "Dual Wielder"),
			new Def("sniper_instinct", "Sniper Instinct"),
			new Def("adrenaline_rush", "Adrenaline Rush")
	);

	public static boolean exists(String id) {
		for (Def d : ALL) {
			if (d.id().equals(id)) {
				return true;
			}
		}
		return false;
	}

	private Passives() {
	}
}
