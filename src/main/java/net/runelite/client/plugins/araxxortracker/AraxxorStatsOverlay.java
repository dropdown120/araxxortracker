package net.runelite.client.plugins.araxxortracker;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.components.LineComponent;

@Slf4j
public class AraxxorStatsOverlay extends OverlayPanel
{
	private final AraxxorPlugin plugin;
	private final AraxxorConfig config;

	private static final Color COLOR_GRAY_NO_TIME = new Color(100, 100, 100);
	private static final Color COLOR_YELLOW_TITLE = new Color(255, 255, 0);
	
	private static final Dimension PANEL_SIZE = new Dimension(200, 0);
	private static final Color PANEL_BACKGROUND = new Color(0, 0, 0, 190);
	private static final Rectangle PANEL_BORDER = new Rectangle(5, 5, 5, 5);
	private static final java.awt.BasicStroke CIRCLE_STROKE = new java.awt.BasicStroke(2.0f);
	
	private final List<RotationTime> rotationTimesList = new ArrayList<>(3);
	private int timeLineIndex = -1;
	private String timeLineText = null;
	private String targetTimeText = null;
	private Color targetTimeColor = null;
	private int rotationTimesLineIndex = -1; // Track line index for rotation times placeholder

	@Inject
	private AraxxorStatsOverlay(AraxxorPlugin plugin, AraxxorConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(java.awt.Graphics2D graphics)
	{
		if (!config.showStatsOverlay())
		{
			return null;
		}

		if (!plugin.isInAraxxorArea())
		{
			return null;
		}

		if (!plugin.isAraxxorReachedZeroHp())
		{
			return null;
		}

		if (plugin.getFightEndTime() == -1)
		{
			return null;
		}

		getPanelComponent().setPreferredSize(PANEL_SIZE);
		getPanelComponent().setBackgroundColor(PANEL_BACKGROUND);
		getPanelComponent().setBorder(PANEL_BORDER);

		getPanelComponent().getChildren().clear();

		SplitComparisonMode comparisonMode = config.splitComparisonMode();
		
		if (comparisonMode == SplitComparisonMode.LAST_KILL)
		{
			renderLastKillMode();
		}
		else if (comparisonMode == SplitComparisonMode.PERSONAL_BEST)
		{
			renderBestTimeMode();
		}
		else // TARGET
		{
			renderTargetMode();
		}

		Dimension panelSize = super.render(graphics);
		if (panelSize == null)
		{
			return null;
		}
		
		// Draw title and additional overlays after panel is rendered
		FontMetrics fm = graphics.getFontMetrics();
		int padding = 5;
		
		String titleText;
		if (comparisonMode == SplitComparisonMode.LAST_KILL)
		{
			titleText = "Last Kill:";
		}
		else if (comparisonMode == SplitComparisonMode.PERSONAL_BEST)
		{
			titleText = "Best Time:";
		}
		else // TARGET
		{
			titleText = "Target Time:";
		}
		
		// Title centered at top
		int titleWidth = fm.stringWidth(titleText);
		int titleX = (panelSize.width - titleWidth) / 2;
		int titleY = padding + fm.getAscent();
		graphics.setColor(COLOR_YELLOW_TITLE);
		graphics.drawString(titleText, titleX, titleY);
		
		// Render rotation times for BEST_TIME mode (at bottom, using tracked line index)
		if (comparisonMode == SplitComparisonMode.PERSONAL_BEST && rotationTimesLineIndex >= 0)
		{
			renderRotationTimes(graphics, panelSize, fm, padding);
		}
		
		// Render rotation circle for LAST_KILL mode
		if (comparisonMode == SplitComparisonMode.LAST_KILL && timeLineIndex >= 0)
		{
			renderTimeCircle(graphics, panelSize, fm, padding);
		}
		
		// Render centered target time for TARGET mode
		if (comparisonMode == SplitComparisonMode.TARGET && targetTimeText != null)
		{
			int targetWidth = fm.stringWidth(targetTimeText);
			int targetX = (panelSize.width - targetWidth) / 2;
			// Position below the separator (after title spacing)
			int targetY = titleY + fm.getHeight() + padding;
			graphics.setColor(targetTimeColor);
			graphics.drawString(targetTimeText, targetX, targetY);
		}
		
		return panelSize;
	}
	
	private void renderLastKillMode()
	{
		// Add space below title
		addSeparator();
		
		// Get last kill data
		long lastNormal = plugin.getLastFightNormalTime();
		long lastEnrage = plugin.getLastFightEnrageTime();
		long lastTotal = -1;
		if (lastNormal > 0)
		{
			lastTotal = lastNormal + (lastEnrage > 0 ? lastEnrage : 0);
		}
		
		int lastHits = plugin.getLastFightHits();
		int lastDamageDealt = plugin.getLastFightDamageDealt();
		int lastDamageTaken = plugin.getLastFightDamageTaken();
		
		// Check if we have valid last kill data
		if (lastTotal <= 0 && lastHits <= 0)
		{
			// No last kill - show waiting message
			addLineLeft("Get a kill", "", Color.WHITE);
			return;
		}
		
		// Format: Time: 1:52 (1:30 | :22) -> [space] -> Hits: 34 | Avg: X | HP: -69
		
		// Time with normal and enrage times in parentheses
		if (lastTotal > 0)
		{
			StringBuilder timeLine = new StringBuilder();
			timeLine.append("Time: ").append(formatTimeCompact(lastTotal));
			
			// Add normal and enrage times in parentheses
			if (lastNormal > 0 || lastEnrage > 0)
			{
				timeLine.append(" (");
				if (lastNormal > 0)
				{
					long normalTime = lastNormal + 1000; // Add 1s offset like main overlay
					timeLine.append(formatTimeSimple(normalTime));
				}
				if (lastEnrage > 0)
				{
					if (lastNormal > 0)
					{
						timeLine.append(" | ");
					}
					timeLine.append(formatEnrageTime(lastEnrage));
				}
				timeLine.append(")");
			}
			// Track the line index and text for circle rendering
			timeLineText = timeLine.toString();
			timeLineIndex = getPanelComponent().getChildren().size();
			addLineLeft(timeLineText, "", Color.WHITE);
		}
		else
		{
			timeLineIndex = -1;
			timeLineText = null;
		}
		
		// Add blank line (separator)
		addSeparator();
		
		// Hits, Avg Hit, HP on one line
		if (lastHits > 0 || (lastHits > 0 && lastDamageDealt > 0) || lastDamageTaken > 0)
		{
			StringBuilder perfLine = new StringBuilder();
			boolean hasContent = false;
			
			if (lastHits > 0)
			{
				perfLine.append("Hits: ").append(lastHits);
				hasContent = true;
			}
			
			// Replace DPS with Avg Hit
			if (lastHits > 0 && lastDamageDealt > 0)
			{
				double avgHit = (double) lastDamageDealt / lastHits;
				if (hasContent)
				{
					perfLine.append(" | ");
				}
				perfLine.append("Avg: ").append((int) Math.round(avgHit));
				hasContent = true;
			}
			
			if (lastDamageTaken > 0)
			{
				if (hasContent)
				{
					perfLine.append(" | ");
				}
				perfLine.append("HP: -").append(lastDamageTaken);
			}
			
			if (perfLine.length() > 0)
			{
				addLineLeft("", perfLine.toString(), Color.WHITE);
			}
		}
	}
	
	private void addSeparator()
	{
		getPanelComponent().getChildren().add(LineComponent.builder()
			.left("")
			.right("")
			.build());
	}
	
	private void addLine(String left, String right, Color rightColor)
	{
		getPanelComponent().getChildren().add(LineComponent.builder()
			.left(left)
			.right(right)
			.rightColor(rightColor)
			.build());
	}
	
	private void addLineLeft(String left, String right, Color color)
	{
		// Put content in left field to ensure left alignment
		String combined = left;
		if (!left.isEmpty() && !right.isEmpty())
		{
			combined = left + " " + right;
		}
		else if (!right.isEmpty())
		{
			combined = right;
		}
		getPanelComponent().getChildren().add(LineComponent.builder()
			.left(combined)
			.right("")
			.leftColor(color)
			.build());
	}
	
	private void renderBestTimeMode()
	{
		// Get overall best stats
		int bestHits = plugin.getBestHitCount();
		int bestDamageTaken = plugin.getBestDamageTaken();
		long bestKillTime = plugin.getBestKillTime();
		long bestTimeToEnrage = plugin.getBestTimeToEnrage();
		long bestTimeInEnrage = plugin.getBestTimeInEnrage();
		
		// Add separator below title
		addSeparator();
		
		// Total time
		if (bestKillTime > 0)
		{
			addLineLeft("Time:", formatTimeCompact(bestKillTime), Color.WHITE);
		}
		
		// Normal and Enrage combined on one line
		if (bestTimeToEnrage > 0 || bestTimeInEnrage > 0)
		{
			StringBuilder timingLine = new StringBuilder();
			if (bestTimeToEnrage > 0)
			{
				long normalTime = bestTimeToEnrage + 1000; // Add 1s offset
				timingLine.append("Normal: ").append(formatTimeSimple(normalTime));
			}
			if (bestTimeInEnrage > 0)
			{
				if (timingLine.length() > 0)
				{
					timingLine.append(" | ");
				}
				timingLine.append("Enrage: ").append(formatTimeSimple(bestTimeInEnrage));
			}
			addLineLeft("", timingLine.toString(), Color.WHITE);
		}
		
		// Hits and HP Lost combined on one line
		if (bestHits > 0 || bestDamageTaken > 0)
		{
			StringBuilder statsLine = new StringBuilder();
			boolean hasContent = false;
			
			if (bestHits > 0)
			{
				statsLine.append("Hits: ").append(bestHits);
				hasContent = true;
			}
			
			if (bestDamageTaken > 0)
			{
				if (hasContent)
				{
					statsLine.append(" | ");
				}
				statsLine.append("Lost: ").append(bestDamageTaken).append(" HP");
			}
			
			if (statsLine.length() > 0)
			{
				addLineLeft("", statsLine.toString(), Color.WHITE);
			}
		}
		
		// Add separator before rotation times
		addSeparator();
		
		// Add placeholder line for rotation times (we'll draw over this)
		rotationTimesLineIndex = getPanelComponent().getChildren().size();
		addSeparator(); // Empty line that creates space for manual drawing
	}
	
	private void renderTargetMode()
	{
		// Add space below title
		addSeparator();
		
		// Get target time
		int targetSeconds = config.targetTotalTime();
		long targetTime = targetSeconds > 0 ? targetSeconds * 1000L : -1;
		
		// Store target time text and color for centered rendering after panel render
		if (targetTime > 0)
		{
			targetTimeText = formatTimeCompact(targetTime);
			targetTimeColor = Color.WHITE;
		}
		else
		{
			targetTimeText = "Not set";
			targetTimeColor = COLOR_GRAY_NO_TIME;
		}
		
		// Add empty lines to make box height consistent with other modes
		addSeparator();
		addSeparator();

	}
	
	private void renderRotationTimes(Graphics2D graphics, Dimension panelSize, FontMetrics fm, int padding)
	{
		// Get best times for each rotation
		long whiteTime = plugin.getBestWhiteStartTime();
		long redTime = plugin.getBestRedStartTime();
		long greenTime = plugin.getBestGreenStartTime();
		
		rotationTimesList.clear();
		rotationTimesList.add(new RotationTime(AraxxorEggType.WHITE, whiteTime));
		rotationTimesList.add(new RotationTime(AraxxorEggType.RED, redTime));
		rotationTimesList.add(new RotationTime(AraxxorEggType.GREEN, greenTime));
		
		rotationTimesList.sort((a, b) -> {
			if (a.getTime() <= 0 && b.getTime() <= 0) return 0;
			if (a.getTime() <= 0) return 1;
			if (b.getTime() <= 0) return -1;
			return Long.compare(a.getTime(), b.getTime());
		});
		
		// Render rotation times as colored circles with times
		int circleSize = 12;
		int circleSpacing = 8;
		int itemSpacing = 15;
		
		String[] timeStrings = new String[3];
		Color[] dotColors = new Color[3];
		
		for (int i = 0; i < rotationTimesList.size(); i++) {
			RotationTime rt = rotationTimesList.get(i);
			timeStrings[i] = rt.getTime() > 0 ? formatTimeCompact(rt.getTime()) : "-";
			
			if (rt.getTime() <= 0) {
				dotColors[i] = COLOR_GRAY_NO_TIME;
			} else {
				dotColors[i] = rt.getEggType().getColor();
			}
		}
		
		// Calculate total width for centering
		int totalWidth = 0;
		for (int i = 0; i < timeStrings.length; i++) {
			if (i > 0) {
				totalWidth += itemSpacing;
			}
			totalWidth += circleSize + circleSpacing;
			totalWidth += fm.stringWidth(timeStrings[i]);
		}
		
		// Position rotation times at the bottom with padding
		int startX = padding + (panelSize.width - padding * 2 - totalWidth) / 2;
		int rotationTimesY = panelSize.height - padding - fm.getDescent();
		int textCenterY = rotationTimesY - fm.getAscent() / 2;
		int circleY = textCenterY - circleSize / 2;
		
		int currentX = startX;
		Graphics2D g2d = (Graphics2D) graphics.create();
		g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		
		for (int i = 0; i < timeStrings.length; i++) {
			if (i > 0) {
				currentX += itemSpacing;
			}
			
			if (timeStrings[i].equals("-")) {
				g2d.setColor(COLOR_GRAY_NO_TIME);
			} else {
				g2d.setColor(dotColors[i]);
			}
			g2d.setStroke(CIRCLE_STROKE);
			g2d.drawOval(currentX, circleY, circleSize, circleSize);
			currentX += circleSize + circleSpacing;
			
			if (timeStrings[i].equals("-")) {
				graphics.setColor(COLOR_GRAY_NO_TIME);
			} else {
				graphics.setColor(dotColors[i]);
			}
			graphics.drawString(timeStrings[i], currentX, rotationTimesY);
			currentX += fm.stringWidth(timeStrings[i]);
		}
		
		g2d.dispose();
	}
	
	private void renderTimeCircle(Graphics2D graphics, Dimension panelSize, FontMetrics fm, int padding)
	{
		if (timeLineIndex < 0 || timeLineIndex >= getPanelComponent().getChildren().size() || timeLineText == null)
		{
			return;
		}
		
		net.runelite.client.ui.overlay.components.LayoutableRenderableEntity child = getPanelComponent().getChildren().get(timeLineIndex);
		if (!(child instanceof LineComponent))
		{
			return;
		}
		
		LineComponent lineComponent = (LineComponent) child;
		Rectangle lineBounds = lineComponent.getBounds();
		
		if (lineBounds.isEmpty())
		{
			return;
		}
		
		// Get the rotation for the last kill
		AraxxorEggType rotation = plugin.getCurrentRotationStart();
		if (rotation == null)
		{
			return;
		}
		
		// Calculate position: right after the entire time text
		int fullTextWidth = fm.stringWidth(timeLineText);
		int circleSize = 12;
		int circleSpacing = 4; // Small spacing between text and circle
		
		// Position circle to the right of the entire Time value
		int circleX = lineBounds.x + fullTextWidth + circleSpacing;
		int textCenterY = lineBounds.y + fm.getAscent() / 2;
		int circleY = textCenterY - circleSize / 2;
		
		// Draw the circle with the same style as rotation times
		Graphics2D g2d = (Graphics2D) graphics.create();
		g2d.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		g2d.setColor(rotation.getColor());
		g2d.setStroke(CIRCLE_STROKE);
		g2d.drawOval(circleX, circleY, circleSize, circleSize);
		g2d.dispose();
	}

	private String formatTimeCompact(long timeMs)
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
	
	private String formatTimeSimple(long timeMs)
	{
		if (timeMs < 0)
		{
			return "-";
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
	
	private String formatEnrageTime(long timeMs)
	{
		if (timeMs < 0)
		{
			return "-";
		}
		long totalSeconds = timeMs / 1000;
		long minutes = totalSeconds / 60;
		long seconds = totalSeconds % 60;
		
		// Format as :30 when under a minute, or 1:30 when over a minute
		if (minutes > 0)
		{
			return minutes + ":" + String.format("%02d", seconds);
		}
		else
		{
			return ":" + String.format("%02d", seconds);
		}
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
}
