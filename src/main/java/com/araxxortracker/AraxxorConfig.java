package com.araxxortracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("arraxxor")
public interface AraxxorConfig extends Config
{
	@ConfigSection(
		name = "Overlay",
		description = "Overlay visibility and display options",
		position = 0
	)
	String overlaySection = "overlay";

	@ConfigSection(
		name = "Splits & Timing",
		description = "Split comparison and target time settings",
		position = 1
	)
	String splitsSection = "splits";

	@ConfigSection(
		name = "Misc",
		description = "Miscellaneous settings",
		position = 2
	)
	String statsSection = "statistics";

	@ConfigItem(
		keyName = "showWhenInactive",
		name = "Show Always",
		description = "Always display the overlay even when not fighting Araxxor",
		position = 0,
		section = overlaySection
	)
	default boolean showWhenInactive()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showPreFightStats",
		name = "Show Comparison",
		description = "Controls the secondary box showing last kills / best time / target. Shows the box that typically appears at the end of a kill",
		position = 1,
		section = overlaySection
	)
	default boolean showPreFightStats()
	{
		return false;
	}

	@ConfigItem(
		keyName = "overlayMode",
		name = "Overlay Mode",
		description = "Display mode for the overlay. Main shows all information, Minimal shows only egg rotation, total time, and split.",
		position = 2,
		section = overlaySection
	)
	default OverlayMode overlayMode()
	{
		return OverlayMode.MAIN;
	}

	@ConfigItem(
		keyName = "showLiveSplits",
		name = "Show Splits",
		description = "Display real-time split differences during the fight",
		position = 0,
		section = splitsSection
	)
	default boolean showLiveSplits()
	{
		return true;
	}

	@ConfigItem(
		keyName = "splitComparisonMode",
		name = "Split Type",
		description = "What to compare splits against",
		position = 1,
		section = splitsSection
	)
	default SplitComparisonMode splitComparisonMode()
	{
		return SplitComparisonMode.PERSONAL_BEST;
	}

	@ConfigItem(
		keyName = "targetTotalTime",
		name = "Target Time",
		description = "Target total kill time. Accepts seconds (e.g., 95) or MM:SS format (e.g., 1:35). Only used when Split Comparison is 'Target Times'.",
		position = 2,
		section = splitsSection
	)
	default int targetTotalTime()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "showStatsOverlay",
		name = "Show Stats Overlay",
		description = "Show the optional stats overlay with kills and best times by rotation",
		position = 0,
		section = statsSection
	)
	default boolean showStatsOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showRotationBestTimes",
		name = "Show Rotation Best Times",
		description = "Show best times for each starting egg rotation (White, Red, Green) instead of single best time",
		position = 1,
		section = statsSection
	)
	default boolean showRotationBestTimes()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showKillTiles",
		name = "Kill Box Animation",
		description = "Display stat card above boss death location showing hits, damage, DPS, time, and rotation",
		position = 3,
		section = overlaySection
	)
	default boolean showKillTiles()
	{
		return true;
	}

}

