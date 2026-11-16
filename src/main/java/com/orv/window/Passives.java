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
	public static final List<String> CATEGORIES = List.of("offense", "defense", "movement", "element", "energy", "recovery", "magic", "hunter", "explore", "growth");

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
			new Def("glacial_armor", "Glacial Armor", "element", 2),
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
			new Def("arcane_efficiency", "Arcane Efficiency", "energy", 2),
			new Def("blood_conversion", "Blood Conversion", "energy", 3),
			new Def("energy_absorption", "Energy Absorption", "energy", 2),
			new Def("stamina_mastery", "Stamina Mastery", "energy", 2),
			new Def("second_wind", "Second Wind", "energy", 3),
			new Def("overflow", "Overflow", "energy", 3),
			new Def("conservation", "Conservation", "energy", 3),
			new Def("soul_collector", "Soul Collector", "energy", 2),
			new Def("soul_eater", "Soul Eater", "energy", 3),
			new Def("battle_focus", "Battle Focus", "energy", 2),
			new Def("overcharge", "Overcharge", "energy", 3),
			new Def("efficient_casting", "Efficient Casting", "energy", 2),
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
			new Def("arcane_mastery", "Arcane Mastery", "magic", 2),
			new Def("spell_amplifier", "Spell Amplifier", "magic", 3),
			new Def("spell_echo", "Spell Echo", "magic", 4),
			new Def("quick_incantation", "Quick Incantation", "magic", 2),
			new Def("mystic_sight", "Mystic Sight", "magic", 1),
			new Def("rune_master", "Rune Master", "magic", 2),
			new Def("spell_weaver", "Spell Weaver", "magic", 3),
			new Def("mana_shield", "Mana Shield", "magic", 3),
			new Def("magic_penetration", "Magic Penetration", "magic", 2),
			new Def("forbidden_knowledge", "Forbidden Knowledge", "magic", 4),
			new Def("mystic_resonance", "Mystic Resonance", "magic", 2),
			new Def("spell_absorption", "Spell Absorption", "magic", 3),
			new Def("dual_casting", "Dual Casting", "magic", 3),
			new Def("ancient_bloodline", "Ancient Bloodline", "magic", 3),
			new Def("mana_manipulation", "Mana Manipulation", "magic", 2),
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
			new Def("night_predator", "Night Predator", "hunter", 2),
			new Def("treasure_hunter", "Treasure Hunter", "explore", 2),
			new Def("lucky_finder", "Lucky Finder", "explore", 3),
			new Def("scavenger", "Scavenger", "explore", 2),
			new Def("forager", "Forager", "explore", 1),
			new Def("miners_instinct", "Miner's Instinct", "explore", 2),
			new Def("appraiser", "Appraiser", "explore", 1),
			new Def("cartographer", "Cartographer", "explore", 1),
			new Def("deep_diver", "Deep Diver", "explore", 2),
			new Def("desert_walker", "Desert Walker", "explore", 1),
			new Def("cold_resistance", "Cold Resistance", "explore", 1),
			new Def("survivor", "Survivor", "explore", 2),
			new Def("master_chef", "Master Chef", "explore", 2),
			new Def("resourceful", "Resourceful", "explore", 2),
			new Def("ancient_sense", "Ancient Sense", "explore", 3),
			new Def("treasure_instinct", "Treasure Instinct", "explore", 3),
			new Def("fast_learner", "Fast Learner", "growth", 2),
			new Def("combat_experience", "Combat Experience", "growth", 2),
			new Def("skill_prodigy", "Skill Prodigy", "growth", 2),
			new Def("growth_potential", "Growth Potential", "growth", 3),
			new Def("weapon_scholar", "Weapon Scholar", "growth", 2),
			new Def("adaptive_learning", "Adaptive Learning", "growth", 3),
			new Def("skill_reduction", "Skill Reduction", "growth", 3),
			new Def("mastery_seeker", "Mastery Seeker", "growth", 2),
			new Def("awakening_potential", "Awakening Potential", "growth", 4),
			new Def("limit_breaker", "Limit Breaker", "growth", 4),
			new Def("evolution", "Evolution", "growth", 4),
			new Def("perfectionist", "Perfectionist", "growth", 3)
	);

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
				Text.translatable("orvwindow.cat." + d.category()).formatted(Formatting.DARK_GRAY)
		);
	}

	private Passives() {
	}
}
