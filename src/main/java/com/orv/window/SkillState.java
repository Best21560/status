package com.orv.window;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Which passive skills each player owns. Saved with the world. */
public class SkillState extends PersistentState {
	private final Map<UUID, Set<String>> data = new HashMap<>();

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

	@Override
	public NbtCompound writeNbt(NbtCompound nbt) {
		NbtList players = new NbtList();
		for (Map.Entry<UUID, Set<String>> e : data.entrySet()) {
			NbtCompound p = new NbtCompound();
			p.putString("uuid", e.getKey().toString());
			NbtList list = new NbtList();
			for (String s : e.getValue()) {
				list.add(NbtString.of(s));
			}
			p.put("skills", list);
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
		}
		return st;
	}

	public static SkillState get(MinecraftServer server) {
		return server.getOverworld().getPersistentStateManager()
				.getOrCreate(SkillState::fromNbt, SkillState::new, "orvwindow_skills");
	}
}
