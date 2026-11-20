package com.orv.window;

import java.util.List;

/** The starting classes. Each one has its own base stats (applied on top of the vanilla player). */
public final class Classes {
	private Classes() {
	}

	/**
	 * hp / armor / attack: added to the vanilla value. speed / attackSpeed: fraction (0.05 = +5%).
	 * mana / stamina: maximum values (vanilla-like default is 100).
	 */
	public record Def(String id, double hp, double armor, double attack, double speed, double attackSpeed,
					  float mana, float stamina) {
	}

	public static final List<Def> ALL = List.of(
			// light and shadow trickster: lots of Mana, a bit fragile
			new Def("lightbender", -2.0, 0.0, 0.0, 0.03, 0.0, 140f, 100f),
			// fast and light, lots of Stamina, little Mana
			new Def("rogue", -2.0, 0.0, 0.0, 0.08, 0.10, 70f, 130f),
			// martial artist who trains body and inner energy: balanced
			new Def("wuxia", 0.0, 0.0, 0.5, 0.04, 0.05, 120f, 120f),
			// heavy hunter: tough and hard hitting, slow, little Mana
			new Def("slayer", 6.0, 2.0, 1.0, -0.02, 0.0, 60f, 100f)
	);

	public static Def get(String id) {
		for (Def d : ALL) {
			if (d.id().equals(id)) {
				return d;
			}
		}
		return null;
	}

	public static boolean exists(String id) {
		return get(id) != null;
	}

	public static float mana(String id) {
		Def d = get(id);
		return d == null ? 100f : d.mana();
	}

	public static float stamina(String id) {
		Def d = get(id);
		return d == null ? 100f : d.stamina();
	}
}
