package com.orv.window;

import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/** The passive skills. Text lives in the lang files: orvwindow.passive.<id> (description), orvwindow.effect.<id> (numbers). */
public final class Passives {
	/** tier: 1 = I (common) ... 4 = IV (legendary). Shop price depends on tier. */
	public record Def(String id, String name, String category, int tier) {
		public int cost() {
			return Passives.costOfTier(tier);
		}
	}

	/** Shop price (skill points) for tiers I, II, III, IV. */
	private static final int[] TIER_COST = {2, 4, 7, 12};

	public static int costOfTier(int tier) {
		int i = Math.max(1, Math.min(4, tier)) - 1;
		return TIER_COST[i];
	}

	/** Category keys, in tab order. Names live in the lang files: orvwindow.cat.<key> */
	public static final List<String> CATEGORIES = List.of("offense", "defense", "movement", "element", "energy", "recovery", "hunter");

	public static final List<Def> ALL = List.of(
			new Def("power_strike", "Power Strike", "offense", 1),
			new Def("heavy_impact", "Heavy Impact", "offense", 2),
			new Def("critical_instinct", "Critical Instinct", "offense", 2),
			new Def("fatal_blow", "Fatal Blow", "offense", 3),
			new Def("armor_breaker", "Armor Breaker", "offense", 2),
			new Def("executioner", "Executioner", "offense", 3),
			new Def("backstabber", "Backstabber", "offense", 2),
			new Def("combo_master", "Combo Master", "offense", 3),
			new Def("first_blood", "First Blood", "offense", 2),
			new Def("counter_attack", "Counter Attack", "offense", 3),
			new Def("piercing_force", "Piercing Force", "offense", 2),
			new Def("weapon_mastery", "Weapon Mastery", "offense", 1),
			new Def("berserker", "Berserker", "offense", 3),
			new Def("bloodlust", "Bloodlust", "offense", 3),
			new Def("unstoppable", "Unstoppable", "offense", 2),
			new Def("predator", "Predator", "offense", 3),
			new Def("ruthless", "Ruthless", "offense", 2),
			new Def("dual_wielder", "Dual Wielder", "offense", 3),
			new Def("sniper_instinct", "Sniper Instinct", "offense", 2),
			new Def("adrenaline_rush", "Adrenaline Rush", "offense", 3),
			new Def("iron_body", "Iron Body", "defense", 1),
			new Def("magic_barrier", "Magic Barrier", "defense", 2),
			new Def("thick_skin", "Thick Skin", "defense", 1),
			new Def("last_stand", "Last Stand", "defense", 2),
			new Def("second_chance", "Second Chance", "defense", 4),
			new Def("regeneration", "Regeneration", "defense", 2),
			new Def("toughness", "Toughness", "defense", 2),
			new Def("fortress", "Fortress", "defense", 2),
			new Def("evasive_instinct", "Evasive Instinct", "defense", 3),
			new Def("quick_reflex", "Quick Reflex", "defense", 2),
			new Def("deflection", "Deflection", "defense", 3),
			new Def("tenacity", "Tenacity", "defense", 2),
			new Def("unyielding", "Unyielding", "defense", 3),
			new Def("elemental_resistance", "Elemental Resistance", "defense", 2),
			new Def("death_ward", "Death Ward", "defense", 4),
			new Def("guardian_spirit", "Guardian Spirit", "defense", 3),
			new Def("pain_suppression", "Pain Suppression", "defense", 2),
			new Def("steadfast", "Steadfast", "defense", 2),
			new Def("reflective_armor", "Reflective Armor", "defense", 3),
			new Def("adaptation", "Adaptation", "defense", 3),
			new Def("swift_feet", "Swift Feet", "movement", 1),
			new Def("fleet_footed", "Fleet Footed", "movement", 2),
			new Def("double_jump", "Double Jump", "movement", 4),
			new Def("air_control", "Air Control", "movement", 1),
			new Def("lightweight", "Lightweight", "movement", 1),
			new Def("wall_runner", "Wall Runner", "movement", 4),
			new Def("silent_step", "Silent Step", "movement", 1),
			new Def("shadow_walker", "Shadow Walker", "movement", 2),
			new Def("dash_master", "Dash Master", "movement", 2),
			new Def("combat_roll", "Combat Roll", "movement", 2),
			new Def("acrobat", "Acrobat", "movement", 2),
			new Def("water_strider", "Water Strider", "movement", 1),
			new Def("climber", "Climber", "movement", 1),
			new Def("momentum", "Momentum", "movement", 3),
			new Def("hunters_pursuit", "Hunter's Pursuit", "movement", 2),
			new Def("escape_artist", "Escape Artist", "movement", 2),
			new Def("gravity_defier", "Gravity Defier", "movement", 4),
			new Def("swift_recovery", "Swift Recovery", "movement", 1),
			new Def("inferno_soul", "Inferno Soul", "element", 2),
			new Def("burning_blood", "Burning Blood", "element", 2),
			new Def("ashen_skin", "Ashen Skin", "element", 1),
			new Def("frozen_heart", "Frozen Heart", "element", 2),
			new Def("absolute_zero", "Absolute Zero", "element", 3),
			new Def("thunder_core", "Thunder Core", "element", 2),
			new Def("chain_lightning", "Chain Lightning", "element", 3),
			new Def("static_charge", "Static Charge", "element", 2),
			new Def("venom_blood", "Venom Blood", "element", 2),
			new Def("toxic_touch", "Toxic Touch", "element", 2),
			new Def("corrosion", "Corrosion", "element", 3),
			new Def("wind_soul", "Wind Soul", "element", 1),
			new Def("gale_force", "Gale Force", "element", 2),
			new Def("earth_heart", "Earth Heart", "element", 2),
			new Def("natures_blessing", "Nature's Blessing", "element", 2),
			new Def("holy_light", "Holy Light", "element", 2),
			new Def("dark_embrace", "Dark Embrace", "element", 2),
			new Def("void_resonance", "Void Resonance", "element", 2),
			new Def("elemental_harmony", "Elemental Harmony", "element", 3),
			new Def("mana_flow", "Mana Flow", "energy", 2),
			new Def("mana_pool", "Mana Pool", "energy", 2),
			new Def("blood_conversion", "Blood Conversion", "energy", 3),
			new Def("energy_absorption", "Energy Absorption", "energy", 2),
			new Def("stamina_mastery", "Stamina Mastery", "energy", 2),
			new Def("second_wind", "Second Wind", "energy", 3),
			new Def("conservation", "Conservation", "energy", 3),
			new Def("soul_collector", "Soul Collector", "energy", 2),
			new Def("soul_eater", "Soul Eater", "energy", 3),
			new Def("battle_focus", "Battle Focus", "energy", 2),
			new Def("overcharge", "Overcharge", "energy", 3),
			new Def("arcane_recovery", "Arcane Recovery", "energy", 2),
			new Def("vampirism", "Vampirism", "recovery", 3),
			new Def("blood_harvest", "Blood Harvest", "recovery", 2),
			new Def("soul_drain", "Soul Drain", "recovery", 3),
			new Def("rejuvenation", "Rejuvenation", "recovery", 2),
			new Def("combat_medic", "Combat Medic", "recovery", 1),
			new Def("desperate_recovery", "Desperate Recovery", "recovery", 2),
			new Def("life_bond", "Life Bond", "recovery", 2),
			new Def("overheal", "Overheal", "recovery", 3),
			new Def("blood_pact", "Blood Pact", "recovery", 3),
			new Def("natural_healing", "Natural Healing", "recovery", 2),
			new Def("purification", "Purification", "recovery", 3),
			new Def("vitality_surge", "Vitality Surge", "recovery", 3),
			new Def("mana_shield", "Mana Shield", "energy", 3),
			new Def("keen_eyes", "Keen Eyes", "hunter", 1),
			new Def("predators_instinct", "Predator's Instinct", "hunter", 2),
			new Def("monster_hunter", "Monster Hunter", "hunter", 2),
			new Def("beast_slayer", "Beast Slayer", "hunter", 2),
			new Def("weak_point_detection", "Weak Point Detection", "hunter", 3),
			new Def("ambusher", "Ambusher", "hunter", 2),
			new Def("shadow_meld", "Shadow Meld", "hunter", 2),
			new Def("silent_killer", "Silent Killer", "hunter", 1),
			new Def("marked_prey", "Marked Prey", "hunter", 2),
			new Def("blood_tracker", "Blood Tracker", "hunter", 2),
			new Def("trap_expert", "Trap Expert", "hunter", 2),
			new Def("survival_instinct", "Survival Instinct", "hunter", 2),
			new Def("hunters_trophy", "Hunter's Trophy", "hunter", 3),
			new Def("killing_rhythm", "Killing Rhythm", "hunter", 3),
			new Def("night_predator", "Night Predator", "hunter", 2)
	);

