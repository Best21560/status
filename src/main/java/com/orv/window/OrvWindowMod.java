package com.orv.window;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class OrvWindowMod implements ModInitializer {
	/** client -> server: my window opened/closed. server -> other clients: (uuid, open). */
	public static final Identifier STATE = new Identifier("orvwindow", "state");
	/** client -> server: (skill id, add?) from an op. */
	public static final Identifier GRANT = new Identifier("orvwindow", "grant");
	/** client -> server: buy a passive in the shop with skill points. */
	public static final Identifier BUY = new Identifier("orvwindow", "buy");
	/** server -> client: the passives this player owns + skill points. */
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

		ServerPlayNetworking.registerGlobalReceiver(BUY, (server, player, handler, buf, responseSender) -> {
			String skill = buf.readString(64);
			server.execute(() -> {
				Passives.Def def = Passives.get(skill);
				if (def == null) {
					return;
				}
				SkillState st = SkillState.get(server);
				UUID id = player.getUuid();
				if (st.skillsOf(id).contains(skill)) {
					return;
				}
				int cost = def.cost();
				if (st.pointsOf(id) < cost) {
					player.sendMessage(Text.translatable("orvwindow.shop.no_points"), true);
					return;
				}
				st.addPoints(id, -cost);
				st.add(id, skill);
				sendSkills(player, st);
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

		// /skillpoints add <players> <amount>   /skillpoints set <players> <amount>   (ops only)
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(CommandManager.literal("skillpoints")
						.requires(src -> src.hasPermissionLevel(2))
						.then(CommandManager.literal("add")
								.then(CommandManager.argument("players", EntityArgumentType.players())
										.then(CommandManager.argument("amount", IntegerArgumentType.integer(-100000, 100000))
												.executes(ctx -> changePoints(ctx, false)))))
						.then(CommandManager.literal("set")
								.then(CommandManager.argument("players", EntityArgumentType.players())
										.then(CommandManager.argument("amount", IntegerArgumentType.integer(0, 100000))
												.executes(ctx -> changePoints(ctx, true)))))));
	}

	private static int changePoints(CommandContext<ServerCommandSource> ctx, boolean set) throws CommandSyntaxException {
		Collection<ServerPlayerEntity> targets = EntityArgumentType.getPlayers(ctx, "players");
		int amount = IntegerArgumentType.getInteger(ctx, "amount");
		SkillState st = SkillState.get(ctx.getSource().getServer());
		for (ServerPlayerEntity p : targets) {
			if (set) {
				st.setPoints(p.getUuid(), amount);
			} else {
				st.addPoints(p.getUuid(), amount);
			}
			sendSkills(p, st);
		}
		int count = targets.size();
		ctx.getSource().sendFeedback(() -> Text.translatable(
				set ? "orvwindow.cmd.points_set" : "orvwindow.cmd.points_added", count, amount), true);
		return count;
	}

	private static void sendSkills(ServerPlayerEntity player, SkillState st) {
		Set<String> owned = st.skillsOf(player.getUuid());
		PacketByteBuf out = PacketByteBufs.create();
		out.writeVarInt(owned.size());
		for (String s : owned) {
			out.writeString(s);
		}
		out.writeVarInt(st.pointsOf(player.getUuid()));
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
