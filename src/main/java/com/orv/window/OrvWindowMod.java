package com.orv.window;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.command.CommandSource;
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
	/** server -> client: level, xp, xp needed, stat points, 5 stats. */
	public static final Identifier LEVEL = new Identifier("orvwindow", "level");
	/** client -> server: spend one stat point (stat index 0-4). */
	public static final Identifier STAT_UP = new Identifier("orvwindow", "stat_up");
	/** server -> client: the passives this player owns. */
	public static final Identifier SKILLS = new Identifier("orvwindow", "skills");
	/** server -> client: Mana, max Mana, Stamina, max Stamina, exhausted. */
	public static final Identifier VITALS = new Identifier("orvwindow", "vitals");
	/** client -> server: a key was pressed (0 = Blood Conversion, 1 = Mana Shield toggle). */
	/** server -> client: the chosen class id ("" = not chosen yet, the client opens the class screen). */
	public static final Identifier CLASS = new Identifier("orvwindow", "class");
	/** client -> server: the class the player picked. */
	public static final Identifier CLASS_PICK = new Identifier("orvwindow", "class_pick");
	/** client -> server: a key was pressed (see PassiveEngine.action / Extras.action). */
	/** server -> client: a visual effect (see FxNet / Fx). */
	public static final Identifier FX = new Identifier("orvwindow", "fx");
	/** Lightbender state of one caster (orbs, halo, heal %, cooldowns), sent to the players around. */
	public static final Identifier LIGHT = new Identifier("orvwindow", "light");
	public static final Identifier ACTION = new Identifier("orvwindow", "action");

	private static final Set<UUID> OPEN = ConcurrentHashMap.newKeySet();

	@Override
	public void onInitialize() {
		PassiveEngine.init();
		ModItems.register();

		ServerPlayNetworking.registerGlobalReceiver(ACTION, (server, player, handler, buf, responseSender) -> {
			int id = buf.readVarInt();
			server.execute(() -> PassiveEngine.action(player, id));
		});

		ServerPlayNetworking.registerGlobalReceiver(CLASS_PICK, (server, player, handler, buf, responseSender) -> {
			String cls = buf.readString(32);
			server.execute(() -> {
				SkillState st = SkillState.get(server);
				// a class can only be picked once (ops can change it with /orvclass)
				if (!Classes.exists(cls) || !st.classOf(player.getUuid()).isEmpty()) {
					return;
				}
				st.setClass(player.getUuid(), cls);
				PassiveEngine.setClass(player.getUuid(), cls, true);
				sendClass(player, cls);
			});
		});

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

		ServerPlayNetworking.registerGlobalReceiver(STAT_UP, (server, player, handler, buf, responseSender) -> {
			int idx = buf.readVarInt();
			server.execute(() -> LevelEngine.spend(player, PassiveEngine.res(player.getUuid()), idx));
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			for (UUID id : OPEN) {
				send(handler.player, id, true);
			}
			SkillState st = SkillState.get(server);
			PassiveEngine.res(handler.player.getUuid()).lvLoaded = false; // level data is re-sent by LevelEngine.tick
			sendSkills(handler.player, st);
			String cls = st.classOf(handler.player.getUuid());
			PassiveEngine.setClass(handler.player.getUuid(), cls, false);
			sendClass(handler.player, cls);
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UUID id = handler.player.getUuid();
			SlayerEngine.onLeave(handler.player);
			LightEngine.onLeave(handler.player);
			PassiveEngine.clear(id);
			if (OPEN.remove(id)) {
				broadcast(server, id, false);
			}
		});

		// /orvpotion <tier 1-4>   gives yourself a recovery potion (ops only)
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(CommandManager.literal("orvpotion")
						.requires(src -> src.hasPermissionLevel(2))
						.then(CommandManager.argument("tier", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 4))
								.executes(ctx -> {
									int tier = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "tier");
									ctx.getSource().getPlayerOrThrow().giveItemStack(new net.minecraft.item.ItemStack(ModItems.POTIONS[tier - 1], 4));
									return 1;
								}))));

		// /orvclass set <players> <class>   /orvclass reset <players>   (ops only)
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(CommandManager.literal("orvclass")
						.requires(src -> src.hasPermissionLevel(2))
						.then(CommandManager.literal("set")
								.then(CommandManager.argument("players", EntityArgumentType.players())
										.then(CommandManager.argument("class", StringArgumentType.word())
												.suggests((c, b) -> CommandSource.suggestMatching(
														Classes.ALL.stream().map(Classes.Def::id).toList(), b))
												.executes(ctx -> changeClass(ctx, StringArgumentType.getString(ctx, "class"))))))
						.then(CommandManager.literal("reset")
								.then(CommandManager.argument("players", EntityArgumentType.players())
										.executes(ctx -> changeClass(ctx, ""))))));

		// /orvlevel set <players> <level>   /orvlevel addxp <players> <amount>   (ops only)
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(CommandManager.literal("orvlevel")
						.requires(src -> src.hasPermissionLevel(2))
						.then(CommandManager.literal("set")
								.then(CommandManager.argument("players", EntityArgumentType.players())
										.then(CommandManager.argument("level", IntegerArgumentType.integer(1, LevelEngine.MAX_LEVEL))
												.executes(ctx -> {
													int lvl = IntegerArgumentType.getInteger(ctx, "level");
													Collection<ServerPlayerEntity> t = EntityArgumentType.getPlayers(ctx, "players");
													for (ServerPlayerEntity p : t) {
														LevelEngine.setLevel(p, PassiveEngine.res(p.getUuid()), lvl);
													}
													ctx.getSource().sendFeedback(() -> Text.translatable("orvwindow.cmd.level_set", t.size(), lvl), true);
													return t.size();
												}))))
						.then(CommandManager.literal("addxp")
								.then(CommandManager.argument("players", EntityArgumentType.players())
										.then(CommandManager.argument("amount", IntegerArgumentType.integer(1, 1000000))
												.executes(ctx -> {
													int amt = IntegerArgumentType.getInteger(ctx, "amount");
													Collection<ServerPlayerEntity> t = EntityArgumentType.getPlayers(ctx, "players");
													for (ServerPlayerEntity p : t) {
														LevelEngine.addXp(p, PassiveEngine.res(p.getUuid()), amt);
													}
													ctx.getSource().sendFeedback(() -> Text.translatable("orvwindow.cmd.xp_added", t.size(), amt), true);
													return t.size();
												}))))));
	}

	private static int changeClass(CommandContext<ServerCommandSource> ctx, String cls) throws CommandSyntaxException {
		if (!cls.isEmpty() && !Classes.exists(cls)) {
			ctx.getSource().sendError(Text.translatable("orvwindow.cmd.class_unknown", cls));
			return 0;
		}
		Collection<ServerPlayerEntity> targets = EntityArgumentType.getPlayers(ctx, "players");
		SkillState st = SkillState.get(ctx.getSource().getServer());
		for (ServerPlayerEntity p : targets) {
			st.setClass(p.getUuid(), cls);
			PassiveEngine.setClass(p.getUuid(), cls, !cls.isEmpty());
			sendClass(p, cls);
		}
		int count = targets.size();
		ctx.getSource().sendFeedback(() -> Text.translatable("orvwindow.cmd.class_changed", count), true);
		return count;
	}

	private static void sendClass(ServerPlayerEntity player, String cls) {
		PacketByteBuf out = PacketByteBufs.create();
		out.writeString(cls);
		ServerPlayNetworking.send(player, CLASS, out);
	}

	private static void sendSkills(ServerPlayerEntity player, SkillState st) {
		Set<String> owned = st.skillsOf(player.getUuid());
		PassiveEngine.setOwned(player.getUuid(), new java.util.HashSet<>(owned));
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
