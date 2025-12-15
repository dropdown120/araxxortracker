package com.araxxortracker;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

class AraxxorWorldOverlay extends Overlay
{
	private static final int DISPLAY_DURATION_MS = 7000;
	private static final int FADE_IN_DURATION_MS = 500;
	private static final int FADE_OUT_START_MS = 5500;
	private static final float MIN_OPACITY = 0.1f;
	
	private static final int CARD_SPACING = 8;
	private static final int CARD_PADDING = 10;
	private static final int LINE_SPACING = 4;
	
	private static final Color COLOR_BACKGROUND = new Color(20, 20, 20, 220);
	private static final Color COLOR_BORDER = new Color(80, 80, 80, 200);
	private static final Color COLOR_LABEL = new Color(180, 180, 180, 255);
	private static final Color COLOR_VALUE = new Color(240, 240, 240, 255);
	
	private static final Color COLOR_HITS_GOOD = new Color(100, 220, 100, 255);
	private static final Color COLOR_HITS_BEST = new Color(255, 200, 50, 255);
	private static final Color COLOR_HITS_NORMAL = new Color(220, 220, 150, 255);
	private static final Color COLOR_DAMAGE_GOOD = new Color(100, 220, 100, 255);
	private static final Color COLOR_DAMAGE_BEST = new Color(255, 200, 50, 255);
	private static final Color COLOR_DAMAGE_BAD = new Color(240, 100, 100, 255);
	private static final Color COLOR_DAMAGE_DEALT = new Color(150, 200, 255, 255);
	private static final Color COLOR_TIME_BEST = new Color(255, 200, 50, 255);
	private static final Color COLOR_TIME_GOOD = new Color(100, 220, 100, 255);
	private static final Color COLOR_TIME_NORMAL = new Color(240, 240, 240, 255);
	
	private static final Color COLOR_SPLIT_GOOD = new Color(100, 220, 100, 255);
	private static final Color COLOR_SPLIT_BAD = new Color(255, 100, 100, 255);
	private static final Color COLOR_SPLIT_BEST = new Color(255, 215, 0, 255);
	
	private static class StatCard
	{
		final String title;
		final List<MetricLine> lines;
		final int width;
		final int height;
		
		StatCard(String title, List<MetricLine> lines, int width, int height)
		{
			this.title = title;
			this.lines = lines;
			this.width = width;
			this.height = height;
		}
	}
	
	private static class MetricLine
	{
		final String label;
		final String value;
		final Color valueColor;
		final String comparison;
		final Color comparisonColor;
		
		MetricLine(String label, String value, Color valueColor)
		{
			this(label, value, valueColor, "", null);
		}
		
		MetricLine(String label, String value, Color valueColor, String comparison, Color comparisonColor)
		{
			this.label = label;
			this.value = value;
			this.valueColor = valueColor;
			this.comparison = comparison;
			this.comparisonColor = comparisonColor;
		}
	}
	
	private static class FightStats
	{
		final int hits;
		final int damageTaken;
		final int damageDealt;
		final long killTime;
		final double dps;
		final double avgHit;
		final AraxxorEggType rotation;
		final long timeToEnrage;
		final long timeInEnrage;
		final int bestHits;
		final int bestDamage;
		final long bestKillTime;
		
		FightStats(int hits, int damageTaken, int damageDealt,
			long killTime, double dps, double avgHit,
			AraxxorEggType rotation, long timeToEnrage, long timeInEnrage,
			int bestHits, int bestDamage, long bestKillTime)
		{
			this.hits = hits;
			this.damageTaken = damageTaken;
			this.damageDealt = damageDealt;
			this.killTime = killTime;
			this.dps = dps;
			this.avgHit = avgHit;
			this.rotation = rotation;
			this.timeToEnrage = timeToEnrage;
			this.timeInEnrage = timeInEnrage;
			this.bestHits = bestHits;
			this.bestDamage = bestDamage;
			this.bestKillTime = bestKillTime;
		}
	}
	
	private final Client client;
	private final AraxxorPlugin plugin;
	private final AraxxorConfig config;
	
	private final Font statFont;
	
	private float cachedOpacity = -1.0f;
	private long cachedOpacityTime = -1L;
	
