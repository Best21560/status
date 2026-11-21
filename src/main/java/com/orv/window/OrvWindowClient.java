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
	/** My mod level, XP, XP needed for the next level, unspent stat points and stats (STR, DEX, INT, CON, WILL). */
	public static volatile int myLevel = 1;
	public static volatile int myXp = 0;
	public static volatile int myXpNeed = 30;
	public static volatile int myStatPoints = 0;
	public static volatile int[] myStats = new int[5];
	/** Mana / Stamina, synced from the server (HP comes from the player itself). */
	public static volatile float mana = 100f;
	public static volatile float maxMana = 100f;
	public static volatile float stamina = 100f;
	public static volatile float maxStamina = 100f;
	public static volatile boolean exhausted = false;
	/** My class id ("" = none). classKnown turns true once the server told us. */
	public static volatile String myClass = "";
	public static volatile boolean classKnown = false;
	private static boolean prevAttack = false;
	/** Slayer: Rage 0-100 and the client tick at which each skill is ready again. */
	public static volatile boolean slayer = false;
	public static volatile float rage = 0f;
	public static final long[] cdEnd = new long[5];
	public static KeyBinding slashKey;
	public static KeyBinding pursuitKey;
	public static KeyBinding fatalKey;
	public static KeyBinding judgmentKey;
	public static KeyBinding berserkKey;

	public static KeyBinding bloodKey;
	public static KeyBinding shieldKey;
	public static KeyBinding soulKey;
	public static KeyBinding dashKey;

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

		soulKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.orvwindow.soul_eater", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, "category.orvwindow"));
		dashKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.orvwindow.dash", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.orvwindow"));

		slashKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.orvwindow.slash", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Z, "category.orvwindow"));
		pursuitKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.orvwindow.pursuit", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_X, "category.orvwindow"));
		fatalKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.orvwindow.fatal", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_C, "category.orvwindow"));
		judgmentKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.orvwindow.judgment", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, "category.orvwindow"));

		berserkKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.orvwindow.berserk", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.orvwindow"));

		ClientTickEvents.START_CLIENT_TICK.register(BerserkClient::suppress);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			Fx.tick(client);
			RogueClient.tick(client);
			boolean noControl = Fx.locked || Fx.berserkTicks > 0;
			if (!noControl) {
				MoveClient.tick(client);
			}
			BerserkClient.tick(client);
			if (slayer && !noControl && client.player != null && client.currentScreen == null) {
				while (slashKey.wasPressed()) {
					sendAction(10);
				}
				while (pursuitKey.wasPressed()) {
					sendAction(11);
				}
				while (fatalKey.wasPressed()) {
					sendAction(12);
				}
				while (judgmentKey.wasPressed()) {
					sendAction(13);
				}
				while (berserkKey.wasPressed()) {
					sendAction(14);
				}
			} else if (myClass.equals("lightbender") && !noControl && client.player != null && client.currentScreen == null) {
				while (slashKey.wasPressed()) {
					sendAction(20);
				}
				while (pursuitKey.wasPressed()) {
					sendAction(21);
				}
				while (fatalKey.wasPressed()) {
					sendAction(22);
				}
				while (judgmentKey.wasPressed()) {
					// no 4th skill for this class
				}
				boolean atk = client.options.attackKey.isPressed()
						|| org.lwjgl.glfw.GLFW.glfwGetMouseButton(client.getWindow().getHandle(), org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
				if (atk && !prevAttack && LightClient.orbsOut()) {
					sendAction(23);
				}
				prevAttack = atk;
			} else if (myClass.equals("rogue") && !noControl && client.player != null && client.currentScreen == null) {
				while (slashKey.wasPressed()) {
					sendAction(30);
				}
				while (pursuitKey.wasPressed()) {
					sendAction(31);
				}
				while (fatalKey.wasPressed()) {
					sendAction(32);
				}
				while (judgmentKey.wasPressed()) {
					sendAction(33);
				}
				while (berserkKey.wasPressed()) {
					sendAction(34);
				}
			} else {
				while (slashKey.wasPressed() || pursuitKey.wasPressed() || fatalKey.wasPressed() || judgmentKey.wasPressed()) {
					// drop presses while the player has no control
				}
			}
			if (classKnown && myClass.isEmpty() && client.player != null && client.world != null
					&& client.currentScreen == null) {
				client.setScreen(new ClassScreen());
			}
			while (soulKey.wasPressed()) {
				if (client.player != null && client.currentScreen == null) {
					sendAction(6);
				}
			}
			while (dashKey.wasPressed()) {
				if (client.player != null && client.currentScreen == null) {
					MoveClient.dash(client);
				}
			}
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
		HudRenderCallback.EVENT.register(Fx::renderHud);

		ClientPlayNetworking.registerGlobalReceiver(OrvWindowMod.LIGHT, (client, handler, buf, responseSender) ->
				LightClient.receive(client, buf));

		ClientPlayNetworking.registerGlobalReceiver(OrvWindowMod.FX, (client, handler, buf, responseSender) -> {
			int type = buf.readVarInt();
			double x = buf.readDouble();
			double y = buf.readDouble();
			double z = buf.readDouble();
			float dx = buf.readFloat();
			float dy = buf.readFloat();
			float dz = buf.readFloat();
			float a = buf.readFloat();
			int b = buf.readVarInt();
			client.execute(() -> Fx.spawn(type, new net.minecraft.util.math.Vec3d(x, y, z),
					new net.minecraft.util.math.Vec3d(dx, dy, dz), a, b));
		});

		ClientPlayNetworking.registerGlobalReceiver(OrvWindowMod.VITALS, (client, handler, buf, responseSender) -> {
			float m = buf.readFloat();
			float mm = buf.readFloat();
			float st = buf.readFloat();
			float ms = buf.readFloat();
			boolean ex = buf.readBoolean();
			boolean sl = buf.readBoolean();
			float rg = buf.readFloat();
			int bt = buf.readVarInt();
			boolean lk = buf.readBoolean();
			String atk = buf.readString(64);
			int[] cd = new int[5];
			for (int i = 0; i < 5; i++) {
				cd[i] = buf.readVarInt();
			}
			client.execute(() -> {
				slayer = sl;
				rage = rg;
				Fx.berserkTicks = bt;
				Fx.locked = lk;
				BerserkClient.attacker = atk;
				for (int i = 0; i < 5; i++) {
					cdEnd[i] = Fx.clientTick + cd[i];
				}
				mana = m;
				maxMana = mm;
				stamina = st;
				maxStamina = ms;
				exhausted = ex;
			});
		});

		ClientPlayNetworking.registerGlobalReceiver(OrvWindowMod.CLASS, (client, handler, buf, responseSender) -> {
			String cls = buf.readString(32);
			client.execute(() -> {
				myClass = cls;
				classKnown = true;
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
			client.execute(() -> mySkills = set);
		});

		ClientPlayNetworking.registerGlobalReceiver(OrvWindowMod.LEVEL, (client, handler, buf, responseSender) -> {
			int lv = buf.readVarInt();
			int xp = buf.readVarInt();
			int need = buf.readVarInt();
			int sp = buf.readVarInt();
			int[] st = new int[5];
			for (int i = 0; i < 5; i++) {
				st[i] = buf.readVarInt();
			}
			client.execute(() -> {
				myLevel = lv;
				myXp = xp;
				myXpNeed = need;
				myStatPoints = sp;
				myStats = st;
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
			myLevel = 1;
			myXp = 0;
			myXpNeed = 30;
			myStatPoints = 0;
			myStats = new int[5];
			mana = 100f;
			maxMana = 100f;
			stamina = 100f;
			maxStamina = 100f;
			exhausted = false;
			myClass = "";
			classKnown = false;
			slayer = false;
			rage = 0f;
			java.util.Arrays.fill(cdEnd, 0L);
			BerserkClient.attacker = "";
			Fx.reset();
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
		WorldRenderEvents.LAST.register(Fx::render);
		WorldRenderEvents.AFTER_ENTITIES.register(RogueClient::render);
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

	public static void sendClassPick(String id) {
		if (ClientPlayNetworking.canSend(OrvWindowMod.CLASS_PICK)) {
			PacketByteBuf buf = PacketByteBufs.create();
			buf.writeString(id);
			ClientPlayNetworking.send(OrvWindowMod.CLASS_PICK, buf);
		}
	}

	public static void sendStatUp(int index) {
		if (ClientPlayNetworking.canSend(OrvWindowMod.STAT_UP)) {
			PacketByteBuf buf = PacketByteBufs.create();
			buf.writeVarInt(index);
			ClientPlayNetworking.send(OrvWindowMod.STAT_UP, buf);
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
