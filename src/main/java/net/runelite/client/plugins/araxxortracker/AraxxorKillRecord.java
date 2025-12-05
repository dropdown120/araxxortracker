package net.runelite.client.plugins.araxxortracker;

import lombok.Getter;
import lombok.Setter;
import java.util.HashMap;
import java.util.Map;

/**
 * Record of a single Araxxor kill with loot, equipment, and performance stats
 */
@Getter
@Setter
public class AraxxorKillRecord
{
	private long timestamp;
	private long killTime;
	private AraxxorEggType rotation;
	private Map<Integer, Long> loot;
	private long lootValue;
	private int hits;
	private int damageDealt;
	private int damageTaken;
	
	public AraxxorKillRecord()
	{
		this.timestamp = System.currentTimeMillis();
		this.loot = new HashMap<>();
		this.lootValue = 0;
	}
	
	public AraxxorKillRecord(long timestamp, long killTime, AraxxorEggType rotation,
		Map<Integer, Long> loot, long lootValue,
		int hits, int damageDealt, int damageTaken)
	{
		this.timestamp = timestamp;
		this.killTime = killTime;
		this.rotation = rotation;
		this.loot = loot != null ? new HashMap<>(loot) : new HashMap<>();
		this.lootValue = lootValue;
		this.hits = hits;
		this.damageDealt = damageDealt;
		this.damageTaken = damageTaken;
	}
}

