package com.orv.window;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-player runtime state used by the passives (server side only, not saved). */
public class PlayerRuntime {
	public boolean init = false;
	public double mana;
	public double stamina;

	// movement
	public double lastX;
	public double lastZ;
	public int stillTicks;
	public int movingTicks;
	public double momentum;
	public int lastSprintTick = -1000;
	public int lastFrozen;

	// combat timers (server ticks)
	public int lastDamagedTick = -1000;
	public int lastAttackTick = -1000;
	public float lastSwingProgress = 1f;

	public int regenTick;
	public int rejuvTick;
	public int naturalTick;

	public int combo;
	public int comboLastTick = -1000;
	public int adrenalineHits;
	public int adrenalineLast = -1000;
	public int adrenalineUntil;
	public int adrenalineIcd;
	public int bloodlustStacks;
	public int bloodlustUntil;
	public int rhythm;
	public int rhythmUntil;
	public int lastKill = -1000;
	public int focus;
	public int focusUntil;
	public int charge;
	public int overchargeIcd;
	public int burnIcd;
	public int toxicIcd;
	public UUID markedTarget;
	public int markUntil;

	// defensive timers
	public double secondWindLeft;
	public int secondWindIcd;
	public int secondChanceIcd;
	public int deathWardIcd;
	public int guardianIcd;
	public int purifyIcd;
	public int evasiveIcd;
	public int deflectIcd;
	public int painUntil;
	public int painIcd;
	public UUID lastAttacker;
	public int repeat;
	public int repeatTick = -1000;
	public float overhealAmount;
	public int overhealUntil;
	public int djIcd;

	public final Map<UUID, Integer> firstHit = new HashMap<>();
	public final Map<UUID, Integer> ambushHit = new HashMap<>();
	/** element key -> {stacks, untilTick} */
	public final Map<String, int[]> adapt = new HashMap<>();

	// what was last sent to the client
	public int sentMana = -1;
	public int sentMaxMana = -1;
	public int sentStamina = -1;
	public int sentMaxStamina = -1;
}
