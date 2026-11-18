package com.orv.window;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
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
	/** Skill points I can spend in the shop, synced from the server. */
	public static volatile int myPoints = 0;
	/** Mana / Stamina, synced from the server (HP comes from the player itself). */
	public static volatile float mana = 100f;
	public static volatile float maxMana = 100f;
	public static volatile float stamina = 100f;
	public static volatile float maxStamina = 100f;
	public static volatile boolean exhausted = false;

	public static KeyBinding bloodKey;
	public static KeyBinding shieldKey;

	@Override
	public void onInitializeClient() {
		PlayerData.load();

		openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.orvwindow.open",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_K,
				"category.orvwindow"
		));

		bloodKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.orvwindow.blood_conversion", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "category.orvwindow"));
		shieldKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.orvwindow.mana_shield", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.orvwindow"));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.wasPressed()) {
				if (client.player != null && client.currentScreen == null) {
					openWindow(client);
				}
			}
			while (bloodKey.wasPressed()) {
				if (client.player != null && client.currentScreen == null) {
					sendAction(0);
				}
			}
			while (shieldKey.wasPressed()) {
				if (client.player != null && client.currentScreen == null) {
					sendAction(1);
				}
			}
		});

		HudRenderCallback.EVENT.register(VitalsHud::render);

		ClientPlayNetworking.registerGlobalReceiver(OrvWindowMod.VITALS, (client, handler, buf, responseSender) -> {
			float m = buf.readFloat();
			float mm = buf.readFloat();
			float st = buf.readFloat();
			float ms = buf.readFloat();
			boolean ex = buf.readBoolean();
			client.execute(() -> {
				mana = m;
				maxMana = mm;
				stamina = st;
				maxStamina = ms;
				exhausted = ex;
			});
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
			int pts = buf.readVarInt();
			client.execute(() -> {
				mySkills = set;
				myPoints = pts;
			});
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			SELF.reset();
			OTHERS.clear();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			SELF.reset();
			OTHERS.clear();
			mySkills = Set.of();
			myPoints = 0;
			mana = 100f;
			maxMana = 100f;
			stamina = 100f;
			maxStamina = 100f;
			exhausted = false;
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

	/** Open the window and release the mouse cursor so it can be used on the window. */
	public static void openWindow(MinecraftClient client) {
		if (!SELF.open) {
			SELF.set(true);
			sendState(true);
			client.getSoundManager().play(PositionedSoundInstance.master(OrvSounds.OPEN, 1.0f, 0.8f));
		}
		client.setScreen(new WindowScreen());
	}

	/** Collapse the window (called when the cursor screen closes). */
	public static void closeWindow() {
		if (SELF.open) {
			SELF.set(false);
			sendState(false);
			MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.master(OrvSounds.CLOSE, 1.0f, 0.8f));
		}
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

	public static void sendAction(int id) {
		if (ClientPlayNetworking.canSend(OrvWindowMod.ACTION)) {
			PacketByteBuf buf = PacketByteBufs.create();
			buf.writeVarInt(id);
			ClientPlayNetworking.send(OrvWindowMod.ACTION, buf);
		}
	}

	public static void sendBuy(String skillId) {
		if (ClientPlayNetworking.canSend(OrvWindowMod.BUY)) {
			PacketByteBuf buf = PacketByteBufs.create();
			buf.writeString(skillId);
			ClientPlayNetworking.send(OrvWindowMod.BUY, buf);
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
