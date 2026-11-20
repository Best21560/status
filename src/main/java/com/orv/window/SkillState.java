package com.orv.window;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Passive skills and skill points of each player. Saved with the world. */
public class SkillState extends PersistentState {
	private static final int MAX_POINTS = 1_000_000;

	private final Map<UUID, Set<String>> data = new HashMap<>();
	private final Map<UUID, Integer> points = new HashMap<>();
	private final Map<UUID, String> classes = new HashMap<>();

	/** Chosen class id, or "" if the player has not chosen yet. */
	public String classOf(UUID id) {
		return classes.getOrDefault(id, "");
	}

	public void setClass(UUID id, String cls) {
		if (cls == null || cls.isEmpty()) {
			classes.remove(id);
		} else {
			classes.put(id, cls);
		}
		markDirty();
	}

	public Set<String> skillsOf(UUID id) {
		return data.getOrDefault(id, Set.of());
	}

	public boolean add(UUID id, String skill) {
		boolean changed = data.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(skill);
		if (changed) {
			markDirty();
		}
		return changed;
	}

	public boolean remove(UUID id, String skill) {
		Set<String> set = data.get(id);
		boolean changed = set != null && set.remove(skill);
		if (changed) {
			markDirty();
		}
		return changed;
	}

	public int pointsOf(UUID id) {
		return points.getOrDefault(id, 0);
	}

	public void setPoints(UUID id, int value) {
		points.put(id, Math.max(0, Math.min(MAX_POINTS, value)));
		markDirty();
	}

	public void addPoints(UUID id, int delta) {
		setPoints(id, (int) Math.max(0L, Math.min((long) MAX_POINTS, (long) pointsOf(id) + delta)));
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		Set<UUID> everyone = new HashSet<>(data.keySet());
		everyone.addAll(points.keySet());
		everyone.addAll(classes.keySet());
		NbtList players = new NbtList();
		for (UUID id : everyone) {
			NbtCompound p = new NbtCompound();
			p.putString("uuid", id.toString());
			NbtList list = new NbtList();
			for (String s : skillsOf(id)) {
				list.add(NbtString.of(s));
			}
			p.put("skills", list);
			p.putInt("points", pointsOf(id));
			p.putString("class", classOf(id));
			players.add(p);
		}
		nbt.put("players", players);
		return nbt;
	}

	public static SkillState fromNbt(NbtCompound nbt) {
		SkillState st = new SkillState();
		NbtList players = nbt.getList("players", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < players.size(); i++) {
			NbtCompound p = players.getCompound(i);
			UUID id = UUID.fromString(p.getString("uuid"));
			NbtList list = p.getList("skills", NbtElement.STRING_TYPE);
			Set<String> set = new LinkedHashSet<>();
			for (int j = 0; j < list.size(); j++) {
				set.add(list.getString(j));
			}
			st.data.put(id, set);
			st.points.put(id, p.getInt("points"));
			String cls = p.getString("class");
			if (!cls.isEmpty()) {
				st.classes.put(id, cls);
			}
		}
		return st;
	}

	public static SkillState get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager()
				.getOrCreate(SkillState::fromNbt, SkillState::new, "orvwindow_skills");
	}
}
