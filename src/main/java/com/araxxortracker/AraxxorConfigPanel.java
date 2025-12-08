package com.araxxortracker;

import com.google.common.base.MoreObjects;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SpinnerModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import net.runelite.client.config.ConfigDescriptor;
import net.runelite.client.config.ConfigItemDescriptor;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ConfigSectionDescriptor;
import net.runelite.client.config.Range;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.SwingUtil;
import net.runelite.client.callback.ClientThread;
import net.runelite.api.Client;
import javax.swing.ImageIcon;

public class AraxxorConfigPanel extends PluginPanel
{
	private static final int SPINNER_FIELD_WIDTH = 6;
	private static final int BORDER_OFFSET = 6;

	private final ConfigManager configManager;
	private final ConfigDescriptor configDescriptor;
	private final AraxxorPlugin plugin;
	private final AraxxorConfig config;
	private final ItemManager itemManager;
	private final ClientThread clientThread;
	
	private static final int MAX_CACHE_SIZE = 1000;
	private final Map<Integer, String> itemNameCache = java.util.Collections.synchronizedMap(
		new java.util.LinkedHashMap<Integer, String>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest) {
				return size() > MAX_CACHE_SIZE;
			}
		}
	);
	
	private static class CachedPrice {
		final int price;
		final long timestamp;
		
		CachedPrice(int price, long timestamp) {
			this.price = price;
			this.timestamp = timestamp;
		}
	}
	
	private static final long PRICE_CACHE_EXPIRY_MS = 24 * 60 * 60 * 1000L;
	private final Map<Integer, CachedPrice> itemPriceCache = java.util.Collections.synchronizedMap(
		new java.util.LinkedHashMap<Integer, CachedPrice>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Integer, CachedPrice> eldest) {
				return size() > MAX_CACHE_SIZE;
			}
		}
	);
	
	private static class CachedGP {
		final long gp;
		final long timestamp;
		
		CachedGP(long gp, long timestamp) {
			this.gp = gp;
			this.timestamp = timestamp;
		}
	}
	
	private static final long GP_CACHE_EXPIRY_MS = 24 * 60 * 60 * 1000L;
	private final Map<String, CachedGP> gpCache = java.util.Collections.synchronizedMap(
		new java.util.LinkedHashMap<String, CachedGP>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, CachedGP> eldest) {
				return size() > 100;
			}
		}
	);
	
	/**
	 * Calculate GP from loot map with caching (recalculates max once per 30 minutes)
	 * This balances freshness with bandwidth - prices don't change frequently
	 */
	long calculateGPWithCache(Map<Integer, Long> loot, String cacheKey)
	{
		if (loot == null || loot.isEmpty())
		{
			return 0;
		}
		
		long currentTime = System.currentTimeMillis();
		
		CachedGP cachedGP = gpCache.get(cacheKey);
		if (cachedGP != null && (currentTime - cachedGP.timestamp) < GP_CACHE_EXPIRY_MS)
		{
			return cachedGP.gp;
		}
		
		java.util.Set<Integer> missingPrices = new java.util.HashSet<>();
		for (Map.Entry<Integer, Long> entry : loot.entrySet())
		{
			int itemId = entry.getKey();
			CachedPrice cachedPrice = itemPriceCache.get(itemId);
			if (cachedPrice == null || cachedPrice.price == 0 || (currentTime - cachedPrice.timestamp) > PRICE_CACHE_EXPIRY_MS)
			{
				missingPrices.add(itemId);
			}
		}
		
		if (!missingPrices.isEmpty() && clientThread != null)
		{
			boolean isOnClientThread = client != null && client.isClientThread();
			if (!isOnClientThread)
			{
				java.util.Set<Integer> finalMissingPrices = new java.util.HashSet<>(missingPrices);
				final String finalCacheKey = cacheKey;
				clientThread.invokeLater(() -> {
					for (int itemId : finalMissingPrices)
					{
						cacheItemPrice(itemId);
					}
					gpCache.remove(finalCacheKey);
				});
			}
			else
			{
				for (int itemId : missingPrices)
				{
					cacheItemPrice(itemId);
				}
			}
		}
		
		long totalGP = 0;
		for (Map.Entry<Integer, Long> entry : loot.entrySet())
		{
			int itemId = entry.getKey();
			long quantity = entry.getValue();
			
			int price = getCachedItemPrice(itemId);
			
			if (price <= 0)
			{
				boolean isOnClientThread = client != null && client.isClientThread();
				
				if (isOnClientThread)
				{
					try
					{
						int customPrice = getCustomItemPrice(itemId);
						if (customPrice > 0)
						{
							price = customPrice;
							itemPriceCache.put(itemId, new CachedPrice(customPrice, System.currentTimeMillis()));
						}
						else
						{
							price = itemManager.getItemPrice(itemId);
							if (price > 0)
							{
								itemPriceCache.put(itemId, new CachedPrice(price, System.currentTimeMillis()));
							}
						}
					}
					catch (Exception e)
					{
					}
				}
			}
			
			long itemValue = (long)price * quantity;
			totalGP += itemValue;
		}
		
		boolean allPricesAvailable = missingPrices.isEmpty();
		if (totalGP > 0 || allPricesAvailable)
		{
			gpCache.put(cacheKey, new CachedGP(totalGP, currentTime));
		}
		
		return totalGP;
	}
	

	public AraxxorConfigPanel(ConfigManager configManager, AraxxorConfig config, AraxxorPlugin plugin, ItemManager itemManager, ClientThread clientThread, Client client)
	{
		this.configManager = configManager;
		this.configDescriptor = configManager.getConfigDescriptor(config);
		this.plugin = plugin;
		this.config = config;
		this.itemManager = itemManager;
		this.clientThread = clientThread;
		this.client = client;
		rebuild();
	}
	
	private final Client client;
	
	private final java.util.Set<Integer> pendingNameFetches = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
	private final java.util.Set<Integer> pendingPriceFetches = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
	
	/**
	 * Get cached item name (thread-safe)
	 */
	String getCachedItemName(int itemId)
	{
		String cached = itemNameCache.get(itemId);
		if (cached != null)
		{
			return cached;
		}
		
		if (!pendingNameFetches.contains(itemId))
		{
			pendingNameFetches.add(itemId);
			clientThread.invokeLater(() ->
			{
				pendingNameFetches.remove(itemId);
				if (!itemNameCache.containsKey(itemId))
				{
					try
					{
						var composition = itemManager.getItemComposition(itemId);
						if (composition != null)
						{
							String name = composition.getName();
							if (name != null)
							{
								itemNameCache.put(itemId, name);
							}
						}
					}
			catch (Exception e)
						{
						}
				}
			});
		}
		
		return "Item " + itemId;
	}
	
	/**
	 * Pre-populate item name cache (called from plugin on client thread)
	 */
	public void cacheItemName(int itemId)
	{
		if (!itemNameCache.containsKey(itemId))
		{
			try
			{
				var composition = itemManager.getItemComposition(itemId);
				if (composition != null)
				{
					String name = composition.getName();
					if (name != null)
					{
						itemNameCache.put(itemId, name);
					}
				}
			}
			catch (Exception e)
			{
			}
		}
	}
	
	private int getCustomItemPrice(int itemId)
	{
		if (itemId == 29790 || itemId == 29792 || itemId == 29794)
		{
			try
			{
				int halberdPrice = itemManager.getItemPrice(29796);
				if (halberdPrice > 0)
				{
					int partPrice = halberdPrice / 3;
					return partPrice;
				}
				return 12_000_000;
			}
			catch (IllegalStateException e)
			{
				return -1;
			}
		}
		
		if (itemId == 29799)
		{
			try
			{
				int rancourPrice = itemManager.getItemPrice(29801);
				if (rancourPrice > 0)
				{
					return rancourPrice;
				}
				return 60_000_000;
			}
			catch (IllegalStateException e)
			{
				return -1;
			}
		}
		
		return -1;
	}
	
	public void cacheItemPrice(int itemId)
	{
		long currentTime = System.currentTimeMillis();
		
		CachedPrice cached = itemPriceCache.get(itemId);
		if (cached != null && cached.price > 0 && (currentTime - cached.timestamp) < PRICE_CACHE_EXPIRY_MS)
		{
			return;
		}
		
		try
		{
			boolean isOnClientThread = client != null && client.isClientThread();
			if (isOnClientThread)
			{
				int customPrice = getCustomItemPrice(itemId);
				if (customPrice > 0)
				{
					itemPriceCache.put(itemId, new CachedPrice(customPrice, currentTime));
					return;
				}
			}
			
			if (isOnClientThread)
			{
				int price = itemManager.getItemPrice(itemId);
				if (price > 0)
				{
					itemPriceCache.put(itemId, new CachedPrice(price, currentTime));
				}
			}
		}
		catch (Exception e)
		{
		}
	}
	
	/**
	 * Clear item caches
	 */
	public void clearItemCaches()
	{
		itemNameCache.clear();
		itemPriceCache.clear();
		pendingNameFetches.clear();
		pendingPriceFetches.clear();
	}
	
	/**
	 * Cleanup resources
	 */
	public void cleanup()
	{
		if (rebuildTimer != null && rebuildTimer.isRunning())
		{
			rebuildTimer.stop();
			rebuildTimer = null;
		}
		
		clearItemCaches();
		sectionExpandStates.clear();
	}
	
	/**
	 * Get cached item price (thread-safe)
	 * Prices are cached for 3 hours before refresh
	 */
	int getCachedItemPrice(int itemId)
	{
		long currentTime = System.currentTimeMillis();
		
		CachedPrice cached = itemPriceCache.get(itemId);
		if (cached != null && cached.price > 0)
		{
			if ((currentTime - cached.timestamp) < PRICE_CACHE_EXPIRY_MS)
			{
				return cached.price;
			}
		}
		
		boolean isOnClientThread = client != null && client.isClientThread();
		if (isOnClientThread)
		{
			int customPrice = getCustomItemPrice(itemId);
			if (customPrice > 0)
			{
				itemPriceCache.put(itemId, new CachedPrice(customPrice, currentTime));
				return customPrice;
			}
		}
		
		if (cached != null && cached.price > 0)
		{
			return cached.price;
		}
		
		if (!pendingPriceFetches.contains(itemId))
		{
			pendingPriceFetches.add(itemId);
			try
			{
				clientThread.invokeLater(() ->
				{
					pendingPriceFetches.remove(itemId);
					CachedPrice existingCache = itemPriceCache.get(itemId);
					long cacheTime = System.currentTimeMillis();
					if (existingCache == null || existingCache.price == 0 || (cacheTime - existingCache.timestamp) > PRICE_CACHE_EXPIRY_MS)
					{
						try
						{
							int customPriceAsync = getCustomItemPrice(itemId);
							int price;
							if (customPriceAsync > 0)
							{
								price = customPriceAsync;
							}
							else
							{
								price = itemManager.getItemPrice(itemId);
							}
							if (price > 0)
							{
								if (itemPriceCache.size() >= MAX_CACHE_SIZE && !itemPriceCache.isEmpty())
								{
									Integer firstKey = itemPriceCache.keySet().iterator().next();
									if (firstKey != null)
									{
										itemPriceCache.remove(firstKey);
									}
								}
								itemPriceCache.put(itemId, new CachedPrice(price, System.currentTimeMillis()));
								
								gpCache.clear();
							}
						}
						catch (Exception e)
						{
						}
					}
				});
			}
			catch (Exception e)
			{
				pendingPriceFetches.remove(itemId);
			}
		}
		
		return 0;
	}

	private long lastRebuildTime = 0;
	private static final long REBUILD_THROTTLE_MS = 100;
	private javax.swing.Timer rebuildTimer = null;
	
	public void refreshStats()
	{
		long currentTime = System.currentTimeMillis();
		if (currentTime - lastRebuildTime < REBUILD_THROTTLE_MS)
		{
			if (rebuildTimer == null || !rebuildTimer.isRunning())
			{
				rebuildTimer = new javax.swing.Timer((int)REBUILD_THROTTLE_MS, e -> {
					rebuildTimer.stop();
					doRefreshStats();
				});
				rebuildTimer.setRepeats(false);
				rebuildTimer.start();
			}
			return;
		}
		
		SwingUtilities.invokeLater(() -> {
			doRefreshStats();
		});
	}
	
	private void doRefreshStats()
	{
		lastRebuildTime = System.currentTimeMillis();
		rebuild(); // Full rebuild to update stats display
	}
	

	/**
	 * Rebuild only the splits section instead of entire panel
	 */
	private void rebuildSplitsSection()
	{
		if (splitsSectionPanel == null || mainPanel == null)
		{
			rebuild();
			return;
		}
		
		int splitsIndex = -1;
		for (int i = 0; i < mainPanel.getComponentCount(); i++)
		{
			if (mainPanel.getComponent(i) == splitsSectionPanel)
			{
				splitsIndex = i;
				break;
			}
		}
		
		if (splitsIndex >= 0)
		{
			mainPanel.remove(splitsIndex);
			
			for (ConfigSectionDescriptor sectionDesc : configDescriptor.getSections())
			{
				if (sectionDesc.key().equals("splits"))
				{
					splitsSectionPanel = createSectionPanel(sectionDesc);
					mainPanel.add(splitsSectionPanel, splitsIndex);
					break;
				}
			}
			
			revalidate();
			repaint();
		}
		else
		{
			rebuild();
		}
	}
	
	private void rebuild()
	{
		removeAll();
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		mainPanel = new JPanel();
		mainPanel.setBorder(new EmptyBorder(8, 10, 10, 10));
		mainPanel.setLayout(new DynamicGridLayout(0, 1, 0, 5));
		mainPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel statisticsHeader = createStatisticsHeader();
		mainPanel.add(statisticsHeader);
		
		JPanel statsPanel = createStatsPanel();
		mainPanel.add(statsPanel);

		JPanel spacer = new JPanel();
		spacer.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 15));
		spacer.setOpaque(false);
		mainPanel.add(spacer);
		
		JPanel configHeader = createConfigSectionHeader();
		mainPanel.add(configHeader);

		for (ConfigSectionDescriptor sectionDesc : configDescriptor.getSections())
		{
			JPanel sectionPanel = createSectionPanel(sectionDesc);
			mainPanel.add(sectionPanel);
			if (sectionDesc.key().equals("splits"))
			{
				splitsSectionPanel = sectionPanel;
			}
		}

		for (ConfigItemDescriptor itemDesc : configDescriptor.getItems())
		{
			if (itemDesc.getItem().section().isEmpty())
			{
				JPanel itemPanel = createItemPanel(itemDesc);
				mainPanel.add(itemPanel);
			}
		}

		JPanel lootSpacer = new JPanel();
		lootSpacer.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH, 15));
		lootSpacer.setOpaque(false);
		mainPanel.add(lootSpacer);
		
		JPanel lootTrackerHeader = createLootTrackerHeader();
		mainPanel.add(lootTrackerHeader);

		JPanel lootTrackerPanel = createLootTrackerSection();
		mainPanel.add(lootTrackerPanel);

		JPanel northPanel = new JPanel();
		northPanel.setLayout(new BorderLayout());
		northPanel.add(mainPanel, BorderLayout.NORTH);
		northPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		add(northPanel, BorderLayout.CENTER);
		revalidate();
		repaint();
	}

	private JPanel createStatsPanel()
	{
		JPanel statsPanel = new JPanel();
		statsPanel.setLayout(new BoxLayout(statsPanel, BoxLayout.Y_AXIS));
		statsPanel.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH, 0));
		statsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		statsPanel.setBorder(new CompoundBorder(
			new MatteBorder(2, 2, 2, 2, ColorScheme.BRAND_ORANGE),
			new EmptyBorder(10, 10, 10, 10)));

		JPanel sectionContents = new JPanel();
		sectionContents.setLayout(new DynamicGridLayout(0, 1, 0, 5));
		sectionContents.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH, 0));
		sectionContents.setOpaque(false);
		sectionContents.setBorder(new EmptyBorder(BORDER_OFFSET, 0, BORDER_OFFSET, 0));

		int killCount = plugin.getKillCount();
		JLabel killCountLabel = new JLabel(htmlLabel("Kills: ", String.valueOf(killCount)));
		killCountLabel.setFont(FontManager.getRunescapeSmallFont());
		killCountLabel.setForeground(Color.WHITE);
		sectionContents.add(killCountLabel);

		long avgTime = plugin.getAverageKillTime();
		if (avgTime > 0)
		{
			JLabel avgTimeLabel = new JLabel(htmlLabel("Avg Time: ", formatTime(avgTime) + " (last 5)"));
			avgTimeLabel.setFont(FontManager.getRunescapeSmallFont());
			avgTimeLabel.setForeground(Color.WHITE);
			sectionContents.add(avgTimeLabel);
		}

		double killsPerHour = plugin.getKillsPerHour();
		if (killsPerHour > 0)
		{
			JLabel kphLabel = new JLabel(htmlLabel("Kills/hr: ", String.valueOf((int) Math.ceil(killsPerHour))));
			kphLabel.setFont(FontManager.getRunescapeSmallFont());
			kphLabel.setForeground(Color.WHITE);
			sectionContents.add(kphLabel);
		}

		JLabel bestTimeTitle = new JLabel("Best Time:");
		bestTimeTitle.setFont(FontManager.getRunescapeSmallFont());
		bestTimeTitle.setForeground(Color.WHITE);
		sectionContents.add(bestTimeTitle);

		long whiteTime = plugin.getBestWhiteStartTime();
		long redTime = plugin.getBestRedStartTime();
		long greenTime = plugin.getBestGreenStartTime();

		List<RotationTime> rotationTimes = new ArrayList<>();
		rotationTimes.add(new RotationTime(AraxxorEggType.WHITE, whiteTime));
		rotationTimes.add(new RotationTime(AraxxorEggType.RED, redTime));
		rotationTimes.add(new RotationTime(AraxxorEggType.GREEN, greenTime));
		
		rotationTimes.sort((a, b) -> {
			if (a.getTime() <= 0 && b.getTime() <= 0) return 0;
			if (a.getTime() <= 0) return 1;
			if (b.getTime() <= 0) return -1;
			return Long.compare(a.getTime(), b.getTime());
		});

		JPanel rotationPanel = new JPanel();
		rotationPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 8, 0));
		rotationPanel.setOpaque(false);

		for (RotationTime rt : rotationTimes) {
			JPanel panel = createRotationLabel(rt.getEggType(), rt.getTime());
			rotationPanel.add(panel);
		}

		sectionContents.add(rotationPanel);
		statsPanel.add(sectionContents);

		return statsPanel;
	}

	private JPanel createRotationLabel(AraxxorEggType eggType, long time)
	{
		String timeStr = time > 0 ? formatTime(time) : "-";
		Color color = time > 0 ? eggType.getColor() : ColorScheme.LIGHT_GRAY_COLOR;

		JPanel containerPanel = new JPanel();
		containerPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 4, 0));
		containerPanel.setOpaque(false);
		
		JPanel circlePanel = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				super.paintComponent(g);
				Graphics2D g2d = (Graphics2D) g.create();
				g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				g2d.setColor(color);
				g2d.setStroke(new java.awt.BasicStroke(2.0f));
				
				int size = 12;
				int x = 0;
				int y = (getHeight() - size) / 2;
				g2d.drawOval(x, y, size, size);
				g2d.dispose();
			}
		};
		circlePanel.setOpaque(false);
		circlePanel.setPreferredSize(new Dimension(12, 20));
		circlePanel.setMinimumSize(new Dimension(12, 20));
		
		JLabel timeLabel = new JLabel(timeStr);
		timeLabel.setFont(FontManager.getRunescapeSmallFont());
		timeLabel.setForeground(color);
		
		containerPanel.add(circlePanel);
		containerPanel.add(timeLabel);
		
		return containerPanel;
	}
	
	private static class RotationTime
	{
		private final AraxxorEggType eggType;
		private final long time;

		public RotationTime(AraxxorEggType eggType, long time)
		{
			this.eggType = eggType;
			this.time = time;
		}

		public AraxxorEggType getEggType() { return eggType; }
		public long getTime() { return time; }
	}

	private static String htmlLabel(String key, String value)
	{
		return "<html><body style='color:#a5a5a5'>" + key + "<span style='color:white'>" + value + "</span></body></html>";
	}

	private static String formatTime(long timeMs)
	{
		if (timeMs < 0)
		{
			return "N/A";
		}
		long totalSeconds = timeMs / 1000;
		long minutes = totalSeconds / 60;
		long seconds = totalSeconds % 60;

		if (minutes > 0)
		{
			return minutes + ":" + String.format("%02d", seconds);
		}
		else
		{
			return String.valueOf(seconds) + "s";
		}
	}

	/**
	 * Convert seconds to MM:SS format
	 */
	private static String secondsToMMSS(int totalSeconds)
	{
		if (totalSeconds <= 0)
		{
			return "0:00";
		}
		int minutes = totalSeconds / 60;
		int seconds = totalSeconds % 60;
		return minutes + ":" + String.format("%02d", seconds);
	}

	/**
	 * Parse MM:SS format to seconds. Returns -1 if invalid format.
	 * Validates input bounds to prevent integer overflow.
	 */
	private static int parseMMSSToSeconds(String timeStr)
	{
		if (timeStr == null || timeStr.trim().isEmpty())
		{
			return 0;
		}

		timeStr = timeStr.trim();
		
		if (timeStr.length() > 20)
		{
			return -1;
		}

		try
		{
			if (timeStr.contains(":"))
			{
				String[] parts = timeStr.split(":", 2);
				if (parts.length == 2)
				{
					int minutes = Integer.parseInt(parts[0].trim());
					int seconds = Integer.parseInt(parts[1].trim());
					if (minutes < 0 || minutes > 99 || seconds < 0 || seconds >= 60)
					{
						return -1;
					}
					if (minutes > (Integer.MAX_VALUE / 60))
					{
						return -1;
					}
					return minutes * 60 + seconds;
				}
				return -1;
			}
			else
			{
				int seconds = Integer.parseInt(timeStr);
				if (seconds < 0 || seconds > 5999)
				{
					return -1;
				}
				return seconds;
			}
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	/**
	 * Parse flexible time input - accepts seconds (e.g., 95) or MM:SS format (e.g., 1:35).
	 * Strips out any text in parentheses (like "(min:sec)") before parsing.
	 * Returns -1 if invalid format.
	 */
	private static int parseFlexibleTime(String timeStr)
	{
		if (timeStr == null || timeStr.trim().isEmpty())
		{
			return 0;
		}

		timeStr = timeStr.trim();
		
		timeStr = timeStr.replaceAll("\\([^)]*\\)", "").trim();
		
		if (timeStr.length() > 20)
		{
			return -1;
		}

		try
		{
			if (timeStr.contains(":"))
			{
				String[] parts = timeStr.split(":", 2);
				if (parts.length == 2)
				{
					int minutes = Integer.parseInt(parts[0].trim());
					int seconds = Integer.parseInt(parts[1].trim());
					if (minutes < 0 || minutes > 99 || seconds < 0 || seconds >= 60)
					{
						return -1;
					}
					if (minutes > (Integer.MAX_VALUE / 60))
					{
						return -1;
					}
					return minutes * 60 + seconds;
				}
				return -1;
			}
			else
			{
				int seconds = Integer.parseInt(timeStr);
				if (seconds < 0 || seconds > 5999)
				{
					return -1;
				}
				return seconds;
			}
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	/**
	 * Format target time for display - shows MM:SS format
	 */
	private static String formatTargetTime(int totalSeconds)
	{
		if (totalSeconds <= 0)
		{
			return "";
		}
		int minutes = totalSeconds / 60;
		int seconds = totalSeconds % 60;
		return minutes + ":" + String.format("%02d", seconds);
	}

	private JPanel createStatisticsHeader()
	{
		JPanel header = new JPanel();
		header.setLayout(new BorderLayout());
		header.setOpaque(false);
		header.setBorder(new CompoundBorder(
			new MatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(8, 0, 8, 0)));
		
		JLabel statsLabel = new JLabel("Statistics");
		statsLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statsLabel.setFont(FontManager.getRunescapeSmallFont());
		header.add(statsLabel, BorderLayout.WEST);
		
		return header;
	}
	
	private JPanel createConfigSectionHeader()
	{
		JPanel header = new JPanel();
		header.setLayout(new BorderLayout());
		header.setOpaque(false);
		header.setBorder(new CompoundBorder(
			new MatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(8, 0, 8, 0)));
		
		JLabel configLabel = new JLabel("Configuration");
		configLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		configLabel.setFont(FontManager.getRunescapeSmallFont());
		header.add(configLabel, BorderLayout.WEST);
		
		return header;
	}
	
	private JPanel createLootTrackerHeader()
	{
		JPanel header = new JPanel();
		header.setLayout(new BorderLayout());
		header.setOpaque(false);
		header.setBorder(new CompoundBorder(
			new MatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(6, 0, 6, 0)));
		
		JLabel lootLabel = new JLabel("Loot Tracker");
		lootLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		lootLabel.setFont(FontManager.getRunescapeSmallFont());
		header.add(lootLabel, BorderLayout.WEST);
		
		JPanel filterBar = createFilterBar();
		header.add(filterBar, BorderLayout.EAST);
		
		return header;
	}
	
	private JPanel createSectionPanel(ConfigSectionDescriptor sectionDesc)
	{
		JPanel section = new JPanel();
		section.setLayout(new BoxLayout(section, BoxLayout.Y_AXIS));
		section.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH, 0));

		boolean isOpen = sectionExpandStates.getOrDefault(sectionDesc, false);

		JPanel sectionHeader = new JPanel();
		sectionHeader.setLayout(new BorderLayout());
		sectionHeader.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH, 0));
		sectionHeader.setOpaque(true);
		sectionHeader.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		sectionHeader.setBorder(new CompoundBorder(
			new MatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			new EmptyBorder(8, 8, 8, 8)));
		
		JButton sectionToggle = new JButton(isOpen ? COLLAPSE_ICON : EXPAND_ICON);
		sectionToggle.setRolloverIcon(isOpen ? COLLAPSE_ICON_HOVER : EXPAND_ICON_HOVER);
		sectionToggle.setPreferredSize(new Dimension(20, 20));
		sectionToggle.setOpaque(false);
		sectionToggle.setContentAreaFilled(false);
		sectionToggle.setFocusPainted(false);
		sectionToggle.setBorder(new EmptyBorder(0, 0, 0, 8));
		sectionToggle.setToolTipText(isOpen ? "Collapse" : "Expand");
		SwingUtil.removeButtonDecorations(sectionToggle);
		sectionHeader.add(sectionToggle, BorderLayout.WEST);

		String name = sectionDesc.getSection().name();
		JLabel sectionName = new JLabel(name);
		sectionName.setForeground(ColorScheme.BRAND_ORANGE);
		sectionName.setFont(FontManager.getRunescapeBoldFont());
		String description = sectionDesc.getSection().description();
		if (!description.isEmpty())
		{
			sectionName.setToolTipText("<html>" + name + ":<br>" + description + "</html>");
		}
		sectionHeader.add(sectionName, BorderLayout.CENTER);
		
		sectionHeader.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				sectionHeader.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
			}
			
			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				sectionHeader.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
		});
		
		section.add(sectionHeader);

		JPanel sectionContents = new JPanel();
		sectionContents.setLayout(new DynamicGridLayout(0, 1, 0, 5));
		sectionContents.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH, 0));
		sectionContents.setOpaque(false);
		sectionContents.setBorder(new CompoundBorder(
			new MatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			new EmptyBorder(BORDER_OFFSET, 0, BORDER_OFFSET, 0)));
		sectionContents.setVisible(isOpen);

		for (ConfigItemDescriptor itemDesc : configDescriptor.getItems())
		{
			if (itemDesc.getItem().section().equals(sectionDesc.key()))
			{
				if (sectionDesc.key().equals("splits"))
				{
					String key = itemDesc.getItem().keyName();
					if (key.equals("targetTotalTime"))
					{
						SplitComparisonMode mode = config.splitComparisonMode();
						if (mode != SplitComparisonMode.TARGET)
						{
							continue;
						}
					}
				}
				JPanel itemPanel = createItemPanel(itemDesc);
				sectionContents.add(itemPanel);
			}
		}

		section.add(sectionContents);
		
		if (sectionDesc.key().equals("statistics"))
		{
			JPanel actionsPanel = new JPanel();
			actionsPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 5, 5));
			actionsPanel.setOpaque(false);
			actionsPanel.setBorder(new EmptyBorder(5, 0, 0, 0));
			
			JButton resetButton = new JButton("Reset All Stats");
			resetButton.addActionListener(e ->
			{
				int result = JOptionPane.showConfirmDialog(
					this,
					"Are you sure you want to reset all statistics? This cannot be undone.",
					"Reset Statistics",
					JOptionPane.YES_NO_OPTION,
					JOptionPane.WARNING_MESSAGE
				);
				if (result == JOptionPane.YES_OPTION)
				{
					plugin.resetStats();
					refreshStats();
				}
			});
			actionsPanel.add(resetButton);
			sectionContents.add(actionsPanel);
		}
		
		java.awt.event.MouseAdapter headerAdapter = new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				toggleSection(sectionDesc, sectionToggle, sectionContents);
			}
		};
		
		sectionToggle.addActionListener(e -> toggleSection(sectionDesc, sectionToggle, sectionContents));
		sectionName.addMouseListener(headerAdapter);
		sectionHeader.addMouseListener(headerAdapter);
		
		return section;
	}
	
	private void toggleSection(ConfigSectionDescriptor sectionDesc, JButton toggleButton, JPanel contents)
	{
		boolean newState = !contents.isVisible();
		contents.setVisible(newState);
		sectionExpandStates.put(sectionDesc, newState);
		
		toggleButton.setIcon(newState ? COLLAPSE_ICON : EXPAND_ICON);
		toggleButton.setRolloverIcon(newState ? COLLAPSE_ICON_HOVER : EXPAND_ICON_HOVER);
		toggleButton.setToolTipText(newState ? "Collapse" : "Expand");
		
		SwingUtilities.invokeLater(() -> {
			contents.revalidate();
			repaint();
		});
	}

	private JPanel createItemPanel(ConfigItemDescriptor itemDesc)
	{
		JPanel item = new JPanel();
		item.setLayout(new BorderLayout());
		item.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH, 0));
		item.setBorder(new EmptyBorder(5, 0, 5, 0));

		String name = itemDesc.getItem().name();
		JLabel configEntryName = new JLabel(name);
		configEntryName.setForeground(Color.WHITE);
		String description = itemDesc.getItem().description();
		if (!description.isEmpty())
		{
			configEntryName.setToolTipText("<html>" + name + ":<br>" + description + "</html>");
		}
		item.add(configEntryName, BorderLayout.CENTER);

		Class<?> type = (Class<?>) itemDesc.getType();
		String key = itemDesc.getItem().keyName();
		String group = configDescriptor.getGroup().value();

		if (type == boolean.class)
		{
			JCheckBox checkbox = new JCheckBox();
			Boolean value = configManager.getConfiguration(group, key, Boolean.class);
			checkbox.setSelected(value != null && value);
			checkbox.addActionListener(e -> configManager.setConfiguration(group, key, checkbox.isSelected()));
			item.add(checkbox, BorderLayout.EAST);
		}
		else if (type == int.class)
		{
			if (key.equals("targetTotalTime"))
			{
				int valueSeconds = MoreObjects.firstNonNull(configManager.getConfiguration(group, key, int.class), 0);
				String timeValue = formatTargetTime(valueSeconds);
				if (timeValue.isEmpty())
				{
					timeValue = "(mm:ss)";
				}
				
				JTextField timeField = new JTextField(timeValue);
				timeField.setColumns(8);
				timeField.setToolTipText("Enter time in seconds (e.g., 95) or MM:SS format (e.g., 1:30)");
				timeField.setPreferredSize(new Dimension(timeField.getPreferredSize().width, timeField.getPreferredSize().height));
				timeField.setMaximumSize(new Dimension(timeField.getPreferredSize().width, timeField.getPreferredSize().height));
				
				final boolean[] isPlaceholder = {timeValue.equals("(mm:ss)")};
				if (isPlaceholder[0])
				{
					timeField.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
				}
				
				timeField.addFocusListener(new java.awt.event.FocusAdapter()
				{
					@Override
					public void focusGained(java.awt.event.FocusEvent e)
					{
						if (isPlaceholder[0])
						{
							timeField.setText("");
							timeField.setForeground(Color.WHITE);
							isPlaceholder[0] = false;
						}
					}
					
					@Override
					public void focusLost(java.awt.event.FocusEvent e)
					{
						String input = timeField.getText().trim();
						int seconds = parseFlexibleTime(input);
						if (seconds >= 0)
						{
							configManager.setConfiguration(group, key, seconds);
							String formatted = formatTargetTime(seconds);
							if (formatted.isEmpty())
							{
								timeField.setText("(mm:ss)");
								timeField.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
								isPlaceholder[0] = true;
							}
							else
							{
								timeField.setText(formatted);
								timeField.setForeground(Color.WHITE);
								isPlaceholder[0] = false;
							}
						}
						else
						{
							int currentValue = MoreObjects.firstNonNull(configManager.getConfiguration(group, key, int.class), 0);
							String formatted = formatTargetTime(currentValue);
							if (formatted.isEmpty())
							{
								timeField.setText("(mm:ss)");
								timeField.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
								isPlaceholder[0] = true;
							}
							else
							{
								timeField.setText(formatted);
								timeField.setForeground(Color.WHITE);
								isPlaceholder[0] = false;
							}
						}
					}
				});
				
				timeField.addActionListener(e -> {
					String input = timeField.getText().trim();
					int seconds = parseFlexibleTime(input);
					if (seconds >= 0)
					{
						configManager.setConfiguration(group, key, seconds);
						String formatted = formatTargetTime(seconds);
						if (formatted.isEmpty())
						{
							timeField.setText("(mm:ss)");
							timeField.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
							isPlaceholder[0] = true;
						}
						else
						{
							timeField.setText(formatted);
							timeField.setForeground(Color.WHITE);
							isPlaceholder[0] = false;
						}
					}
					else
					{
						int currentValue = MoreObjects.firstNonNull(configManager.getConfiguration(group, key, int.class), 0);
						String formatted = formatTargetTime(currentValue);
						if (formatted.isEmpty())
						{
							timeField.setText("(mm:ss)");
							timeField.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
							isPlaceholder[0] = true;
						}
						else
						{
							timeField.setText(formatted);
							timeField.setForeground(Color.WHITE);
							isPlaceholder[0] = false;
						}
					}
				});
				
				JPanel fieldWrapper = new JPanel(new BorderLayout());
				fieldWrapper.setOpaque(false);
				fieldWrapper.add(Box.createHorizontalStrut(5), BorderLayout.WEST);
				fieldWrapper.add(timeField, BorderLayout.CENTER);
				item.add(fieldWrapper, BorderLayout.EAST);
			}
			else
			{
				int value = MoreObjects.firstNonNull(configManager.getConfiguration(group, key, int.class), 0);

				Range range = itemDesc.getRange();
				int min = 0, max = Integer.MAX_VALUE;
				if (range != null)
				{
					min = range.min();
					max = range.max();
				}

				SpinnerModel model = new SpinnerNumberModel(value, min, max, 1);
				JSpinner spinner = new JSpinner(model);
				Component editor = spinner.getEditor();
				JFormattedTextField spinnerTextField = ((JSpinner.DefaultEditor) editor).getTextField();
				spinnerTextField.setColumns(SPINNER_FIELD_WIDTH);
				spinner.addChangeListener(e -> configManager.setConfiguration(group, key, ((Number) spinner.getValue()).intValue()));
				item.add(spinner, BorderLayout.EAST);
			}
		}
		else if (type == String.class)
		{
			String value = MoreObjects.firstNonNull(configManager.getConfiguration(group, key, String.class), "");
			JTextField textField = new JTextField(value);
			textField.setColumns(10);
			textField.addActionListener(e -> configManager.setConfiguration(group, key, textField.getText()));
			textField.addFocusListener(new java.awt.event.FocusAdapter()
			{
				@Override
				public void focusLost(java.awt.event.FocusEvent e)
				{
					configManager.setConfiguration(group, key, textField.getText());
				}
			});
			item.add(textField, BorderLayout.SOUTH);
		}
		else if (type.isEnum())
		{
			@SuppressWarnings("unchecked")
			Enum<?> value = MoreObjects.firstNonNull(
				configManager.getConfiguration(group, key, (Class<Enum<?>>) type),
				((Enum<?>[]) type.getEnumConstants())[0]);
			@SuppressWarnings({"unchecked", "rawtypes"})
			JComboBox comboBox = new JComboBox(type.getEnumConstants());
			comboBox.setSelectedItem(value);
			comboBox.setPreferredSize(new Dimension(120, comboBox.getPreferredSize().height));
			comboBox.addActionListener(e ->
			{
				configManager.setConfiguration(group, key, comboBox.getSelectedItem());
				if (key.equals("splitComparisonMode"))
				{
					rebuildSplitsSection();
				}
			});
			item.add(comboBox, BorderLayout.EAST);
		}

		return item;
	}
	
	private final JPanel lootContainer = new JPanel();
	private final List<AraxxorLootBox> lootBoxes = new ArrayList<>();
	private final JButton collapseAllBtn = new JButton();
	private static final ImageIcon COLLAPSE_ICON;
	private static final ImageIcon EXPAND_ICON;
	private static final ImageIcon COLLAPSE_ICON_HOVER;
	private static final ImageIcon EXPAND_ICON_HOVER;
	
	static
	{
		COLLAPSE_ICON = createCollapseIcon(false);
		EXPAND_ICON = createExpandIcon(false);
		COLLAPSE_ICON_HOVER = createCollapseIcon(true);
		EXPAND_ICON_HOVER = createExpandIcon(true);
	}
	
	private static ImageIcon createCollapseIcon(boolean hover)
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		
		g.setColor(hover ? Color.WHITE : ColorScheme.MEDIUM_GRAY_COLOR);
		g.setStroke(new java.awt.BasicStroke(2.5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
		g.drawLine(4, 6, 8, 10);
		g.drawLine(8, 10, 12, 6);
		
		g.dispose();
		return new ImageIcon(img);
	}
	
	private static ImageIcon createExpandIcon(boolean hover)
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		
		g.setColor(hover ? Color.WHITE : ColorScheme.MEDIUM_GRAY_COLOR);
		g.setStroke(new java.awt.BasicStroke(2.5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
		g.drawLine(6, 4, 10, 8);
		g.drawLine(10, 8, 6, 12);
		
		g.dispose();
		return new ImageIcon(img);
	}
	
	private enum ViewMode
	{
		ALL,
		BY_KILL,
		BY_SESSION
	}
	
	private ViewMode currentViewMode = ViewMode.BY_SESSION;
	
	private static final ImageIcon ALL_VIEW_ICON;
	private static final ImageIcon ALL_VIEW_ICON_FADED;
	private static final ImageIcon ALL_VIEW_ICON_HOVER;
	private static final ImageIcon BY_KILL_VIEW_ICON;
	private static final ImageIcon BY_KILL_VIEW_ICON_FADED;
	private static final ImageIcon BY_KILL_VIEW_ICON_HOVER;
	private static final ImageIcon BY_SESSION_VIEW_ICON;
	private static final ImageIcon BY_SESSION_VIEW_ICON_FADED;
	private static final ImageIcon BY_SESSION_VIEW_ICON_HOVER;
	
	static
	{
		ALL_VIEW_ICON = createAllIcon(true);
		ALL_VIEW_ICON_FADED = createAllIcon(false);
		ALL_VIEW_ICON_HOVER = createAllIconHover();
		
		BY_KILL_VIEW_ICON = createKillIcon(true);
		BY_KILL_VIEW_ICON_FADED = createKillIcon(false);
		BY_KILL_VIEW_ICON_HOVER = createKillIconHover();
		
		BY_SESSION_VIEW_ICON = createSessionIcon(true);
		BY_SESSION_VIEW_ICON_FADED = createSessionIcon(false);
		BY_SESSION_VIEW_ICON_HOVER = createSessionIconHover();
	}
	
	private static ImageIcon createAllIcon(boolean selected)
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		
		g.setColor(selected ? ColorScheme.BRAND_ORANGE : ColorScheme.MEDIUM_GRAY_COLOR);
		g.setStroke(new java.awt.BasicStroke(2.5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
		g.drawLine(2, 6, 14, 6);
		g.drawLine(2, 10, 14, 10);
		
		g.dispose();
		return new ImageIcon(img);
	}
	
	private static ImageIcon createAllIconHover()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		
		g.setColor(Color.WHITE);
		g.setStroke(new java.awt.BasicStroke(2.5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
		g.drawLine(2, 6, 14, 6);
		g.drawLine(2, 10, 14, 10);
		
		g.dispose();
		return new ImageIcon(img);
	}
	
	private static ImageIcon createKillIcon(boolean selected)
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		
		g.setColor(selected ? ColorScheme.BRAND_ORANGE : ColorScheme.MEDIUM_GRAY_COLOR);
		g.setStroke(new java.awt.BasicStroke(2.5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
		g.drawLine(8, 3, 8, 13);
		
		g.dispose();
		return new ImageIcon(img);
	}
	
	private static ImageIcon createKillIconHover()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		
		g.setColor(Color.WHITE);
		g.setStroke(new java.awt.BasicStroke(2.5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
		g.drawLine(8, 3, 8, 13);
		
		g.dispose();
		return new ImageIcon(img);
	}
	
	private static ImageIcon createSessionIcon(boolean selected)
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		
		g.setColor(selected ? ColorScheme.BRAND_ORANGE : ColorScheme.MEDIUM_GRAY_COLOR);
		g.setStroke(new java.awt.BasicStroke(2.5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
		g.drawLine(2, 5, 14, 5);
		g.drawLine(2, 8, 14, 8);
		g.drawLine(2, 11, 14, 11);
		
		g.dispose();
		return new ImageIcon(img);
	}
	
	private static ImageIcon createSessionIconHover()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		
		g.setColor(Color.WHITE);
		g.setStroke(new java.awt.BasicStroke(2.5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
		g.drawLine(2, 5, 14, 5);
		g.drawLine(2, 8, 14, 8);
		g.drawLine(2, 11, 14, 11);
		
		g.dispose();
		return new ImageIcon(img);
	}
	
	private JPanel splitsSectionPanel = null;
	private JPanel mainPanel = null;
	
	private final Map<ConfigSectionDescriptor, Boolean> sectionExpandStates = new java.util.HashMap<>();
	
	private JToggleButton createViewModeButton(
		ImageIcon normalIcon, ImageIcon hoverIcon, ImageIcon selectedIcon,
		boolean selected, String tooltip, java.awt.event.ActionListener listener)
	{
		JToggleButton btn = new JToggleButton();
		SwingUtil.removeButtonDecorations(btn);
		btn.setIcon(normalIcon);
		btn.setRolloverIcon(hoverIcon);
		btn.setSelectedIcon(selectedIcon);
		btn.setSelected(selected);
		btn.setToolTipText(tooltip);
		btn.setPreferredSize(new Dimension(20, 20));
		btn.setOpaque(false);
		btn.setContentAreaFilled(false);
		btn.setFocusPainted(false);
		
		updateButtonBorder(btn, selected);
		btn.addItemListener(e -> updateButtonBorder(btn, btn.isSelected()));
		
		btn.addActionListener(listener);
		return btn;
	}
	
	private void updateButtonBorder(JToggleButton btn, boolean selected)
	{
		if (selected)
		{
			btn.setBorder(new CompoundBorder(
				new MatteBorder(2, 2, 2, 2, ColorScheme.BRAND_ORANGE),
				new EmptyBorder(1, 1, 1, 1)));
		}
		else
		{
			btn.setBorder(new EmptyBorder(3, 3, 3, 3));
		}
	}
	
	private JPanel createFilterBar()
	{
		JPanel filterBar = new JPanel();
		filterBar.setLayout(new FlowLayout(FlowLayout.RIGHT, 3, 0));
		filterBar.setOpaque(false);
		filterBar.setBorder(new EmptyBorder(0, 5, 0, 5));
		
		javax.swing.ButtonGroup viewModeGroup = new javax.swing.ButtonGroup();
		
		JToggleButton showAllBtn = createViewModeButton(
			ALL_VIEW_ICON_FADED, ALL_VIEW_ICON_HOVER, ALL_VIEW_ICON,
			currentViewMode == ViewMode.ALL,
			"Show all kills aggregated",
			e -> {
				currentViewMode = ViewMode.ALL;
				rebuildLootTracker();
			}
		);
		viewModeGroup.add(showAllBtn);
		filterBar.add(showAllBtn);
		
		JToggleButton showByKillBtn = createViewModeButton(
			BY_KILL_VIEW_ICON_FADED, BY_KILL_VIEW_ICON_HOVER, BY_KILL_VIEW_ICON,
			currentViewMode == ViewMode.BY_KILL,
			"Show individual kills",
			e -> {
				currentViewMode = ViewMode.BY_KILL;
				rebuildLootTracker();
			}
		);
		viewModeGroup.add(showByKillBtn);
		filterBar.add(showByKillBtn);
		
		JToggleButton showBySessionBtn = createViewModeButton(
			BY_SESSION_VIEW_ICON_FADED, BY_SESSION_VIEW_ICON_HOVER, BY_SESSION_VIEW_ICON,
			currentViewMode == ViewMode.BY_SESSION,
			"Show sessions by day",
			e -> {
				currentViewMode = ViewMode.BY_SESSION;
				rebuildLootTracker();
			}
		);
		viewModeGroup.add(showBySessionBtn);
		filterBar.add(showBySessionBtn);
		
		filterBar.add(Box.createRigidArea(new Dimension(10, 0)));
		
		SwingUtil.removeButtonDecorations(collapseAllBtn);
		collapseAllBtn.setIcon(EXPAND_ICON);
		collapseAllBtn.setRolloverIcon(EXPAND_ICON_HOVER);
		collapseAllBtn.setSelectedIcon(COLLAPSE_ICON);
		collapseAllBtn.setRolloverSelectedIcon(COLLAPSE_ICON_HOVER);
		SwingUtil.addModalTooltip(collapseAllBtn, "Expand All", "Collapse All");
		collapseAllBtn.setOpaque(false);
		collapseAllBtn.setContentAreaFilled(false);
		collapseAllBtn.setFocusPainted(false);
		collapseAllBtn.setPreferredSize(new Dimension(20, 20));
		collapseAllBtn.addActionListener(e -> toggleCollapseAll());
		filterBar.add(collapseAllBtn);
		
		return filterBar;
	}
	
	private JPanel createLootTrackerSection()
	{
		JPanel section = new JPanel();
		section.setLayout(new BorderLayout());
		section.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH, 0));
		section.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		
		section.setBorder(new CompoundBorder(
			new MatteBorder(1, 0, 0, 0, ColorScheme.DARK_GRAY_COLOR),
			new EmptyBorder(10, 0, 0, 0)));
		
		lootContainer.setLayout(new DynamicGridLayout(0, 1, 0, 0));
		lootContainer.setOpaque(false);
		
		section.add(lootContainer, BorderLayout.CENTER);
		
		rebuildLootTracker();
		
		return section;
	}
	
	/**
	 * Get all historical kills from config (for BY_SESSION view)
	 */
	private List<AraxxorKillRecord> getAllKillsFromConfig()
	{
		List<AraxxorKillRecord> allKills = new ArrayList<>();
		
		if (configManager == null)
		{
			return allKills;
		}
		
		java.time.Instant cutoff = java.time.Instant.now().minus(java.time.Duration.ofDays(365L));
		List<String> fullKeys = configManager.getConfigurationKeys("arraxxor.kill_");
		
		for (String fullKey : fullKeys)
		{
			String[] parts = fullKey.split("\\.", 2);
			if (parts.length != 2)
			{
				continue;
			}
			String key = parts[1];
			
			String value = configManager.getConfiguration("arraxxor", key, String.class);
			if (value == null)
			{
				continue;
			}
			
			try
			{
				String[] valueParts = value.split("\\|");
				if (valueParts.length >= 7)
				{
					long timestamp = Long.parseLong(valueParts[0]);
					
					if (java.time.Instant.ofEpochMilli(timestamp).isBefore(cutoff))
					{
						continue;
					}
					
					AraxxorKillRecord kill = new AraxxorKillRecord();
					kill.setTimestamp(timestamp);
					kill.setKillTime(Long.parseLong(valueParts[1]));
					try
					{
						kill.setRotation(AraxxorEggType.valueOf(valueParts[2]));
					}
					catch (IllegalArgumentException e)
					{
						kill.setRotation(null);
					}
					kill.setLootValue(Long.parseLong(valueParts[3]));
					kill.setHits(Integer.parseInt(valueParts[4]));
					kill.setDamageDealt(Integer.parseInt(valueParts[5]));
					kill.setDamageTaken(Integer.parseInt(valueParts[6]));
					
					if (valueParts.length >= 8 && !valueParts[7].isEmpty())
					{
						String[] lootItems = valueParts[7].split(",");
						for (String lootItem : lootItems)
						{
							String[] itemParts = lootItem.split(":");
							if (itemParts.length == 2)
							{
								int itemId = Integer.parseInt(itemParts[0]);
								long quantity = Long.parseLong(itemParts[1]);
								kill.getLoot().put(itemId, quantity);
							}
						}
					}
					
					allKills.add(kill);
				}
			}
			catch (Exception e)
			{
				continue;
			}
		}
		
		return allKills;
	}
	
	private void rebuildLootTracker()
	{
		net.runelite.client.util.SwingUtil.fastRemoveAll(lootContainer);
		lootBoxes.clear();
		
		List<AraxxorKillRecord> kills = (currentViewMode == ViewMode.BY_SESSION) 
			? getAllKillsFromConfig() 
			: plugin.getSessionKills();
		
		if (kills.isEmpty())
		{
			collapseAllBtn.setVisible(false);
			JLabel emptyLabel = new JLabel("No kills tracked yet");
			emptyLabel.setFont(FontManager.getRunescapeSmallFont());
			emptyLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			emptyLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
			lootContainer.add(emptyLabel);
			lootContainer.revalidate();
			lootContainer.repaint();
			return;
		}
		
		java.util.Set<Integer> uniqueItemIds = new java.util.HashSet<>();
		for (AraxxorKillRecord kill : kills)
		{
			if (kill.getLoot() != null)
			{
				for (int itemId : kill.getLoot().keySet())
				{
					uniqueItemIds.add(itemId);
				}
			}
		}
		
		boolean cachedSync = false;
		if (clientThread != null)
		{
			try
			{
				if (!uniqueItemIds.isEmpty())
				{
					int testItemId = uniqueItemIds.iterator().next();
					cacheItemPrice(testItemId);
					cachedSync = true;
					
					for (int itemId : uniqueItemIds)
					{
						cacheItemPrice(itemId);
					}
				}
			}
			catch (IllegalStateException e)
			{
				cachedSync = false;
			}
		}
		
		if (!cachedSync && clientThread != null && !uniqueItemIds.isEmpty())
		{
			java.util.Set<Integer> finalItemIds = new java.util.HashSet<>(uniqueItemIds);
			clientThread.invokeLater(() -> {
				for (int itemId : finalItemIds)
				{
					cacheItemPrice(itemId);
				}
			});
		}
		
		collapseAllBtn.setVisible(true);
		
		switch (currentViewMode)
		{
			case ALL:
				buildAllView(kills);
				break;
			case BY_KILL:
				buildByKillView(kills);
				break;
			case BY_SESSION:
				buildBySessionView(kills);
				break;
		}
		
		lootContainer.revalidate();
		lootContainer.repaint();
		
		updateCollapseButtonState();
	}
	
	private void updateCollapseButtonState()
	{
		boolean allCollapsed = lootBoxes.isEmpty() || lootBoxes.stream().allMatch(AraxxorLootBox::isCollapsed);
		collapseAllBtn.setSelected(!allCollapsed);
	}
	
	private void buildAllView(List<AraxxorKillRecord> allKills)
	{
		Map<Integer, Long> aggregatedLoot = new java.util.HashMap<>();
		long bestTime = Long.MAX_VALUE;
		AraxxorEggType bestRotation = null;
		
		for (AraxxorKillRecord kill : allKills)
		{
			if (kill.getLoot() != null)
			{
				for (Map.Entry<Integer, Long> entry : kill.getLoot().entrySet())
				{
					aggregatedLoot.merge(entry.getKey(), entry.getValue(), Long::sum);
				}
			}
			
			if (kill.getKillTime() < bestTime)
			{
				bestTime = kill.getKillTime();
				bestRotation = kill.getRotation();
			}
		}
		
		AraxxorLootBox allBox = new AraxxorLootBox(itemManager);
		allBox.buildAllView(aggregatedLoot, allKills.size(), bestTime, bestRotation, this);
		lootBoxes.add(allBox);
		lootContainer.add(allBox);
	}
	
	private void buildByKillView(List<AraxxorKillRecord> allKills)
	{
		List<AraxxorKillRecord> sortedKills = new ArrayList<>(allKills);
		sortedKills.sort(Comparator.comparingLong(AraxxorKillRecord::getTimestamp).reversed());
		
		final int MAX_KILLS = 100;
		int killCount = Math.min(MAX_KILLS, sortedKills.size());
		
		for (int i = 0; i < killCount; i++)
		{
			AraxxorKillRecord kill = sortedKills.get(i);
			int killNumber = i + 1;
			
			AraxxorLootBox killBox = new AraxxorLootBox(itemManager);
			killBox.buildKill(kill, killNumber, this);
			lootBoxes.add(killBox);
			lootContainer.add(killBox);
		}
	}
	
	private void buildBySessionView(List<AraxxorKillRecord> allKills)
	{
		List<List<AraxxorKillRecord>> sessions = plugin.groupKillsIntoSessions(allKills);
		
		if (sessions.isEmpty())
		{
			return;
		}
		
		// Group sessions by day
		Map<String, List<SessionWithIndex>> sessionsByDay = new java.util.LinkedHashMap<>();
		// Show up to 100 sessions (performance should be fine since we're just rendering UI)
		int sessionCount = Math.min(50, sessions.size());
		
		for (int i = sessions.size() - 1; i >= sessions.size() - sessionCount; i--)
		{
			List<AraxxorKillRecord> sessionKills = sessions.get(i);
			AraxxorPlugin.SessionSummary summary = plugin.calculateSessionSummary(sessionKills);
			if (summary == null)
			{
				continue;
			}
			
			// Get day key (MM/dd format)
			String dayKey = formatDayKey(summary.getSessionStartTime());
			sessionsByDay.computeIfAbsent(dayKey, k -> new ArrayList<>())
				.add(new SessionWithIndex(sessionKills, summary, sessions.size() - i, i == sessions.size() - 1));
		}
		
		// Create day accordions with nested sessions
		for (Map.Entry<String, List<SessionWithIndex>> dayEntry : sessionsByDay.entrySet())
		{
			String dayKey = dayEntry.getKey();
			List<SessionWithIndex> daySessions = dayEntry.getValue();
			
			if (daySessions.size() == 1)
			{
				// Single session on this day - display directly without nesting
				SessionWithIndex sessionData = daySessions.get(0);
				AraxxorLootBox sessionBox = new AraxxorLootBox(itemManager);
				sessionBox.buildSession(sessionData.sessionKills, sessionData.summary, sessionData.sessionNumber, this);
				
				if (!sessionData.isCurrentSession)
				{
					sessionBox.collapse();
				}
				
				lootBoxes.add(sessionBox);
				lootContainer.add(sessionBox);
			}
			else
			{
				// Multiple sessions on same day - create nested accordion
				JPanel dayAccordion = createDayAccordion(dayKey, daySessions);
				lootContainer.add(dayAccordion);
			}
		}
	}
	
	private static class SessionWithIndex
	{
		final List<AraxxorKillRecord> sessionKills;
		final AraxxorPlugin.SessionSummary summary;
		final int sessionNumber;
		final boolean isCurrentSession;
		
		SessionWithIndex(List<AraxxorKillRecord> sessionKills, AraxxorPlugin.SessionSummary summary, 
			int sessionNumber, boolean isCurrentSession)
		{
			this.sessionKills = sessionKills;
			this.summary = summary;
			this.sessionNumber = sessionNumber;
			this.isCurrentSession = isCurrentSession;
		}
	}
	
	/**
	 * Format timestamp to day key (MM/dd)
	 */
	private String formatDayKey(long timestamp)
	{
		java.time.LocalDateTime dateTime = java.time.LocalDateTime.ofInstant(
			java.time.Instant.ofEpochMilli(timestamp), 
			java.time.ZoneId.systemDefault());
		java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("MM/dd");
		return dateTime.format(formatter);
	}
	
	/**
	 * Create a day-level accordion containing nested sessions
	 */
	private JPanel createDayAccordion(String dayKey, List<SessionWithIndex> daySessions)
	{
		JPanel dayAccordion = new JPanel();
		dayAccordion.setLayout(new BoxLayout(dayAccordion, BoxLayout.Y_AXIS));
		dayAccordion.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH, 0));
		dayAccordion.setOpaque(true);
		dayAccordion.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		dayAccordion.setBorder(new CompoundBorder(
			new MatteBorder(1, 1, 1, 1, ColorScheme.DARK_GRAY_COLOR),
			new EmptyBorder(0, 0, 0, 0)
		));
		
		// Calculate day totals
		int totalKills = daySessions.stream().mapToInt(s -> s.sessionKills.size()).sum();
		long bestTime = daySessions.stream()
			.mapToLong(s -> s.summary.getBestTime())
			.min()
			.orElse(Long.MAX_VALUE);
		AraxxorEggType bestRotation = daySessions.stream()
			.filter(s -> s.summary.getBestTime() == bestTime)
			.map(s -> s.summary.getBestRotation())
			.findFirst()
			.orElse(null);
		
		// Aggregate loot for the day
		Map<Integer, Long> dayAggregatedLoot = new java.util.HashMap<>();
		for (SessionWithIndex sessionData : daySessions)
		{
			for (AraxxorKillRecord kill : sessionData.sessionKills)
			{
				if (kill.getLoot() != null)
				{
					for (Map.Entry<Integer, Long> entry : kill.getLoot().entrySet())
					{
						dayAggregatedLoot.merge(entry.getKey(), entry.getValue(), Long::sum);
					}
				}
			}
		}
		
		String cacheKey = "day_" + dayKey + "_" + totalKills;
		long totalGP = calculateGPWithCache(dayAggregatedLoot, cacheKey);
		
		// Day header (accordion toggle)
		JPanel dayHeader = new JPanel();
		dayHeader.setLayout(new BorderLayout());
		dayHeader.setMinimumSize(new Dimension(PluginPanel.PANEL_WIDTH, 0));
		dayHeader.setOpaque(true);
		dayHeader.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		dayHeader.setBorder(new CompoundBorder(
			new MatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			new EmptyBorder(10, 4, 10, 12)
		));
		
		// Toggle button
		JButton dayToggle = new JButton(EXPAND_ICON);
		dayToggle.setRolloverIcon(EXPAND_ICON_HOVER);
		dayToggle.setPreferredSize(new Dimension(20, 20));
		dayToggle.setOpaque(false);
		dayToggle.setContentAreaFilled(false);
		dayToggle.setFocusPainted(false);
		dayToggle.setBorder(new EmptyBorder(0, 0, 0, 2));
		dayToggle.setToolTipText("Expand");
		SwingUtil.removeButtonDecorations(dayToggle);
		dayHeader.add(dayToggle, BorderLayout.WEST);
		
		// Check if any session in this day contains unique drops
		boolean hasUnique = daySessions.stream().anyMatch(sessionData -> {
			Map<Integer, Long> aggregatedLoot = new java.util.HashMap<>();
			for (AraxxorKillRecord kill : sessionData.sessionKills)
			{
				if (kill.getLoot() != null)
				{
					for (Map.Entry<Integer, Long> entry : kill.getLoot().entrySet())
					{
						aggregatedLoot.merge(entry.getKey(), entry.getValue(), Long::sum);
					}
				}
			}
			return aggregatedLoot.keySet().stream().anyMatch(AraxxorLootBox::isUniqueDrop);
		});
		
		// Day label with stats
		JPanel dayLabelPanel = new JPanel();
		dayLabelPanel.setLayout(new BoxLayout(dayLabelPanel, BoxLayout.X_AXIS));
		dayLabelPanel.setOpaque(false);
		
		// Add subtle star icon if day contains unique drops
		if (hasUnique)
		{
			JLabel starLabel = new JLabel("★");
			starLabel.setFont(FontManager.getRunescapeSmallFont());
			starLabel.setForeground(new Color(255, 215, 0, 200)); // Subtle golden color
			starLabel.setToolTipText("Contains unique drop");
			dayLabelPanel.add(starLabel);
			dayLabelPanel.add(Box.createRigidArea(new Dimension(3, 0))); // Small spacing
		}
		
		JLabel dayLabel = new JLabel(dayKey + "  - " + totalKills + "KC");
		dayLabel.setFont(FontManager.getRunescapeSmallFont());
		dayLabel.setForeground(Color.WHITE);
		dayLabelPanel.add(dayLabel);
		
		dayHeader.add(dayLabelPanel, BorderLayout.CENTER);
		
		// Total GP on the right
		JLabel gpLabel = new JLabel(QuantityFormatter.quantityToStackSize(totalGP) + " gp");
		gpLabel.setFont(FontManager.getRunescapeSmallFont());
		gpLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		dayHeader.add(gpLabel, BorderLayout.EAST);
		
		// Hover effect
		dayHeader.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				dayHeader.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				dayHeader.setBorder(new CompoundBorder(
					new MatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
					new EmptyBorder(10, 4, 10, 12)
				));
			}
			
			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				dayHeader.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				dayHeader.setBorder(new CompoundBorder(
					new MatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
					new EmptyBorder(10, 4, 10, 12)
				));
			}
		});
		
		// Sessions container (collapsible)
		JPanel sessionsContainer = new JPanel();
		sessionsContainer.setLayout(new DynamicGridLayout(0, 1, 0, 0));
		sessionsContainer.setOpaque(false);
		sessionsContainer.setBorder(new EmptyBorder(0, 0, 0, 0));
		
		// Add sessions (sorted by time, newest first)
		daySessions.sort((a, b) -> Long.compare(b.summary.getSessionStartTime(), a.summary.getSessionStartTime()));
		
		boolean hasCurrentSession = daySessions.stream().anyMatch(s -> s.isCurrentSession);
		
		// Start expanded if this day contains the current session
		sessionsContainer.setVisible(hasCurrentSession);
		
		for (SessionWithIndex sessionData : daySessions)
		{
			AraxxorLootBox sessionBox = new AraxxorLootBox(itemManager);
			sessionBox.buildSessionNested(sessionData.sessionKills, sessionData.summary, sessionData.sessionNumber, this);
			
			// Only expand the current session, collapse others
			if (!sessionData.isCurrentSession)
			{
				sessionBox.collapse();
			}
			
			lootBoxes.add(sessionBox);
			sessionsContainer.add(sessionBox);
		}
		
		// Update toggle button state based on initial visibility
		dayToggle.setIcon(hasCurrentSession ? COLLAPSE_ICON : EXPAND_ICON);
		dayToggle.setRolloverIcon(hasCurrentSession ? COLLAPSE_ICON_HOVER : EXPAND_ICON_HOVER);
		dayToggle.setToolTipText(hasCurrentSession ? "Collapse" : "Expand");
		
		dayAccordion.add(dayHeader);
		dayAccordion.add(sessionsContainer);
		
		// Toggle functionality
		java.awt.event.MouseAdapter headerAdapter = new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e)
			{
				toggleDayAccordion(dayToggle, sessionsContainer);
			}
		};
		
		dayToggle.addActionListener(e -> toggleDayAccordion(dayToggle, sessionsContainer));
		dayHeader.addMouseListener(headerAdapter);
		
		return dayAccordion;
	}
	
	private void toggleDayAccordion(JButton toggleButton, JPanel sessionsContainer)
	{
		boolean newState = !sessionsContainer.isVisible();
		sessionsContainer.setVisible(newState);
		
		toggleButton.setIcon(newState ? COLLAPSE_ICON : EXPAND_ICON);
		toggleButton.setRolloverIcon(newState ? COLLAPSE_ICON_HOVER : EXPAND_ICON_HOVER);
		toggleButton.setToolTipText(newState ? "Collapse" : "Expand");
		
		SwingUtilities.invokeLater(() -> {
			sessionsContainer.revalidate();
			repaint();
		});
	}
	
	private void toggleCollapseAll()
	{
		boolean allCollapsed = lootBoxes.stream().allMatch(AraxxorLootBox::isCollapsed);
		
		for (AraxxorLootBox box : lootBoxes)
		{
			if (allCollapsed)
			{
				box.expand();
			}
			else
			{
				box.collapse();
			}
		}
		
		collapseAllBtn.setSelected(!allCollapsed);
	}
	
}