	/** Passives that work in the game now (see PassiveEngine). */
	private static final java.util.Set<String> ACTIVE = java.util.Set.of("power_strike", "heavy_impact", "critical_instinct", "fatal_blow", "armor_breaker", "executioner", "backstabber", "combo_master", "first_blood", "counter_attack", "piercing_force", "weapon_mastery", "berserker", "bloodlust", "unstoppable", "predator", "ruthless", "dual_wielder", "sniper_instinct", "adrenaline_rush", "iron_body", "magic_barrier", "thick_skin", "last_stand", "second_chance", "regeneration", "toughness", "fortress", "evasive_instinct", "quick_reflex", "deflection", "tenacity", "unyielding", "elemental_resistance", "death_ward", "guardian_spirit", "pain_suppression", "steadfast", "reflective_armor", "adaptation", "swift_feet", "fleet_footed", "double_jump", "air_control", "lightweight", "wall_runner", "silent_step", "shadow_walker", "dash_master", "combat_roll", "acrobat", "water_strider", "climber", "momentum", "hunters_pursuit", "escape_artist", "gravity_defier", "swift_recovery", "inferno_soul", "burning_blood", "ashen_skin", "frozen_heart", "absolute_zero", "thunder_core", "chain_lightning", "static_charge", "venom_blood", "toxic_touch", "corrosion", "wind_soul", "gale_force", "earth_heart", "natures_blessing", "holy_light", "dark_embrace", "void_resonance", "elemental_harmony", "mana_flow", "mana_pool", "blood_conversion", "energy_absorption", "stamina_mastery", "second_wind", "conservation", "soul_collector", "soul_eater", "battle_focus", "overcharge", "arcane_recovery", "vampirism", "blood_harvest", "soul_drain", "rejuvenation", "combat_medic", "desperate_recovery", "life_bond", "overheal", "blood_pact", "natural_healing", "purification", "vitality_surge", "mana_shield", "keen_eyes", "predators_instinct", "monster_hunter", "beast_slayer", "weak_point_detection", "ambusher", "shadow_meld", "silent_killer", "marked_prey", "blood_tracker", "trap_expert", "survival_instinct", "hunters_trophy", "killing_rhythm", "night_predator");

