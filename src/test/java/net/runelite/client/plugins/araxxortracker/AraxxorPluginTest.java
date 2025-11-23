package net.runelite.client.plugins.araxxortracker;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AraxxorPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AraxxorPlugin.class);
		RuneLite.main(args);
	}
}