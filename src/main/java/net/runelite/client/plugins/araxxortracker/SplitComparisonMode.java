package net.runelite.client.plugins.araxxortracker;

public enum SplitComparisonMode
{
	PERSONAL_BEST("Best Time"),
	TARGET("Target"),
	LAST_KILL("Last Kill");
	
	private final String displayName;
	
	SplitComparisonMode(String displayName)
	{
		this.displayName = displayName;
	}
	
	@Override
	public String toString()
	{
		return displayName;
	}
}