	@Inject
	private AraxxorWorldOverlay(Client client, AraxxorPlugin plugin, AraxxorConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.statFont = FontManager.getRunescapeFont().deriveFont(Font.PLAIN, 13.0f);
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}
	
	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showKillTiles() || !shouldShowStats())
		{
			return null;
		}
		
		WorldPoint deathLocation = getBossDeathLocation();
		if (deathLocation == null)
		{
			return null;
		}
		
		float opacity = calculateOpacity();
		if (opacity <= 0.0f)
		{
			return null;
		}
		
		graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		
		Font originalFont = graphics.getFont();
		graphics.setFont(statFont);
		
		SplitComparisonMode comparisonMode = config.splitComparisonMode();
		AraxxorEggType currentRotation = plugin.getCurrentRotationStart();
		long overallBestKillTime = plugin.getBestKillTime();
		int rotationBestHits = getRotationBestHits(currentRotation);
		int rotationBestDamage = getRotationBestDamage(currentRotation);
		long rotationBestTime = currentRotation != null ? plugin.getRotationBestTime(currentRotation) : -1;
		int overallBestHits = rotationBestHits >= 0 ? rotationBestHits : plugin.getBestHitCount();
		int overallBestDamage = rotationBestDamage >= 0 ? rotationBestDamage : plugin.getBestDamageTaken();
		long overallBestTimeToEnrage = plugin.getBestTimeToEnrage();
		long overallBestTimeInEnrage = plugin.getBestTimeInEnrage();
		int lastFightHits = plugin.getLastFightHits();
		int lastFightDamageTaken = plugin.getLastFightDamageTaken();
		long lastFightNormalTime = plugin.getLastFightNormalTime();
		long lastFightEnrageTime = plugin.getLastFightEnrageTime();
		
		FightStats stats = gatherFightStats();
		
		List<StatCard> cards = buildCards(graphics, stats, comparisonMode, currentRotation, 
			overallBestKillTime, rotationBestHits, rotationBestDamage, rotationBestTime, overallBestHits, overallBestDamage,
			overallBestTimeToEnrage, overallBestTimeInEnrage, lastFightHits, lastFightDamageTaken,
			lastFightNormalTime, lastFightEnrageTime);
		
		renderCards(graphics, cards, deathLocation, opacity);
		
		graphics.setFont(originalFont);
		
		return null;
	}
	
	private List<StatCard> buildCards(Graphics2D graphics, FightStats stats, SplitComparisonMode comparisonMode,
		AraxxorEggType currentRotation, long overallBestKillTime, int rotationBestHits, int rotationBestDamage, long rotationBestTime,
		int overallBestHits, int overallBestDamage, long overallBestTimeToEnrage, long overallBestTimeInEnrage,
		int lastFightHits, int lastFightDamageTaken, long lastFightNormalTime, long lastFightEnrageTime)
	{
		FontMetrics fm = graphics.getFontMetrics();
		boolean showSplits = config.showLiveSplits();
		
		List<StatCard> cards = new ArrayList<>();
		
		List<MetricLine> combatLines = new ArrayList<>();
		
		String hitsText = formatHitsText(stats.hits, stats.bestHits);
		Color hitsColor = getHitsColor(stats.hits, stats.bestHits);
		ComparisonResult hitsComp = showSplits ? calculateHitsComparison(stats.hits, comparisonMode, currentRotation,
			rotationBestHits, overallBestHits, lastFightHits) : ComparisonResult.none();
		combatLines.add(new MetricLine("Hits:", hitsText, hitsColor, hitsComp.text, hitsComp.color));
		
		String damageText = formatDamageText(stats.damageTaken, stats.bestDamage);
		Color damageColor = getDamageColor(stats.damageTaken, stats.bestDamage);
		ComparisonResult damageComp = showSplits ? calculateDamageComparison(stats.damageTaken, comparisonMode, currentRotation,
			rotationBestDamage, overallBestDamage, lastFightDamageTaken) : ComparisonResult.none();
		combatLines.add(new MetricLine("Dmg Taken:", damageText, damageColor, damageComp.text, damageComp.color));
		
		combatLines.add(new MetricLine("Dmg Dealt:", String.valueOf(stats.damageDealt), COLOR_DAMAGE_DEALT));
		
		StatCard combatCard = calculateCardDimensions(fm, "Combat", combatLines);
		cards.add(combatCard);
		
		List<MetricLine> perfLines = new ArrayList<>();
		String dpsText = stats.dps > 0 ? String.format("%.1f", stats.dps) : "-";
		perfLines.add(new MetricLine("DPS:", dpsText, COLOR_VALUE));
		
		String avgHitText = stats.avgHit > 0 ? String.format("%.1f", stats.avgHit) : "-";
		perfLines.add(new MetricLine("Avg Hit:", avgHitText, COLOR_VALUE));
		
		StatCard perfCard = calculateCardDimensions(fm, "Performance", perfLines);
		cards.add(perfCard);
		
		List<MetricLine> timeLines = new ArrayList<>();
		
		String timeText = formatTimeCompact(stats.killTime);
		Color timeColor = getTimeColor(stats.killTime, stats.bestKillTime);
		ComparisonResult timeComp = showSplits ? calculateTimeComparison(stats.killTime, comparisonMode, overallBestKillTime,
			currentRotation, rotationBestTime, lastFightNormalTime, lastFightEnrageTime) : ComparisonResult.none();
		timeLines.add(new MetricLine("Time:", timeText, timeColor, timeComp.text, timeComp.color));
		
		String bestText = stats.bestKillTime > 0 ? formatTimeCompact(stats.bestKillTime) : "-";
		timeLines.add(new MetricLine("Best:", bestText, COLOR_TIME_BEST));
		
		boolean hasEnrageData = stats.timeToEnrage > 0 || stats.timeInEnrage > 0;
		if (hasEnrageData)
		{
			if (stats.timeToEnrage > 0)
			{
				String toEnrageText = formatTimeCompact(stats.timeToEnrage + 1000);
				ComparisonResult toEnrageComp = showSplits ? calculatePhaseComparison(stats.timeToEnrage + 1000, true, comparisonMode,
					overallBestTimeToEnrage, lastFightNormalTime) : ComparisonResult.none();
				timeLines.add(new MetricLine("To Enrage:", toEnrageText, COLOR_VALUE, toEnrageComp.text, toEnrageComp.color));
			}
			if (stats.timeInEnrage > 0)
			{
				String inEnrageText = formatTimeCompact(stats.timeInEnrage);
				ComparisonResult inEnrageComp = showSplits ? calculatePhaseComparison(stats.timeInEnrage, false, comparisonMode,
					overallBestTimeInEnrage, lastFightEnrageTime) : ComparisonResult.none();
				timeLines.add(new MetricLine("In Enrage:", inEnrageText, COLOR_VALUE, inEnrageComp.text, inEnrageComp.color));
			}
		}
		else if (stats.rotation != null)
		{
			String rotationText = stats.rotation.getIcon() + " " + stats.rotation.getName();
			timeLines.add(new MetricLine("Rot:", rotationText, stats.rotation.getColor()));
		}
		
		StatCard timeCard = calculateCardDimensions(fm, "Time", timeLines);
		cards.add(timeCard);
		
		return cards;
	}
	
	private StatCard calculateCardDimensions(FontMetrics fm, String title, List<MetricLine> lines)
	{
		int titleWidth = fm.stringWidth(title);
		int maxLabelWidth = 0;
		int maxValueWidth = 0;
		
		for (MetricLine line : lines)
		{
			int labelWidth = fm.stringWidth(line.label);
			int valueWidth = fm.stringWidth(line.value);
			int compWidth = line.comparison.isEmpty() ? 0 : fm.stringWidth(line.comparison) + 4;
			
			maxLabelWidth = Math.max(maxLabelWidth, labelWidth);
			maxValueWidth = Math.max(maxValueWidth, valueWidth + compWidth);
		}
		
		int cardWidth = Math.max(titleWidth, maxLabelWidth + maxValueWidth) + CARD_PADDING * 2;
		int lineHeight = fm.getHeight() + LINE_SPACING;
		int titleHeight = lineHeight + 4;
		int contentHeight = lines.size() * lineHeight;
		int cardHeight = titleHeight + contentHeight + CARD_PADDING * 2;
		
		return new StatCard(title, lines, cardWidth, cardHeight);
	}
	
	private void renderCards(Graphics2D graphics, List<StatCard> cards, WorldPoint deathLocation, float opacity)
	{
		LocalPoint localPoint = LocalPoint.fromWorld(client, deathLocation);
		if (localPoint == null)
		{
			return;
		}
		
		Point basePoint = Perspective.getCanvasTextLocation(client, graphics, localPoint, "", 100);
		if (basePoint == null)
		{
			return;
		}
		
		int totalWidth = 0;
		int maxHeight = 0;
		for (StatCard card : cards)
		{
			totalWidth += card.width;
			if (cards.indexOf(card) < cards.size() - 1)
			{
				totalWidth += CARD_SPACING;
			}
			maxHeight = Math.max(maxHeight, card.height);
		}
		
		int startX = basePoint.getX() - totalWidth / 2;
		int startY = basePoint.getY() - maxHeight - 40;
		
		int currentX = startX;
		for (StatCard card : cards)
		{
			renderCard(graphics, card, currentX, startY, opacity);
			currentX += card.width + CARD_SPACING;
		}
	}
	
	private void renderCard(Graphics2D graphics, StatCard card, int x, int y, float opacity)
	{
		FontMetrics fm = graphics.getFontMetrics();
		
		Color bgColor = applyOpacity(COLOR_BACKGROUND, opacity);
		graphics.setColor(bgColor);
		graphics.fillRect(x, y, card.width, card.height);
		
		Color borderColor = applyOpacity(COLOR_BORDER, opacity);
		graphics.setColor(borderColor);
		graphics.drawRect(x, y, card.width - 1, card.height - 1);
		
		int titleY = y + CARD_PADDING + fm.getAscent();
		Color titleColor = applyOpacity(COLOR_LABEL, opacity);
		graphics.setColor(titleColor);
		graphics.drawString(card.title, x + CARD_PADDING, titleY);
		
		int underlineY = titleY + 4;
		graphics.drawLine(x + CARD_PADDING, underlineY, x + card.width - CARD_PADDING, underlineY);
		
		int lineY = titleY + fm.getHeight() + LINE_SPACING + 4;
		for (MetricLine line : card.lines)
		{
			Color labelColor = applyOpacity(COLOR_LABEL, opacity);
			graphics.setColor(labelColor);
			graphics.drawString(line.label, x + CARD_PADDING, lineY);
			
			int valueX = x + CARD_PADDING + fm.stringWidth(line.label) + 4;
			Color valueColor = applyOpacity(line.valueColor, opacity);
			graphics.setColor(valueColor);
			graphics.drawString(line.value, valueX, lineY);
			
			if (!line.comparison.isEmpty() && line.comparisonColor != null)
			{
				int compX = valueX + fm.stringWidth(line.value) + 4;
				Color compColor = applyOpacity(line.comparisonColor, opacity);
				graphics.setColor(compColor);
				graphics.drawString(line.comparison, compX, lineY);
			}
			
			lineY += fm.getHeight() + LINE_SPACING;
		}
	}
	
	private float calculateOpacity()
	{
		long currentTime = System.currentTimeMillis();
		long fightEndTime = plugin.getFightEndTime();
		
		if (fightEndTime == -1)
		{
			return 0.0f;
		}
		
		long timeSinceDeath = currentTime - fightEndTime;
		
		if (cachedOpacityTime != -1 && (currentTime - cachedOpacityTime) < 50 && timeSinceDeath >= 0)
		{
			return cachedOpacity;
		}
		
		cachedOpacityTime = currentTime;
		
		if (timeSinceDeath < 0 || timeSinceDeath >= DISPLAY_DURATION_MS)
		{
			cachedOpacity = 0.0f;
			return cachedOpacity;
		}
		
		if (timeSinceDeath < FADE_IN_DURATION_MS)
		{
			cachedOpacity = (float) timeSinceDeath / FADE_IN_DURATION_MS;
			return cachedOpacity;
		}
		
		if (timeSinceDeath < FADE_OUT_START_MS)
		{
			cachedOpacity = 1.0f;
			return cachedOpacity;
		}
		
		long fadeOutElapsed = timeSinceDeath - FADE_OUT_START_MS;
		long fadeOutDuration = DISPLAY_DURATION_MS - FADE_OUT_START_MS;
		float fadeOutProgress = (float) fadeOutElapsed / fadeOutDuration;
		cachedOpacity = 1.0f - (fadeOutProgress * (1.0f - MIN_OPACITY));
		return cachedOpacity;
	}
	
	private Color applyOpacity(Color color, float opacity)
	{
		if (opacity >= 1.0f)
		{
			return color;
		}
		int alpha = (int) (color.getAlpha() * opacity);
		return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
	}
	
	private String formatTimeCompact(long milliseconds)
	{
		if (milliseconds < 0)
		{
			return "0s";
		}
		
		long totalSeconds = milliseconds / 1000;
		if (totalSeconds < 60)
		{
			return totalSeconds + "s";
		}
		else
		{
			long minutes = totalSeconds / 60;
			long seconds = totalSeconds % 60;
			return String.format("%d:%02d", minutes, seconds);
		}
	}
	
	private String formatHitsText(int currentHits, int bestHits)
	{
		if (bestHits > 0)
		{
			return String.format("%d/%d", currentHits, bestHits);
		}
		return String.valueOf(currentHits);
	}
	
	private String formatDamageText(int currentDamage, int bestDamage)
	{
		if (bestDamage > 0)
		{
			return String.format("%d/%d", currentDamage, bestDamage);
		}
		return String.valueOf(currentDamage);
	}
	
	private Color getHitsColor(int currentHits, int bestHits)
	{
		if (bestHits <= 0)
		{
			return COLOR_HITS_NORMAL;
		}
		
		if (currentHits > bestHits)
		{
			return COLOR_HITS_GOOD;
		}
		else if (currentHits == bestHits)
		{
			return COLOR_HITS_BEST;
		}
		else
		{
			return COLOR_HITS_NORMAL;
		}
	}
	
	private Color getDamageColor(int currentDamage, int bestDamage)
	{
		if (bestDamage <= 0)
		{
			return COLOR_DAMAGE_BAD;
		}
		
		if (currentDamage < bestDamage)
		{
			return COLOR_DAMAGE_GOOD;
		}
		else if (currentDamage == bestDamage)
		{
			return COLOR_DAMAGE_BEST;
		}
		else
		{
			return COLOR_DAMAGE_BAD;
		}
	}
	
	private Color getTimeColor(long currentTime, long bestTime)
	{
		if (bestTime <= 0)
		{
			return COLOR_TIME_NORMAL;
		}
		
		if (currentTime < bestTime)
		{
			return COLOR_TIME_BEST;
		}
		else if (currentTime == bestTime)
		{
			return COLOR_TIME_BEST;
		}
		else
		{
			double percentSlower = ((double) (currentTime - bestTime) / bestTime) * 100;
			if (percentSlower <= 10)
			{
				return COLOR_TIME_GOOD;
			}
			return COLOR_TIME_NORMAL;
		}
	}
	
	private static class ComparisonResult
	{
		final String text;
		final Color color;
		
		ComparisonResult(String text, Color color)
		{
			this.text = text;
			this.color = color;
		}
		
		static ComparisonResult none()
		{
			return new ComparisonResult("", null);
		}
	}
	
	private ComparisonResult calculateHitsComparison(int currentHits, SplitComparisonMode comparisonMode,
		AraxxorEggType currentRotation, int rotationBestHits, int overallBestHits, int lastFightHits)
	{
		int compareHits = getComparisonHits(comparisonMode, currentRotation, rotationBestHits, overallBestHits, lastFightHits);
		int bestHits = overallBestHits;
		
		if (compareHits <= 0 || currentHits <= 0)
		{
			return ComparisonResult.none();
		}
		
		if (bestHits > 0 && currentHits == bestHits)
		{
			return new ComparisonResult(" ★", COLOR_SPLIT_BEST);
		}
		
		double percentage = ((double) (currentHits - compareHits) / compareHits) * 100;
		if (Math.abs(percentage) < 0.5)
		{
			return ComparisonResult.none();
		}
		
		StringBuilder sb = new StringBuilder(12);
		sb.append(" (");
		if (percentage > 0) sb.append("+");
		sb.append((int)Math.round(percentage)).append("%)");
		Color color = percentage > 0 ? COLOR_SPLIT_GOOD : COLOR_SPLIT_BAD;
		return new ComparisonResult(sb.toString(), color);
	}
	
	private ComparisonResult calculateDamageComparison(int currentDamage, SplitComparisonMode comparisonMode,
		AraxxorEggType currentRotation, int rotationBestDamage, int overallBestDamage, int lastFightDamageTaken)
	{
		int compareDamage = getComparisonDamage(comparisonMode, currentRotation, rotationBestDamage, overallBestDamage, lastFightDamageTaken);
		int bestDamage = overallBestDamage;
		
		if (compareDamage <= 0 || currentDamage < 0)
		{
			return ComparisonResult.none();
		}
		
		if (bestDamage > 0 && currentDamage == bestDamage)
		{
			return new ComparisonResult(" ★", COLOR_SPLIT_BEST);
		}
		
		double percentage = ((double) (currentDamage - compareDamage) / compareDamage) * 100;
		if (Math.abs(percentage) < 0.5)
		{
			return ComparisonResult.none();
		}
		
		StringBuilder sb = new StringBuilder(12);
		sb.append(" (");
		if (percentage > 0) sb.append("+");
		sb.append((int)Math.round(percentage)).append("%)");
		Color color = percentage < 0 ? COLOR_SPLIT_GOOD : COLOR_SPLIT_BAD;
		return new ComparisonResult(sb.toString(), color);
	}
	
	private ComparisonResult calculateTimeComparison(long totalTime, SplitComparisonMode comparisonMode,
		long overallBestKillTime, AraxxorEggType currentRotation, long rotationBestTime,
		long lastFightNormalTime, long lastFightEnrageTime)
	{
		long compareTime = getComparisonTotalTime(comparisonMode, overallBestKillTime, currentRotation, rotationBestTime,
			lastFightNormalTime, lastFightEnrageTime);
		
		if (compareTime <= 0)
		{
			return ComparisonResult.none();
		}
		
		long diff = totalTime - compareTime;
		
		if (diff < -3000)
		{
			return ComparisonResult.none();
		}
		
		if (totalTime == overallBestKillTime && overallBestKillTime > 0)
		{
			return new ComparisonResult(" ★", COLOR_SPLIT_BEST);
		}
		
		long diffSeconds = diff / 1000;
		
		if (diff > 0)
		{
			StringBuilder sb = new StringBuilder(8);
			sb.append(" (+").append(diffSeconds).append("s)");
			return new ComparisonResult(sb.toString(), COLOR_SPLIT_BAD);
		}
		else if (diff < 0)
		{
			StringBuilder sb = new StringBuilder(8);
			sb.append(" (-").append(Math.abs(diffSeconds)).append("s)");
			return new ComparisonResult(sb.toString(), COLOR_SPLIT_GOOD);
		}
		else
		{
			return new ComparisonResult(" (0s)", COLOR_TIME_NORMAL);
		}
	}
	
	private ComparisonResult calculatePhaseComparison(long currentTime, boolean isNormalPhase, SplitComparisonMode comparisonMode,
		long overallBestTime, long lastFightTime)
	{
		long compareTime = getComparisonPhaseTime(isNormalPhase, comparisonMode, overallBestTime, lastFightTime);
		
		if (compareTime <= 0)
		{
			return ComparisonResult.none();
		}
		
		long adjustedCurrentTime = isNormalPhase ? currentTime - 1000 : currentTime;
		long diff = adjustedCurrentTime - compareTime;
		
		if (diff < -3000)
		{
			return ComparisonResult.none();
		}
		
		if (overallBestTime > 0 && adjustedCurrentTime == overallBestTime)
		{
			return new ComparisonResult(" ★", COLOR_SPLIT_BEST);
		}
		
		long diffSeconds = diff / 1000;
		
		if (diff > 0)
		{
			StringBuilder sb = new StringBuilder(8);
			sb.append(" (+").append(diffSeconds).append("s)");
			return new ComparisonResult(sb.toString(), COLOR_SPLIT_BAD);
		}
		else if (diff < 0)
		{
			StringBuilder sb = new StringBuilder(8);
			sb.append(" (-").append(Math.abs(diffSeconds)).append("s)");
			return new ComparisonResult(sb.toString(), COLOR_SPLIT_GOOD);
		}
		else
		{
			return new ComparisonResult(" (0s)", COLOR_TIME_NORMAL);
		}
	}
	
	private int getComparisonHits(SplitComparisonMode mode, AraxxorEggType rotation, int rotationBestHits, int overallBestHits, int lastFightHits)
	{
		if (mode == SplitComparisonMode.LAST_KILL)
		{
			return lastFightHits > 0 ? lastFightHits : (rotationBestHits >= 0 ? rotationBestHits : overallBestHits);
		}
		
		return rotationBestHits >= 0 ? rotationBestHits : overallBestHits;
	}
	
	private int getComparisonDamage(SplitComparisonMode mode, AraxxorEggType rotation, int rotationBestDamage, int overallBestDamage, int lastFightDamageTaken)
	{
		if (mode == SplitComparisonMode.LAST_KILL)
		{
			return lastFightDamageTaken > 0 ? lastFightDamageTaken : (rotationBestDamage >= 0 ? rotationBestDamage : overallBestDamage);
		}
		
		return rotationBestDamage >= 0 ? rotationBestDamage : overallBestDamage;
	}
	
	private long getComparisonTotalTime(SplitComparisonMode mode, long overallBestKillTime, AraxxorEggType currentRotation,
		long rotationBestTime, long lastFightNormalTime, long lastFightEnrageTime)
	{
		if (mode == SplitComparisonMode.TARGET)
		{
			int targetSeconds = config.targetTotalTime();
			if (targetSeconds > 0)
			{
				return targetSeconds * 1000L;
			}
			return overallBestKillTime;
		}
		else if (mode == SplitComparisonMode.LAST_KILL)
		{
			if (lastFightNormalTime > 0)
			{
				return lastFightNormalTime + (lastFightEnrageTime > 0 ? lastFightEnrageTime : 0);
			}
			return overallBestKillTime;
		}
		else
		{
			return overallBestKillTime;
		}
	}
	
	private long getComparisonPhaseTime(boolean isNormalPhase, SplitComparisonMode mode, long overallBestTime, long lastFightTime)
	{
		if (mode == SplitComparisonMode.TARGET)
		{
			// Use total time target for all comparisons (simplified to just total time)
			int targetSeconds = config.targetTotalTime();
			if (targetSeconds > 0)
			{
				return targetSeconds * 1000L;
			}
			return overallBestTime;
		}
		else if (mode == SplitComparisonMode.LAST_KILL)
		{
			if (lastFightTime > 0)
			{
				return lastFightTime;
			}
			return overallBestTime;
		}
		else
		{
			return overallBestTime;
		}
	}
	
	private FightStats gatherFightStats()
	{
		int lastHits = plugin.getLastFightHits();
		int lastDamageTaken = plugin.getLastFightDamageTaken();
		int lastDamageDealt = plugin.getLastFightDamageDealt();
		long killTime = plugin.getElapsedTime();
		double dps = plugin.getDPS();
		double avgHit = plugin.getAverageHit();
		
		AraxxorEggType rotation = plugin.getCurrentRotationStart();
		long timeToEnrage = plugin.getCurrentTimeToEnrage();
		long timeInEnrage = plugin.getCurrentTimeInEnrage();
		
		int rotationBestHits = getRotationBestHits(rotation);
		int rotationBestDamage = getRotationBestDamage(rotation);
		int bestHits = rotationBestHits >= 0 ? rotationBestHits : plugin.getBestHitCount();
		int bestDamage = rotationBestDamage >= 0 ? rotationBestDamage : plugin.getBestDamageTaken();
		long bestKillTime = plugin.getBestKillTime();
		
		return new FightStats(
			lastHits, lastDamageTaken, lastDamageDealt,
			killTime, dps, avgHit,
			rotation, timeToEnrage, timeInEnrage,
			bestHits, bestDamage, bestKillTime
		);
	}
	
	private int getRotationBestHits(AraxxorEggType rotation)
	{
		if (rotation == null)
		{
			return -1;
		}
		return plugin.getRotationBestHits(rotation);
	}
	
	private int getRotationBestDamage(AraxxorEggType rotation)
	{
		if (rotation == null)
		{
			return -1;
		}
		return plugin.getRotationBestDamage(rotation);
	}
	
	private boolean shouldShowStats()
	{
		if (plugin.getFightEndTime() == -1 || plugin.getFightStartTime() == -1)
		{
			return false;
		}
		
		if (plugin.isFightActive() && plugin.getFightStartTime() > plugin.getFightEndTime())
		{
			return false;
		}
		
		long timeSinceDeath = System.currentTimeMillis() - plugin.getFightEndTime();
		return timeSinceDeath > 0 && timeSinceDeath < DISPLAY_DURATION_MS;
	}
	
	private WorldPoint getBossDeathLocation()
	{
		if (plugin.getBossDeathLocation() != null)
		{
			return plugin.getBossDeathLocation();
		}
		
		if (plugin.getAraxxorNpc() != null)
		{
			WorldPoint location = plugin.getAraxxorNpc().getWorldLocation();
			if (location != null)
			{
				return location;
			}
		}
		
		return null;
	}
}
