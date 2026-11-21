package com.orv.window;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

/**
 * The mod's own Level: replaces the vanilla experience level. Vanilla XP that a player collects is converted
 * into mod XP every tick (and the vanilla level/bar are kept at zero). Each level gives stat points
 * that are spent on STR / DEX / INT / CON / WILL in the status window.
 */
public final class LevelEngine {
	private LevelEngine() {
	}

	public static final int MAX_LEVEL = 100;
	public static final int POINTS_PER_LEVEL = 3;
	/** Mod XP gained per 1 point of vanilla XP. */
	public static final int XP_MULT = 2;
	public static final int STATS = 5;

	// per-point effects
	public static final float STR_DAMAGE = 0.015f;     // +1.5% damage
	public static final double DEX_SPEED = 0.003;      // +0.3% movement speed (max +15%)
	public static final double DEX_ATKSPD = 0.004;     // +0.4% attack speed (max +20%)
	public static final float INT_MANA = 5f;
	public static final double CON_HP = 2.0;
	public static final float WILL_STAMINA = 5f;
	public static final double WILL_KB = 0.005;

	/** XP needed to go from this level to the next. */
	public static int need(int level) {
		return 30 + 12 * level + (int) (0.6 * level * level);
	}

	public static void tick(ServerPlayerEntity p, PlayerRes r) {
		if (!r.lvLoaded) {
			load(p, r);
		}
		if (p.totalExperience > 0 || p.experienceLevel > 0 || p.experienceProgress > 0f) {
			int gained = Math.max(0, p.totalExperience);
			p.totalExperience = 0;
			p.experienceLevel = 0;
			p.experienceProgress = 0f;
			p.addExperienceLevels(0); // forces the client to be told
			if (gained > 0) {
				addXp(p, r, (long) gained * XP_MULT);
			}
		}
	}

	private static void load(ServerPlayerEntity p, PlayerRes r) {
		SkillState.Lv lv = SkillState.get(p.getServer()).lvOf(p.getUuid());
		r.level = lv.level;
		r.xp = lv.xp;
		r.statPoints = lv.points;
		System.arraycopy(lv.stats, 0, r.stats, 0, STATS);
		r.lvLoaded = true;
		r.attrOwner = null; // re-apply attributes
		send(p, r);
	}

	public static void addXp(ServerPlayerEntity p, PlayerRes r, long amount) {
		if (amount <= 0 || r.level >= MAX_LEVEL) {
			return;
		}
		long xp = r.xp + amount;
		boolean up = false;
		while (r.level < MAX_LEVEL && xp >= need(r.level)) {
			xp -= need(r.level);
			r.level++;
			r.statPoints += POINTS_PER_LEVEL;
			up = true;
		}
		if (r.level >= MAX_LEVEL) {
			xp = 0;
		}
		r.xp = (int) Math.min(Integer.MAX_VALUE, xp);
		if (up) {
			p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_PLAYER_LEVELUP,
					SoundCategory.PLAYERS, 0.9f, 1.0f);
			p.sendMessage(Text.translatable("orvwindow.msg.levelup", r.level), true);
			r.fullHeal = true;
		}
		save(p, r);
		send(p, r);
	}

	/** Op command: set the level directly (stat points are recalculated, stats reset). */
	public static void setLevel(ServerPlayerEntity p, PlayerRes r, int level) {
		if (!r.lvLoaded) {
			load(p, r);
		}
		r.level = Math.max(1, Math.min(MAX_LEVEL, level));
		r.xp = 0;
		java.util.Arrays.fill(r.stats, 0);
		r.statPoints = (r.level - 1) * POINTS_PER_LEVEL;
		r.attrOwner = null;
		save(p, r);
		send(p, r);
	}

	public static void spend(ServerPlayerEntity p, PlayerRes r, int idx) {
		if (!r.lvLoaded || idx < 0 || idx >= STATS || r.statPoints <= 0) {
			return;
		}
		r.statPoints--;
		r.stats[idx]++;
		r.attrOwner = null; // attributes are re-applied now
		save(p, r);
		send(p, r);
	}

	private static void save(ServerPlayerEntity p, PlayerRes r) {
		SkillState st = SkillState.get(p.getServer());
		SkillState.Lv lv = st.lvOf(p.getUuid());
		lv.level = r.level;
		lv.xp = r.xp;
		lv.points = r.statPoints;
		System.arraycopy(r.stats, 0, lv.stats, 0, STATS);
		st.markDirty();
	}

	public static void send(ServerPlayerEntity p, PlayerRes r) {
		if (!ServerPlayNetworking.canSend(p, OrvWindowMod.LEVEL)) {
			return;
		}
		PacketByteBuf out = PacketByteBufs.create();
		out.writeVarInt(r.level);
		out.writeVarInt(r.xp);
		out.writeVarInt(r.level >= MAX_LEVEL ? 0 : need(r.level));
		out.writeVarInt(r.statPoints);
		for (int i = 0; i < STATS; i++) {
			out.writeVarInt(r.stats[i]);
		}
		ServerPlayNetworking.send(p, OrvWindowMod.LEVEL, out);
	}
}
