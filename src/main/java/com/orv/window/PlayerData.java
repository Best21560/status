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

public class PlayerData {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("orvwindow.json");

	// ---- editable (saved) ----
	public static String name = "";
	// kept for later use (not shown in the window right now)
	public static int age = 20;
	public static int gender = 2;

	// ---- fixed values for now (change here later) ----
	public static final int LEVEL = 1;
	public static final int EXP_PERCENT = 0;
	public static final int ACHIEVEMENTS = 0;
	public static final int HP = 1;
	public static final int MP = 1;
	public static final int STR = 1;
	public static final int DEX = 1;
	public static final int INT = 1;
	public static final int CON = 1;
	public static final int WILL = 1;
	public static final int POINTS = 0;

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

	/** The lines shown on the window, top to bottom. */
	public static List<Text> lines() {
		String shown = name;
		MinecraftClient mc = MinecraftClient.getInstance();
		if (shown.isEmpty() && mc.player != null) {
			shown = mc.player.getName().getString();
		}
		List<Text> l = new ArrayList<>();
		l.add(Text.translatable("orvwindow.line.name", shown));
		l.add(Text.translatable("orvwindow.line.race"));
		l.add(Text.translatable("orvwindow.line.class"));
		l.add(Text.translatable("orvwindow.line.level", LEVEL, EXP_PERCENT));
		l.add(Text.translatable("orvwindow.line.title"));
		l.add(Text.translatable("orvwindow.line.achievements", ACHIEVEMENTS));
		l.add(Text.translatable("orvwindow.line.hpmp", HP, MP));
		l.add(Text.translatable("orvwindow.line.str_dex", STR, DEX));
		l.add(Text.translatable("orvwindow.line.int_con", INT, CON));
		l.add(Text.translatable("orvwindow.line.will", WILL));
		l.add(Text.translatable("orvwindow.line.points", POINTS));
		return l;
	}
}
