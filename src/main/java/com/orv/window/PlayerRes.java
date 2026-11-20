package com.orv.window;

import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Runtime (not saved) state of one player: Mana, Stamina, timers and counters used by passives. */
public class PlayerRes {
	public volatile Set<String> owned = Set.of();

	// resources
	public float mana = 100f;
	public float stamina = 100f;
	public int souls = 0;
	public boolean exhausted = false;
	public boolean sentExhausted = false;
	public boolean shieldOn = false;
	public long bloodIcd = 0;

	// last values sent to the client
	public float sentMana = -1f;
	public float sentStamina = -1f;
	public float sentMaxMana = -1f;
	public float sentMaxStamina = -1f;
	public long sentAt = -1000;

	// timers (server ticks)
	public long lastSprintTick = -1000;
	public long lastDamagedTick = -1000;
	public long lastAttackTick = -1000;

	// attack strength remembered from the cooldown reset
	public float lastStrength = 1f;
	public long lastStrengthTick = -1000;

	// combat counters
	public int combo = 0;
	public long lastHitTick = -1000;
	public int bloodlustStacks = 0;
	public long bloodlustUntil = 0;
	public int rhythmStacks = 0;
	public long rhythmUntil = 0;
	public long lastKillTick = -1000;
	public long adrenalineUntil = 0;
	public long adrenalineIcd = 0;
	public int focus = 0;
	public long focusUntil = 0;
	public boolean overchargeReady = false;
	public long overchargeIcd = 0;
	public UUID markedTarget = null;
	public long markedUntil = 0;
	public final Map<UUID, Long> firstBlood = new HashMap<>();

	// defence timers
	public long painUntil = 0;
	public long painIcd = 0;
	public long secondChanceIcd = 0;
	public long deathWardIcd = 0;
	public long guardianIcd = 0;
	public long dodgeIcd = 0;
	public long deflectIcd = 0;
	public long purifyIcd = 0;
	public long secondWindIcd = 0;
	public int secondWindLeft = 0;
	public UUID lastAttackerId = null;
	public int unyieldStacks = 0;
	public long unyieldTick = -1000;
	public String lastElement = null;
	public int adaptStacks = 0;
	public long adaptUntil = 0;
	public float overhealAbs = 0f;
	public long overhealUntil = 0;

	// movement
	public Vec3d lastPos = null;
	public int moveTicks = 0;
	public int stillTicks = 0;
	public double lastEnemyDist = -1;
	public boolean pursuing = false;

	// environment (refreshed every few ticks)
	public boolean dark = false;
	public boolean night = false;

	// survival helpers
	public float lastExhaustion = -1f;
	public int lastFrozen = -1;
	public float frozenAcc = 0f;
	public final Map<StatusEffect, Integer> lastEffDur = new HashMap<>();

	// attribute modifiers already applied (so we only touch them when the value changes)
	public final Map<String, Double> lastAttr = new HashMap<>();
	public ServerPlayerEntity attrOwner = null;

	// ---- spells
	public int castSpell = -1;
	public long castEnd = 0;
	public int echoSpell = -1;
	public long echoAt = 0;
	public long echoIcd = 0;
	public int lastSpell = -1;
	public long lastSpellTick = -1000;
	public final Map<String, Long> spellRecent = new HashMap<>();
	public final Map<String, Long> elemRecent = new HashMap<>();
	public long overflowUntil = 0;
	public long overflowIcd = 0;
	public long harmonyUntil = 0;
	public long harmonyIcd = 0;
	public long chainIcd = 0;
	public long glacialIcd = 0;
	public int charge = 0;
	public long soulEaterIcd = 0;
	public boolean nearMagic = false;

	// ---- extra combat passives
	public long reflexUntil = 0;
	public long reflexIcd = 0;
	public long counterIcd = 0;
	public long dualIcd = 0;
	public ItemStack refundStack = null;
	public int refundTo = 0;
	public long absorbIcd = 0;

	// ---- dash / roll
	public long dashIcd = 0;
	public long iframeUntil = 0;

	// ---- hunter
	public UUID aimTarget = null;
	public long aimSince = 0;
	public UUID weakTarget = null;
	public long weakUntil = 0;
	public long weakIcd = 0;
	public java.util.Set<UUID> aware = new java.util.HashSet<>();
	public long warnIcd = 0;
	public final Map<UUID, Blood> bleeders = new HashMap<>();

	public static final class Blood {
		public long until;
		public final ArrayDeque<net.minecraft.util.math.Vec3d> trail = new ArrayDeque<>();
	}

	// ---- combat medic
	public Item useItem = null;
	public int useLeft = 0;
	public float hpPrev = -1f;
}
