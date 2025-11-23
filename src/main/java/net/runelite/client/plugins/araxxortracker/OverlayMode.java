package net.runelite.client.plugins.araxxortracker;

public enum OverlayMode
{
	MAIN("Main"),
	MINIMAL("Minimal");
	
	private final String displayName;
	
	OverlayMode(String displayName)
	{
		this.displayName = displayName;
	}
	
	@Override
	public String toString()
	{
		return displayName;
	}
}

