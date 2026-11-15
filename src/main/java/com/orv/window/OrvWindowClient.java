package com.orv.window;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class OrvWindowClient implements ClientModInitializer {
	public static KeyBinding openKey;

	/** My own window. */
	public static final WindowAnim SELF = new WindowAnim();
	/** Other players whose window is open or still closing (they see the empty frame only). */
	public static final Map<UUID, WindowAnim> OTHERS = new ConcurrentHashMap<>();
	/** Passive skills I own (ids), synced from the server. */
	public static volatile Set<String> mySkills = Set.of();

	@Override
	public void onInitializeClient() {
		PlayerData.load();

		openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.orvwindow.open",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_K,
				"category.orvwindow"
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.wasPressed()) {
				if (client.player != null && client.currentScreen == null) {
					boolean now = !SELF.open;
					SELF.set(now);
					sendState(now);
					client.getSoundManager().play(PositionedSoundInstance.master(
							now ? OrvSounds.OPEN : OrvSounds.CLOSE, 1.0f, 0.8f));
				}
			}
		});

		// Left-click while aiming at the "+" button opens the skill list instead of attacking.
		ClientPreAttackCallback.EVENT.register((client, player, clickCount) -> {
			if (SELF.open && SELF.progress >= 0.9f && client.currentScreen == null
					&& client.options.getPerspective().isFirstPerson()
					&& canGrant(player) && StatusRenderer.aimingAtPlus(player, 1.0f)) {
				client.setScreen(new SkillPickerScreen());
				return true;
			}
			return false;
		});

		ClientPlayNetworking.registerGlobalReceiver(OrvWindowMod.STATE, (client, handler, buf, responseSender) -> {
			UUID id = buf.readUuid();
			boolean open = buf.readBoolean();
			client.execute(() -> {
				if (open) {
					OTHERS.computeIfAbsent(id, k -> new WindowAnim()).set(true);
				} else {
					WindowAnim a = OTHERS.get(id);
					if (a != null) {
						a.set(false);
					}
				}
				if (client.world != null) {
					PlayerEntity p = client.world.getPlayerByUuid(id);
					if (p != null) {
						client.world.playSound(p.getX(), p.getEyeY(), p.getZ(),
								open ? OrvSounds.OPEN : OrvSounds.CLOSE,
								SoundCategory.PLAYERS, 0.6f, 1.0f, false);
					}
				}
			});
		});

		ClientPlayNetworking.registerGlobalReceiver(OrvWindowMod.SKILLS, (client, handler, buf, responseSender) -> {
			int n = buf.readVarInt();
			Set<String> set = new HashSet<>();
			for (int i = 0; i < n; i++) {
				set.add(buf.readString(64));
			}
			client.execute(() -> mySkills = set);
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			SELF.reset();
			OTHERS.clear();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			SELF.reset();
			OTHERS.clear();
			mySkills = Set.of();
		});

		// /status name <ชื่อ>
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(ClientCommandManager.literal("status")
						.then(ClientCommandManager.literal("name")
								.then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
										.executes(ctx -> {
											String n = StringArgumentType.getString(ctx, "name").trim();
											if (n.length() > 16) {
												n = n.substring(0, 16);
											}
											PlayerData.name = n;
											PlayerData.save();
											ctx.getSource().sendFeedback(Text.translatable("orvwindow.name_set", n));
											return 1;
										})))));

		WorldRenderEvents.LAST.register(StatusRenderer::render);
	}

	/** Ops (permission level 2+) may add passives directly. */
	public static boolean canGrant(PlayerEntity player) {
		return player != null && player.hasPermissionLevel(2);
	}

	public static void sendGrant(String skillId, boolean add) {
		if (ClientPlayNetworking.canSend(OrvWindowMod.GRANT)) {
			PacketByteBuf buf = PacketByteBufs.create();
			buf.writeString(skillId);
			buf.writeBoolean(add);
			ClientPlayNetworking.send(OrvWindowMod.GRANT, buf);
		}
	}

	private static void sendState(boolean open) {
		if (ClientPlayNetworking.canSend(OrvWindowMod.STATE)) {
			PacketByteBuf buf = PacketByteBufs.create();
			buf.writeBoolean(open);
			ClientPlayNetworking.send(OrvWindowMod.STATE, buf);
		}
	}
}
