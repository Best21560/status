package com.orv.window;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class PlayerData {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("orvwindow.json");

	public static String name = "";
	public static int age = 20;
	public static int gender = 2; // 0 = ชาย, 1 = หญิง, 2 = ไม่ระบุ

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
}
