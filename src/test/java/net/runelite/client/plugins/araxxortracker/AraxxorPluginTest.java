package net.runelite.client.plugins.araxxortracker;

import net.runelite.client.RuneLite;

public class AraxxorPluginTest
{
	public static void main(String[] args) throws Exception
	{
		// Plugin is automatically discovered via classpath scanning
		// Don't use loadBuiltin() as it causes duplicate plugin registration
		RuneLite.main(args);
	}
}