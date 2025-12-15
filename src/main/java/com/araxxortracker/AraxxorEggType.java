/*
 * Copyright (c) 2025
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.araxxortracker;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Represents the three types of Araxxor eggs for rotation tracking
 */
@Getter
@RequiredArgsConstructor
public enum AraxxorEggType
{
	WHITE(13670, 13671, "White", new Color(220, 220, 220), "◯"),
	RED(13672, 13673, "Red", new Color(220, 20, 20), "◯"),
	GREEN(13674, 13675, "Green", new Color(20, 200, 20), "◯");

	private final int eggNpcId;
	private final int minionNpcId;
	private final String name;
	private final Color color;
	private final String icon;

	// Static maps for O(1) lookup
	private static final Map<Integer, AraxxorEggType> EGG_ID_MAP = new HashMap<>();
	private static final Map<Integer, AraxxorEggType> MINION_ID_MAP = new HashMap<>();
	private static final AraxxorEggType[] VALUES_CACHE = values();

	static
	{
		for (AraxxorEggType type : VALUES_CACHE)
		{
			EGG_ID_MAP.put(type.eggNpcId, type);
			MINION_ID_MAP.put(type.minionNpcId, type);
		}
	}

	/**
	 * Get the next egg type in the rotation
	 */
	public AraxxorEggType getNext()
	{
		return VALUES_CACHE[(ordinal() + 1) % VALUES_CACHE.length];
	}

	/**
	 * Get egg type from egg NPC ID
	 */
	public static AraxxorEggType fromEggId(int npcId)
	{
		return EGG_ID_MAP.get(npcId);
	}

	/**
	 * Get egg type from minion NPC ID
	 */
	public static AraxxorEggType fromMinionId(int npcId)
	{
		return MINION_ID_MAP.get(npcId);
	}

	/**
	 * Check if NPC ID is any Araxxor egg
	 */
	public static boolean isEgg(int npcId)
	{
		return fromEggId(npcId) != null;
	}

	/**
	 * Check if NPC ID is any Araxxor minion
	 */
	public static boolean isMinion(int npcId)
	{
		return fromMinionId(npcId) != null;
	}
}