	/** Passives that work only in part (some of their effect is still missing). */
	private static final java.util.Set<String> PARTIAL = java.util.Set.of("silent_killer");

	/** 2 = works, 1 = works in part, 0 = not working yet (needs a system that does not exist yet). */
	public static int status(String id) {
		if (ACTIVE.contains(id)) {
			return 2;
		}
		return PARTIAL.contains(id) ? 1 : 0;
	}

	public static boolean exists(String id) {
		return get(id) != null;
	}

	public static Def get(String id) {
		for (Def d : ALL) {
			if (d.id().equals(id)) {
				return d;
			}
		}
		return null;
	}

	/** Tooltip: name, description, effect numbers, tier and price. */
	public static List<Text> tooltip(Def d) {
		String[] roman = {"I", "II", "III", "IV"};
		return List.of(
				Text.literal(d.name()).formatted(Formatting.AQUA),
				Text.translatable("orvwindow.passive." + d.id()),
				Text.translatable("orvwindow.tooltip.effect", Text.translatable("orvwindow.effect." + d.id())).formatted(Formatting.YELLOW),
				Text.translatable("orvwindow.tooltip.tier", roman[Math.max(1, Math.min(4, d.tier())) - 1], d.cost()).formatted(Formatting.GRAY),
				statusLine(d),
				Text.translatable("orvwindow.cat." + d.category()).formatted(Formatting.DARK_GRAY)
		);
	}

	private static Text statusLine(Def d) {
		int st = status(d.id());
		if (st == 2) {
			return Text.translatable("orvwindow.status.active").formatted(Formatting.GREEN);
		}
		if (st == 1) {
			return Text.translatable("orvwindow.status.partial").formatted(Formatting.GOLD);
		}
		return Text.translatable("orvwindow.status.pending").formatted(Formatting.RED);
	}

	private Passives() {
	}
}
