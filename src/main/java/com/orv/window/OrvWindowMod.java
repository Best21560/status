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

/** Common side: relays "window open/closed" so other players can see the (empty) window. */
public class OrvWindowMod implements ModInitializer {
	public static final Identifier STATE = new Identifier("orvwindow", "state");

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

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			for (UUID id : OPEN) {
				send(handler.player, id, true);
			}
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID id = handler.player.getUuid();
			if (OPEN.remove(id)) {
				broadcast(server, id, false);
			}
		});
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
