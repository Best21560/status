package com.orv.window;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class OrvWindowMod implements ModInitializer {
	/** client -> server: my window opened/closed. server -> other clients: (uuid, open). */
	public static final Identifier STATE = new Identifier("orvwindow", "state");
	/** client -> server: (skill id, add?) from an op. */
	public static final Identifier GRANT = new Identifier("orvwindow", "grant");
	/** server -> client: the full list of passives this player owns. */
	public static final Identifier SKILLS = new Identifier("orvwindow", "skills");

	private static final Set<UUID> OPEN = ConcurrentHashMap.newKeySet();

	@Override
	public void onInitialize() {
		ServerPlayNetworking.registerGlobalReceiver(STATE, (server, player, handler, buf, responseSender) -> {
			boolean open = buf.readBoolean();
			UUID id = player.getUuid();
			server.execute(() -> {
				if (open) {
					OPEN.add(id);
				} else {
					OPEN.remove(id);
				}
				broadcast(server, id, open);
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(GRANT, (server, player, handler, buf, responseSender) -> {
			String skill = buf.readString(64);
			boolean add = buf.readBoolean();
			server.execute(() -> {
				if (!player.hasPermissionLevel(2) || !Passives.exists(skill)) {
					return;
				}
				SkillState st = SkillState.get(server);
				boolean changed = add ? st.add(player.getUuid(), skill) : st.remove(player.getUuid(), skill);
				if (changed) {
					sendSkills(player, st);
				}
			});
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			for (UUID id : OPEN) {
				send(handler.player, id, true);
			}
			sendSkills(handler.player, SkillState.get(server));
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID id = handler.player.getUuid();
			if (OPEN.remove(id)) {
				broadcast(server, id, false);
			}
		});
	}

	private static void sendSkills(ServerPlayerEntity player, SkillState st) {
		Set<String> owned = st.skillsOf(player.getUuid());
		PacketByteBuf out = PacketByteBufs.create();
		out.writeVarInt(owned.size());
		for (String s : owned) {
			out.writeString(s);
		}
		ServerPlayNetworking.send(player, SKILLS, out);
	}

	private static void broadcast(MinecraftServer server, UUID who, boolean open) {
		for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
			if (p.getUuid().equals(who)) {
				continue;
			}
			send(p, who, open);
		}
	}

	private static void send(ServerPlayerEntity to, UUID who, boolean open) {
		PacketByteBuf out = PacketByteBufs.create();
		out.writeUuid(who);
		out.writeBoolean(open);
		ServerPlayNetworking.send(to, STATE, out);
	}
}
