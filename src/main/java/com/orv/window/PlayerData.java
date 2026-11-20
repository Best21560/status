package com.orv.window;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class PlayerData {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("orvwindow.json");

	// ---- editable (saved) ----
	public static String name = "";
	// kept for later use (not shown in the window right now)
	public static int age = 20;
	public static int gender = 2;

	// ---- fixed values for now ----
	public static final int LEVEL = 1;
	public static final int EXP_PERCENT = 0;

	/** Row (0-based) of the "unique skill" line; the + button sits on this row. */
	public static final int UNIQUE_ROW = 4;
	/** Rows other players' (empty) windows are sized for. */
	public static final int BASE_ROWS = 6;
	/** At most this many skill names are listed in the window. */
	public static final int MAX_SKILL_LINES = 8;

	private static class Data {
		String name = "";
		int age = 20;
		int gender = 2;
	}

	public static void load() {
		try {
			if (Files.exists(FILE)) {
				Data d = GSON.fromJson(Files.readString(FILE), Data.class);
				if (d != null) {
					name = d.name == null ? "" : d.name;
					age = d.age;
					gender = d.gender;
				}
			}
		} catch (Exception e) {
			System.err.println("[orvwindow] load failed: " + e);
		}
	}

	public static void save() {
		Data d = new Data();
		d.name = name;
		d.age = age;
		d.gender = gender;
		try {
			Files.writeString(FILE, GSON.toJson(d));
		} catch (IOException e) {
			System.err.println("[orvwindow] save failed: " + e);
		}
	}

	/** Passives I own, in registry order. */
	public static List<Passives.Def> ownedDefs() {
		Set<String> owned = OrvWindowClient.mySkills;
		List<Passives.Def> have = new ArrayList<>();
		for (Passives.Def d : Passives.ALL) {
			if (owned.contains(d.id())) {
				have.add(d);
			}
		}
		return have;
	}

	/** How many owned passives are listed in the window. */
	public static int shownOwnedCount() {
		return Math.min(ownedDefs().size(), MAX_SKILL_LINES);
	}

	/** Name shown on the window (custom name, or the player's own name). */
	public static String displayName() {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (name.isEmpty() && mc.player != null) {
			return mc.player.getName().getString();
		}
		return name;
	}

	/** The lines shown on the window, top to bottom. */
	public static List<Text> lines() {
		List<Text> l = new ArrayList<>();
		l.add(Text.translatable("orvwindow.line.name", displayName()));
		l.add(Text.translatable("orvwindow.line.race"));
		String cid = OrvWindowClient.myClass;
		l.add(Text.translatable("orvwindow.line.class",
				cid.isEmpty() ? Text.literal("-") : Text.translatable("orvwindow.class." + cid)));
		l.add(Text.translatable("orvwindow.line.level", LEVEL, EXP_PERCENT));
		l.add(Text.translatable("orvwindow.line.unique")); // must stay at index UNIQUE_ROW

		List<Passives.Def> have = ownedDefs();
		if (have.isEmpty()) {
			l.add(Text.translatable("orvwindow.line.skills_none"));
		} else {
			l.add(Text.translatable("orvwindow.line.skills")); // skill names start at UNIQUE_ROW + 2
			int shownCount = Math.min(have.size(), MAX_SKILL_LINES);
			for (int i = 0; i < shownCount; i++) {
				l.add(Text.literal(have.get(i).name()));
			}
			if (have.size() > shownCount) {
				l.add(Text.translatable("orvwindow.line.more", have.size() - shownCount));
			}
		}
		return l;
	}
}
