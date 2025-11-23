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
	// Timestamp
	private long timestamp;
	
	// Fight stats
	private long killTime; // Fight duration in milliseconds
	private AraxxorEggType rotation; // White/Red/Green start
	
	// Loot data
	private Map<Integer, Long> loot; // itemId -> quantity
	private long lootValue; // Total GP value (snapshot at kill time)
	
	// Performance stats
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
		// Defensive copy to prevent external modification of the original map
		this.loot = loot != null ? new HashMap<>(loot) : new HashMap<>();
		this.lootValue = lootValue;
		this.hits = hits;
		this.damageDealt = damageDealt;
		this.damageTaken = damageTaken;
	}
}

