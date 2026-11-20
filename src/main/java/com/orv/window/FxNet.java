package com.orv.window;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

/** Sends visual effects (drawn by the client, see Fx) to the players around. */
public final class FxNet {
	private FxNet() {
	}

	public static final int SLASH = 0;
	public static final int IMPACT = 1;
	public static final int DASH = 2;
	public static final int SHOCK = 3;
	public static final int BERSERK_START = 4;
	public static final int BERSERK_END = 5;
	public static final int CUTSCENE_START = 6;
	public static final int CUTSCENE_END = 7;
	public static final int PARRY = 8;
	public static final int GRAB = 9;
	public static final int FLASH = 10;

	private static PacketByteBuf make(int type, Vec3d pos, Vec3d dir, float a, int b) {
		PacketByteBuf out = PacketByteBufs.create();
		out.writeVarInt(type);
		out.writeDouble(pos.x);
		out.writeDouble(pos.y);
		out.writeDouble(pos.z);
		out.writeFloat((float) dir.x);
		out.writeFloat((float) dir.y);
		out.writeFloat((float) dir.z);
		out.writeFloat(a);
		out.writeVarInt(b);
		return out;
	}

	/** To everybody within 80 blocks. */
	public static void send(ServerWorld w, int type, Vec3d pos, Vec3d dir, float a, int b) {
		for (ServerPlayerEntity sp : PlayerLookup.around(w, pos, 80.0)) {
			if (ServerPlayNetworking.canSend(sp, OrvWindowMod.FX)) {
				ServerPlayNetworking.send(sp, OrvWindowMod.FX, make(type, pos, dir, a, b));
			}
		}
	}

	/** To one player only. */
	public static void sendTo(ServerPlayerEntity p, int type, Vec3d pos, Vec3d dir, float a, int b) {
		if (ServerPlayNetworking.canSend(p, OrvWindowMod.FX)) {
			ServerPlayNetworking.send(p, OrvWindowMod.FX, make(type, pos, dir, a, b));
		}
	}
}
