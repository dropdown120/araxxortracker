package net.runelite.client.plugins.araxxortracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.QuantityFormatter;

/**
 * Collapsible box for displaying Araxxor loot, similar to LootTrackerBox
 * Can represent a Session, Trip, or Kill
 */
class AraxxorLootBox extends JPanel
{
	private static final int ITEMS_PER_ROW = 4;
	
	private static final int HTML_ESCAPE_CACHE_SIZE = 100;
	private static final Map<String, String> htmlEscapeCache = new java.util.LinkedHashMap<String, String>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
			return size() > HTML_ESCAPE_CACHE_SIZE;
		}
	};
	
	private static final java.util.Set<Integer> UNIQUE_DROP_IDS = java.util.Collections.unmodifiableSet(
		java.util.stream.Stream.of(
			29790, 29792, 29794, 29799, 29788, 29786, 29781, 29836, 29838
		).collect(java.util.stream.Collectors.toSet())
	);
	
	/**
	 * Check if an item is an OSRS Araxxor unique drop
	 */
	static boolean isUniqueDrop(int itemId)
	{
		return UNIQUE_DROP_IDS.contains(itemId);
	}
	
	/**
	 * Check if a loot map contains any unique drops
	 */
	private static boolean hasUniqueDrops(Map<Integer, Long> loot)
	{
		if (loot == null || loot.isEmpty())
		{
			return false;
		}
		return loot.keySet().stream().anyMatch(AraxxorLootBox::isUniqueDrop);
	}
	
	/**
	 * Create a subtle golden star icon for unique drop indicator
	 */
	private static JLabel createStarIcon()
	{
		JLabel starLabel = new JLabel("★");
		starLabel.setFont(FontManager.getRunescapeSmallFont());
		starLabel.setForeground(new Color(255, 215, 0, 200));
		starLabel.setToolTipText("Contains unique drop");
		return starLabel;
	}
	
	private final JPanel contentContainer = new JPanel();
	private final JPanel titleBar = new JPanel();
	private final JLabel titleLabel = new JLabel();
	private final JLabel subtitleLabel = new JLabel();
	private final JLabel priceLabel = new JLabel();
	private final ItemManager itemManager;
	
	private boolean collapsed = false;
	
	AraxxorLootBox(ItemManager itemManager)
	{
		this.itemManager = itemManager;
		
		setLayout(new BorderLayout(0, 0));
		setBorder(new CompoundBorder(
			new MatteBorder(1, 1, 1, 1, ColorScheme.DARK_GRAY_COLOR), // Use standard color scheme
			new EmptyBorder(0, 0, 0, 0) // No inner border
		));
		setOpaque(true);
		setBackground(ColorScheme.DARKER_GRAY_COLOR); // Background for the entire box
		
		// Title bar - modern, clean styling with subtle borders
		titleBar.setLayout(new BoxLayout(titleBar, BoxLayout.X_AXIS));
		titleBar.setBorder(new CompoundBorder(
			new MatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			new EmptyBorder(10, 12, 10, 12)
		));
		titleBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		titleBar.setOpaque(true);
		titleBar.setPreferredSize(new Dimension(0, 38));
		
		titleBar.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				titleBar.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
				titleBar.setBorder(new CompoundBorder(
					new MatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
					new EmptyBorder(10, 12, 10, 12)
				));
			}
			
			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				titleBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				titleBar.setBorder(new CompoundBorder(
					new MatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
					new EmptyBorder(10, 12, 10, 12)
				));
			}
			
			@Override
			public void mousePressed(java.awt.event.MouseEvent e)
			{
				titleBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			}
			
			@Override
			public void mouseReleased(java.awt.event.MouseEvent e)
			{
				if (titleBar.contains(e.getPoint()))
				{
					titleBar.setBackground(ColorScheme.DARKER_GRAY_HOVER_COLOR);
					titleBar.setBorder(new CompoundBorder(
						new MatteBorder(0, 0, 1, 0, ColorScheme.MEDIUM_GRAY_COLOR),
						new EmptyBorder(10, 12, 10, 12)
					));
				}
			}
		});
		
		addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				if (!titleBar.contains(e.getPoint()))
				{
					setBorder(new CompoundBorder(
						new MatteBorder(1, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR),
						new EmptyBorder(0, 0, 0, 0)
					));
				}
			}
			
			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				setBorder(new CompoundBorder(
					new MatteBorder(1, 1, 1, 1, ColorScheme.DARK_GRAY_COLOR),
					new EmptyBorder(0, 0, 0, 0)
				));
			}
		});
		
		titleLabel.setFont(FontManager.getRunescapeSmallFont());
		titleLabel.setForeground(Color.WHITE);
		titleBar.add(titleLabel);
		
		titleBar.add(Box.createRigidArea(new Dimension(5, 0)));
		
		subtitleLabel.setFont(FontManager.getRunescapeSmallFont());
		subtitleLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		// HTML wrapping will handle long text - no truncation needed
		titleBar.add(subtitleLabel);
		
		titleBar.add(Box.createHorizontalGlue());
		titleBar.add(Box.createRigidArea(new Dimension(5, 0)));
		
		priceLabel.setFont(FontManager.getRunescapeSmallFont());
		priceLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		titleBar.add(priceLabel);
		
		add(titleBar, BorderLayout.NORTH);
		add(contentContainer, BorderLayout.CENTER);
		
		// Click to collapse/expand
		titleBar.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e.getButton() == MouseEvent.BUTTON1)
				{
					toggleCollapse();
				}
			}
		});
		
		// Start expanded
		contentContainer.setVisible(true);
	}
	
	void setTitle(String title)
	{
		// Store full title for tooltip
		String fullTitle = title;
		
		// Use HTML to wrap text - calculate max width based on panel width
		// PluginPanel.PANEL_WIDTH is typically ~225px, account for padding and price label (~50px)
		int maxWidth = 120; // Approximate available width for title before wrapping
		String htmlTitle = String.format("<html><body style='width: %dpx; color: white; word-wrap: break-word;'>%s</body></html>", 
			maxWidth, escapeHtml(title));
		titleLabel.setText(htmlTitle);
		
		// Add tooltip with full text (always show full text on hover)
		titleLabel.setToolTipText(fullTitle);
	}
	
	void setSubtitle(String subtitle)
	{
		if (subtitle == null || subtitle.isEmpty())
		{
			subtitleLabel.setText("");
			subtitleLabel.setToolTipText(null);
			return;
		}
		
		// Store full subtitle for tooltip
		String fullSubtitle = subtitle;
		
		// Use HTML to wrap text - allow more width for subtitle
		int maxWidth = 120; // Approximate available width for subtitle before wrapping
		String htmlSubtitle = String.format("<html><body style='width: %dpx; color: #989898; word-wrap: break-word;'>%s</body></html>", 
			maxWidth, escapeHtml(subtitle));
		subtitleLabel.setText(htmlSubtitle);
		
		// Add tooltip with full text (always show full text on hover)
		subtitleLabel.setToolTipText(fullSubtitle);
	}
	
	/**
	 * Escape HTML special characters
	 * Performance: Caches results to avoid repeated processing of same strings
	 */
	private String escapeHtml(String text)
	{
		if (text == null) return "";
		
		synchronized (htmlEscapeCache) {
			String cached = htmlEscapeCache.get(text);
			if (cached != null) {
				return cached;
			}
		}
		
		// Escape HTML special characters
		String escaped = text.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;")
			.replace("'", "&#39;");
		
		synchronized (htmlEscapeCache) {
			htmlEscapeCache.put(text, escaped);
		}
		
		return escaped;
	}
	
	void setPrice(long price)
	{
		priceLabel.setText(QuantityFormatter.quantityToStackSize(price) + " gp");
	}
	
	void toggleCollapse()
	{
		if (collapsed)
		{
			expand();
		}
		else
		{
			collapse();
		}
	}
	
	void collapse()
	{
		if (!collapsed)
		{
			contentContainer.setVisible(false);
			collapsed = true;
			applyDimmer(false);
			// Update background when collapsed - use standard color scheme
			titleBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			titleBar.setBorder(new CompoundBorder(
				new MatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
				new EmptyBorder(10, 12, 10, 12)
			));
		}
	}
	
	void expand()
	{
		if (collapsed)
		{
			contentContainer.setVisible(true);
			collapsed = false;
			applyDimmer(true);
			// Update background when expanded - use standard color scheme
			titleBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			titleBar.setBorder(new CompoundBorder(
				new MatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
				new EmptyBorder(10, 12, 10, 12)
			));
		}
	}
	
	boolean isCollapsed()
	{
		return collapsed;
	}
	
	private void applyDimmer(boolean brighten)
	{
		for (Component component : titleBar.getComponents())
		{
			if (component instanceof JLabel)
			{
				Color color = ((JLabel) component).getForeground();
				((JLabel) component).setForeground(brighten ? color.brighter() : color.darker());
			}
		}
	}
	
	/**
	 * Build session box with aggregated loot
	 * Format: "11/12  - 1KC | besttime | GP"
	 */
	void buildSession(List<AraxxorKillRecord> sessionKills, AraxxorPlugin.SessionSummary summary, int sessionNumber, AraxxorConfigPanel configPanel)
	{
		// Format: MM/dd  - KC | besttime | GP
		String dateStr = formatSessionDate(summary.getSessionStartTime());
		int killCount = sessionKills.size();
		long bestTime = summary.getBestTime();
		AraxxorEggType bestRotation = summary.getBestRotation();
		
		// Replace title label - add elements directly to titleBar for proper layout
		titleBar.removeAll();
		
		Map<Integer, Long> aggregatedLoot = aggregateLoot(sessionKills);
		boolean hasUnique = hasUniqueDrops(aggregatedLoot);
		
		String cacheKey = "session_" + summary.getSessionStartTime();
		long totalGP = configPanel.calculateGPWithCache(aggregatedLoot, cacheKey);
		
		// Add subtle star icon if session contains unique drops
		if (hasUnique)
		{
			titleBar.add(createStarIcon());
			titleBar.add(Box.createRigidArea(new Dimension(3, 0))); // Small spacing
		}
		
		// Date and KC with compact styling
		JLabel dateKcLabel = new JLabel(dateStr + "  - " + killCount + "KC | ");
		dateKcLabel.setFont(FontManager.getRunescapeSmallFont());
		dateKcLabel.setForeground(Color.WHITE);
		titleBar.add(dateKcLabel);
		
		// Best time label with rotation color
		Color rotationColor = bestRotation != null ? bestRotation.getColor() : Color.WHITE;
		String bestTimeStr = formatTime(bestTime);
		JLabel timeLabel = new JLabel(bestTimeStr);
		timeLabel.setFont(FontManager.getRunescapeSmallFont());
		timeLabel.setForeground(rotationColor);
		titleBar.add(timeLabel);
		
		// Add GP amount on the right
		titleBar.add(Box.createHorizontalGlue());
		JLabel gpLabel = new JLabel(QuantityFormatter.quantityToStackSize(totalGP) + " gp");
		gpLabel.setFont(FontManager.getRunescapeSmallFont());
		gpLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		titleBar.add(gpLabel);
		
		setSubtitle("");
		// Don't call setPrice since we're showing GP in the title panel
		
		// Build loot grid with aggregated loot (already aggregated above)
		
		// Build loot grid with aggregated loot
		if (!aggregatedLoot.isEmpty())
		{
			contentContainer.setLayout(new BorderLayout());
			contentContainer.setOpaque(false);
			contentContainer.setBorder(new EmptyBorder(8, 8, 8, 8)); // Increased padding for better spacing
			
			JPanel lootGrid = buildAggregatedLootGrid(aggregatedLoot, configPanel);
			contentContainer.add(lootGrid, BorderLayout.CENTER);
		}
	}
	
	/**
	 * Build nested session box (for sessions grouped by day)
	 * Format: "HH:mm  - KC | besttime | GP" (session time as primary identifier)
	 */
	void buildSessionNested(List<AraxxorKillRecord> sessionKills, AraxxorPlugin.SessionSummary summary, int sessionNumber, AraxxorConfigPanel configPanel)
	{
		// Format: HH:mm  - KC | besttime | GP (session time as primary)
		String sessionTimeStr = formatSessionTime(summary.getSessionStartTime());
		int killCount = sessionKills.size();
		long bestTime = summary.getBestTime();
		AraxxorEggType bestRotation = summary.getBestRotation();
		
		// Replace title label - add elements directly to titleBar for proper layout
		titleBar.removeAll();
		
		Map<Integer, Long> aggregatedLoot = aggregateLoot(sessionKills);
		boolean hasUnique = hasUniqueDrops(aggregatedLoot);
		
		String cacheKey = "session_nested_" + summary.getSessionStartTime();
		long totalGP = configPanel.calculateGPWithCache(aggregatedLoot, cacheKey);
		
		// Add subtle star icon if session contains unique drops
		if (hasUnique)
		{
			titleBar.add(createStarIcon());
			titleBar.add(Box.createRigidArea(new Dimension(3, 0))); // Small spacing
		}
		
		// Session time and KC with compact styling
		JLabel timeKcLabel = new JLabel(sessionTimeStr + "  - " + killCount + "KC | ");
		timeKcLabel.setFont(FontManager.getRunescapeSmallFont());
		timeKcLabel.setForeground(Color.WHITE);
		titleBar.add(timeKcLabel);
		
		// Best time label with rotation color
		Color rotationColor = bestRotation != null ? bestRotation.getColor() : Color.WHITE;
		String bestTimeStr = formatTime(bestTime);
		JLabel timeLabel = new JLabel(bestTimeStr);
		timeLabel.setFont(FontManager.getRunescapeSmallFont());
		timeLabel.setForeground(rotationColor);
		titleBar.add(timeLabel);
		
		// Add GP amount on the right
		titleBar.add(Box.createHorizontalGlue());
		JLabel gpLabel = new JLabel(QuantityFormatter.quantityToStackSize(totalGP) + " gp");
		gpLabel.setFont(FontManager.getRunescapeSmallFont());
		gpLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		titleBar.add(gpLabel);
		
		setSubtitle("");
		
		// Build loot grid with aggregated loot (already aggregated above)
		
		// Build loot grid with aggregated loot
		if (!aggregatedLoot.isEmpty())
		{
			contentContainer.setLayout(new BorderLayout());
			contentContainer.setOpaque(false);
			contentContainer.setBorder(new EmptyBorder(8, 8, 8, 8)); // Increased padding for better spacing
			
			JPanel lootGrid = buildAggregatedLootGrid(aggregatedLoot, configPanel);
			contentContainer.add(lootGrid, BorderLayout.CENTER);
		}
	}
	
	private String formatTime(long timeMs)
	{
		long seconds = timeMs / 1000;
		long minutes = seconds / 60;
		seconds = seconds % 60;
		return String.format("%d:%02d", minutes, seconds);
	}
	
	/**
	 * Format session date as MM/dd (no year) for compact display (e.g., "11/12")
	 */
	private String formatSessionDate(long timestamp)
	{
		LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/dd");
		return dateTime.format(formatter);
	}
	
	/**
	 * Format session time as HH:mm for nested display (e.g., "14:30")
	 */
	private String formatSessionTime(long timestamp)
	{
		LocalDateTime dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
		return dateTime.format(formatter);
	}
	
	/**
	 * Aggregate loot from multiple kills into a single map
	 */
	private Map<Integer, Long> aggregateLoot(List<AraxxorKillRecord> kills)
	{
		Map<Integer, Long> aggregated = new java.util.HashMap<>();
		for (AraxxorKillRecord kill : kills)
		{
			if (kill.getLoot() != null)
			{
				for (Map.Entry<Integer, Long> entry : kill.getLoot().entrySet())
				{
					aggregated.merge(entry.getKey(), entry.getValue(), Long::sum);
				}
			}
		}
		return aggregated;
	}
	
	/**
	 * Build loot grid from aggregated loot (like LootTracker)
	 */
	private JPanel buildAggregatedLootGrid(Map<Integer, Long> aggregatedLoot, AraxxorConfigPanel configPanel)
	{
		JPanel grid = new JPanel();
		grid.setOpaque(false);
		
		// Sort loot by value
		List<Map.Entry<Integer, Long>> sortedLoot = aggregatedLoot.entrySet().stream()
			.sorted(Comparator.<Map.Entry<Integer, Long>>comparingLong(entry -> {
				int itemId = entry.getKey();
				long qty = entry.getValue();
				int price = configPanel.getCachedItemPrice(itemId);
				return price > 0 ? price * qty : 0;
			}).reversed())
			.collect(Collectors.toList());
		
		if (sortedLoot.isEmpty())
		{
			return grid;
		}
		
		// Calculate grid size
		int rowSize = ((sortedLoot.size() % ITEMS_PER_ROW == 0) ? 0 : 1) + sortedLoot.size() / ITEMS_PER_ROW;
		grid.setLayout(new GridLayout(rowSize, ITEMS_PER_ROW, 3, 3)); // Spacing between items
		
		// Create item slots with improved styling
		for (int i = 0; i < rowSize * ITEMS_PER_ROW; i++)
		{
			JPanel slotContainer = new JPanel(new BorderLayout());
			slotContainer.setBackground(new Color(25, 25, 25)); // Darker background for slots
			slotContainer.setBorder(new CompoundBorder(
				new MatteBorder(1, 1, 1, 1, new Color(40, 40, 40)), // Subtle border
				new EmptyBorder(1, 1, 1, 1) // Reduced inner padding for better image fit
			));
			
			if (i < sortedLoot.size())
			{
				Map.Entry<Integer, Long> entry = sortedLoot.get(i);
				int itemId = entry.getKey();
				long quantity = entry.getValue();
				boolean isUnique = isUniqueDrop(itemId);
				
				JLabel imageLabel = new JLabel();
				imageLabel.setVerticalAlignment(SwingConstants.CENTER);
				imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
				
				// Build tooltip
				String itemName = configPanel.getCachedItemName(itemId);
				int price = configPanel.getCachedItemPrice(itemId);
				long itemValue = price > 0 ? price * quantity : 0;
				String tooltip = String.format("<html>%s x %s<br>%s gp</html>",
					itemName, QuantityFormatter.formatNumber(quantity),
					QuantityFormatter.quantityToStackSize(itemValue));
				imageLabel.setToolTipText(tooltip);
				
				// Load item image
				AsyncBufferedImage itemImage = itemManager.getImage(itemId, (int) quantity, quantity > 1);
				itemImage.addTo(imageLabel);
				
				// Apply unique drop styling if this is a unique drop
				if (isUnique)
				{
					// Subtle golden background tint for unique drops
					slotContainer.setBackground(new Color(45, 40, 25)); // Subtle golden tint
					slotContainer.setBorder(new CompoundBorder(
						new MatteBorder(2, 2, 2, 2, new Color(255, 215, 0, 180)), // Subtle golden border (slightly thicker)
						new EmptyBorder(1, 1, 1, 1)
					));
				}
				else
				{
					// Normal styling for regular items
					slotContainer.setBackground(new Color(25, 25, 25));
					slotContainer.setBorder(new CompoundBorder(
						new MatteBorder(1, 1, 1, 1, new Color(40, 40, 40)),
						new EmptyBorder(1, 1, 1, 1)
					));
				}
				
				// Add hover effect to item slots
				final JPanel finalSlotContainer = slotContainer;
				final boolean finalIsUnique = isUnique;
				slotContainer.addMouseListener(new MouseAdapter()
				{
					@Override
					public void mouseEntered(MouseEvent e)
					{
						if (finalIsUnique)
						{
							// Enhanced golden glow on hover for unique drops
							finalSlotContainer.setBackground(new Color(55, 50, 30)); // Slightly brighter golden tint
							finalSlotContainer.setBorder(new CompoundBorder(
								new MatteBorder(2, 2, 2, 2, new Color(255, 215, 0, 255)), // Brighter golden border
								new EmptyBorder(1, 1, 1, 1)
							));
						}
						else
						{
							finalSlotContainer.setBackground(new Color(35, 35, 35)); // Lighter on hover
							finalSlotContainer.setBorder(new CompoundBorder(
								new MatteBorder(1, 1, 1, 1, new Color(60, 60, 60)), // Brighter border on hover
								new EmptyBorder(1, 1, 1, 1)
							));
						}
					}
					
					@Override
					public void mouseExited(MouseEvent e)
					{
						if (finalIsUnique)
						{
							// Restore unique drop styling
							finalSlotContainer.setBackground(new Color(45, 40, 25));
							finalSlotContainer.setBorder(new CompoundBorder(
								new MatteBorder(2, 2, 2, 2, new Color(255, 215, 0, 180)),
								new EmptyBorder(1, 1, 1, 1)
							));
						}
						else
						{
							finalSlotContainer.setBackground(new Color(25, 25, 25));
							finalSlotContainer.setBorder(new CompoundBorder(
								new MatteBorder(1, 1, 1, 1, new Color(40, 40, 40)),
								new EmptyBorder(1, 1, 1, 1)
							));
						}
					}
				});
				
				slotContainer.add(imageLabel, BorderLayout.CENTER);
			}
			else
			{
				// Empty slot - keep it subtle
				slotContainer.setBackground(new Color(20, 20, 20));
			}
			
			grid.add(slotContainer);
		}
		
		return grid;
	}
	
	/**
	 * Build ALL view: Single box with aggregated loot from all kills
	 * Format: "All - X kills | GP"
	 */
	void buildAllView(Map<Integer, Long> aggregatedLoot, int totalKills, long bestTime, AraxxorEggType bestRotation, AraxxorConfigPanel configPanel)
	{
		String cacheKey = "all_" + totalKills; // Use kill count as part of key
		long totalGP = configPanel.calculateGPWithCache(aggregatedLoot, cacheKey);
		
		// Replace title label - add elements directly to titleBar for proper layout
		titleBar.removeAll();
		
		// Title with more impactful styling (matching session version)
		JLabel titleLabel = new JLabel("All - " + totalKills + " kills");
		titleLabel.setFont(FontManager.getRunescapeBoldFont()); // Use bold font for more impact
		titleLabel.setForeground(Color.WHITE);
		titleBar.add(titleLabel);
		
		// Add GP amount on the right
		titleBar.add(Box.createHorizontalGlue());
		titleBar.add(Box.createRigidArea(new Dimension(5, 0)));
		JLabel gpLabel = new JLabel(QuantityFormatter.quantityToStackSize(totalGP) + " gp");
		gpLabel.setFont(FontManager.getRunescapeBoldFont());
		gpLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		titleBar.add(gpLabel);
		
		setSubtitle("");
		
		// Build loot grid with aggregated loot
		if (!aggregatedLoot.isEmpty())
		{
			contentContainer.setLayout(new BorderLayout());
			contentContainer.setOpaque(false);
			contentContainer.setBorder(new EmptyBorder(5, 5, 5, 5));
			
			JPanel lootGrid = buildAggregatedLootGrid(aggregatedLoot, configPanel);
			contentContainer.add(lootGrid, BorderLayout.CENTER);
		}
	}
	
	/**
	 * Build individual kill box
	 * Format: "Kill #X - (time)" (no circle, colored time)
	 */
	void buildKill(AraxxorKillRecord kill, int killNumber, AraxxorConfigPanel configPanel)
	{
		String timeStr = formatTime(kill.getKillTime());
		AraxxorEggType rotation = kill.getRotation();
		Color rotationColor = rotation != null ? rotation.getColor() : Color.WHITE;
		
		// Replace title label - add elements directly to titleBar for proper layout
		titleBar.removeAll();
		
		boolean hasUnique = kill.getLoot() != null && hasUniqueDrops(kill.getLoot());
		
		// Add subtle star icon if kill contains unique drops
		if (hasUnique)
		{
			titleBar.add(createStarIcon());
			titleBar.add(Box.createRigidArea(new Dimension(3, 0))); // Small spacing
		}
		
		// Kill number label with more impactful styling (matching session version)
		JLabel killNumberLabel = new JLabel("Kill #" + killNumber + " - ");
		killNumberLabel.setFont(FontManager.getRunescapeBoldFont()); // Use bold font for more impact
		killNumberLabel.setForeground(Color.WHITE);
		titleBar.add(killNumberLabel);
		
		// Add spacing before time
		titleBar.add(Box.createRigidArea(new Dimension(5, 0)));
		
		// Time label with rotation color (no circle, just colored time in parentheses)
		JLabel timeLabel = new JLabel("(" + timeStr + ")");
		timeLabel.setFont(FontManager.getRunescapeBoldFont()); // Use bold font for more impact
		timeLabel.setForeground(rotationColor);
		titleBar.add(timeLabel);
		
		// Add glue to fill remaining space
		titleBar.add(Box.createHorizontalGlue());
		
		setSubtitle("");
		
		// Build loot grid
		if (kill.getLoot() != null && !kill.getLoot().isEmpty())
		{
			contentContainer.setLayout(new BorderLayout());
			contentContainer.setOpaque(false);
			contentContainer.setBorder(new EmptyBorder(8, 8, 8, 8)); // Increased padding for better spacing
			
			// Build single kill loot grid
			Map<Integer, Long> singleKillLoot = new java.util.HashMap<>();
			for (Map.Entry<Integer, Long> entry : kill.getLoot().entrySet())
			{
				singleKillLoot.put(entry.getKey(), entry.getValue());
			}
			JPanel lootGrid = buildAggregatedLootGrid(singleKillLoot, configPanel);
			contentContainer.add(lootGrid, BorderLayout.CENTER);
		}
	}
}

