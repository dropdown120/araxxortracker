
package net.runelite.client.plugins.araxxortracker;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.util.ColorUtil;

@Slf4j
class AraxxorOverlay extends OverlayPanel
{
	// ==================== CONSTANTS ====================
	private static final Color COLOR_WHITE = Color.WHITE;
	private static final Color COLOR_GOLD = new Color(255, 215, 0);
	private static final Color COLOR_GREEN_SUCCESS = new Color(100, 220, 100);
	private static final Color COLOR_RED = Color.RED;
	private static final Color COLOR_BACKGROUND = new Color(0, 0, 0, 190);
	
	private static final Dimension PANEL_SIZE = new Dimension(200, 0);
	private static final Dimension PANEL_SIZE_MINIMAL = new Dimension(150, 0);
	private static final Rectangle PANEL_BORDER = new Rectangle(5, 5, 5, 5);
	
	/**
	 * Result class for renderStartRotation to return rotation info for circle rendering
	 */
	private static class StartRotationInfo
	{
		final AraxxorEggType rotation;
		final int lineIndex;
		
		StartRotationInfo(AraxxorEggType rotation, int lineIndex)
		{
			this.rotation = rotation;
			this.lineIndex = lineIndex;
		}
	}
	
	private String formatWithColor(String text, Color color)
	{
		if (color == null || text == null || text.isEmpty())
		{
			return text;
		}
		return " " + ColorUtil.colorTag(color) + text + ColorUtil.colorTag(COLOR_WHITE);
	}
	
	private static final int NORMAL_PHASE_THRESHOLD_SECONDS = 40;
	private static final int ENRAGE_PHASE_THRESHOLD_SECONDS = 20;
	private static final String LABEL_WAITING = "Araxxor is waiting...";
	private static final String LABEL_NORMAL = "Normal:";
	private static final String LABEL_ENRAGE = "Enrage:";
	private static final String LABEL_TOTAL = "Time:";
	private static final String LABEL_TIME = "Time:";
	private static final String LABEL_START = "Start:";
	private static final String LABEL_NEXT = "Next:";
	private static final String LABEL_HITS = "Hits:";
	private static final String LABEL_AVG_HIT = "Avg Hit:";
	private static final String LABEL_DPS = "DPS:";
	private static final String LABEL_LOST_HP = "Lost:";
	
	private final AraxxorPlugin plugin;
	private final AraxxorConfig config;
	
	private long cachedFormattedTimeSimpleMs = -1;
	private String cachedFormattedTimeSimple = null;
	private long cachedFormattedTimeCompactMs = -1;
	private String cachedFormattedTimeCompact = null;
	private boolean cachedInBossArea = false;
	private long lastAreaCheckTime = 0;
	private static final long AREA_CHECK_THROTTLE_MS = 500;
	
	private static class SplitResult {
		final String text;
		final Color color;
		
		SplitResult(String text, Color color) {
			this.text = text;
			this.color = color;
		}
		
		static SplitResult none() {
			return new SplitResult("", COLOR_WHITE);
		}
	}
	
	private static class PercentageResult {
		final String text;
		final Color color;
		
		PercentageResult(String text, Color color) {
			this.text = text;
			this.color = color;
		}
		
		static PercentageResult none() {
			return new PercentageResult("", COLOR_WHITE);
		}
	}

	@Inject
	private AraxxorOverlay(AraxxorPlugin plugin, AraxxorConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// Clear panel to ensure clean state when switching modes
		panelComponent.getChildren().clear();
		
		boolean hasFightStarted = plugin.getFightStartTime() != -1;
			
			long currentTime = System.currentTimeMillis();
			boolean isInBossArea;
			boolean didAreaCheck = false;
			if (currentTime - lastAreaCheckTime > AREA_CHECK_THROTTLE_MS)
			{
				isInBossArea = plugin.isInAraxxorArea();
				cachedInBossArea = isInBossArea;
				lastAreaCheckTime = currentTime;
				didAreaCheck = true;
			}
			else
			{
				isInBossArea = cachedInBossArea;
			}
			
			if (didAreaCheck)
			{
				plugin.resetPhaseIfNotInArea();
			}
			
			// Check overlay mode early to handle minimal mode correctly
			OverlayMode overlayMode = config.overlayMode();
			boolean isFightActive = plugin.isFightActive();
			boolean bossReached0Hp = plugin.isAraxxorReachedZeroHp();
			boolean showAsFightEnded = !isFightActive || bossReached0Hp;
			
		// In minimal mode, show overlay during and after fight
		if (overlayMode == OverlayMode.MINIMAL)
		{
			// Check visibility conditions
			if (!isInBossArea && !config.showWhenInactive())
			{
				return null;
			}
			
			if (!hasFightStarted && !config.showWhenInactive())
			{
				return null;
			}
			
			setupPanelStyling(true);
			
			// If no fight started, show waiting message
			if (!hasFightStarted)
			{
				renderWaitingSection();
				return super.render(graphics);
			}
			
			long elapsedTime = plugin.getElapsedTime();
			AraxxorEggType currentRotationStart = plugin.getCurrentRotationStart();
			boolean showLiveSplits = config.showLiveSplits();
			SplitComparisonMode comparisonMode = config.splitComparisonMode();
			long rotationBestTime = currentRotationStart != null ? getRotationBestTime(currentRotationStart) : -1;
			long overallBestKillTime = plugin.getBestKillTime();
			
			// Show next egg countdown (egg rotation) - always white in minimal mode
			if (!showAsFightEnded)
			{
				renderNextEggMinimal();
			}
			
			// Calculate split for timer color determination
			long totalTime = elapsedTime;
			SplitResult totalSplit = showLiveSplits ? calculateTotalTimeSplit(totalTime, comparisonMode, currentRotationStart, rotationBestTime, overallBestKillTime) : SplitResult.none();
			
			// Determine split color: red for over time, green for good with star
			Color splitColor = COLOR_WHITE;
			if (showLiveSplits && !totalSplit.text.isEmpty())
			{
				if (totalSplit.color == COLOR_RED)
				{
					// Slower than comparison - red
					splitColor = COLOR_RED;
				}
				else if (totalSplit.color == COLOR_GOLD)
				{
					// Personal best - green with star (totalSplit.text already contains " ★")
					splitColor = COLOR_GREEN_SUCCESS;
				}
				else if (totalSplit.color == COLOR_GREEN_SUCCESS)
				{
					// Faster than comparison - green
					splitColor = COLOR_GREEN_SUCCESS;
				}
			}
			
			// Show timer with split - "Time:" label and time text are white, split is colored
			String totalTimeText = formatTimeSimple(totalTime);
			String rightText = totalTimeText;
			
			if (showLiveSplits && !totalSplit.text.isEmpty())
			{
				// Apply the appropriate color to the split text (red for over time, green for good/PB)
				rightText += formatWithColor(totalSplit.text, splitColor);
			}
			
			// "Time:" label and time value are white
			addLine(LABEL_TIME, rightText, COLOR_WHITE);
			
			return super.render(graphics);
		}
		
		// Main overlay mode - continue with normal logic
		// "Show Always" is the master - if disabled, use "Show Pre-Fight Stats" logic
		boolean showAlways = config.showWhenInactive();
		if (!showAlways)
		{
			// If "Show Always" is disabled, use "Show Pre-Fight Stats" logic
			if (!isInBossArea && !config.showPreFightStats())
			{
				return null;
			}
			
			if (!isInBossArea && config.showPreFightStats())
			{
				setupPanelStyling();
				renderWaitingSection();
				return super.render(graphics);
			}
			
			if (!hasFightStarted && !config.showPreFightStats())
			{
				return null;
			}
		}
		else
		{
			// "Show Always" is enabled - show pre-fight stats when not in boss area
			if (!isInBossArea && config.showPreFightStats())
			{
				setupPanelStyling();
				renderWaitingSection();
				return super.render(graphics);
			}
		}
			
		long elapsedTime = plugin.getElapsedTime();
		long currentTimeToEnrage = plugin.getCurrentTimeToEnrage();
		long currentTimeInEnrage = plugin.getCurrentTimeInEnrage();
		long enrageStartTime = plugin.getEnrageStartTime();
		AraxxorEggType currentRotationStart = plugin.getCurrentRotationStart();
		int currentFightHits = plugin.getCurrentFightHits();
		int currentFightDamageTaken = plugin.getCurrentFightDamageTaken();
		double averageHit = plugin.getAverageHit();
		double dps = plugin.getDPS();
		
		boolean showLiveSplits = config.showLiveSplits();
		SplitComparisonMode comparisonMode = config.splitComparisonMode();
		
		long rotationBestTime = currentRotationStart != null ? getRotationBestTime(currentRotationStart) : -1;
		int rotationBestHits = currentRotationStart != null ? getRotationBestHits(currentRotationStart) : -1;
		int rotationBestDamage = currentRotationStart != null ? getRotationBestDamage(currentRotationStart) : -1;
		
		long overallBestKillTime = plugin.getBestKillTime();
		long overallBestTimeToEnrage = plugin.getBestTimeToEnrage();
		long overallBestTimeInEnrage = plugin.getBestTimeInEnrage();

		// Render main overlay
		setupPanelStyling();

		StartRotationInfo startRotationInfo = null;
		if (hasFightStarted && !showAsFightEnded)
		{
			renderActiveFightSection();
		}
		else if (!hasFightStarted)
		{
			renderWaitingSection();
		}

		if (hasFightStarted)
		{
			if (!showAsFightEnded)
			{
				addSeparator();
				renderInFightTiming(enrageStartTime, elapsedTime, currentTimeInEnrage, currentTimeToEnrage, showLiveSplits, comparisonMode, currentRotationStart, rotationBestTime, overallBestTimeToEnrage, overallBestTimeInEnrage);
			}
			else
			{
				int lastFightHits = plugin.getLastFightHits();
				int lastFightDamageTaken = plugin.getLastFightDamageTaken();
				int bestHitCount = plugin.getBestHitCount();
				int bestDamageTaken = plugin.getBestDamageTaken();
				startRotationInfo = renderPostFightOverlay(elapsedTime, currentTimeToEnrage, currentTimeInEnrage, currentRotationStart, showLiveSplits, comparisonMode, rotationBestHits, rotationBestDamage, rotationBestTime, overallBestKillTime, lastFightHits, lastFightDamageTaken, bestHitCount, bestDamageTaken);
			}
		}
		
		if (hasFightStarted && !showAsFightEnded)
		{
			int lastFightHits = plugin.getLastFightHits();
			int lastFightDamageTaken = plugin.getLastFightDamageTaken();
			int bestHitCount = plugin.getBestHitCount();
			int bestDamageTaken = plugin.getBestDamageTaken();
			renderInFightPerformance(currentFightHits, averageHit, dps, currentFightDamageTaken, showLiveSplits, comparisonMode, currentRotationStart, rotationBestHits, rotationBestDamage, bossReached0Hp, lastFightHits, lastFightDamageTaken, bestHitCount, bestDamageTaken);
		}

		Dimension mainDimension = super.render(graphics);

		if (showAsFightEnded && mainDimension != null && startRotationInfo != null)
		{
			renderStartCircle(graphics, mainDimension, startRotationInfo);
		}
		
		return mainDimension;
	}
	
	// ==================== HELPER METHODS ====================
	
	private void setupPanelStyling()
	{
		setupPanelStyling(false);
	}
	
	private void setupPanelStyling(boolean minimal)
	{
		panelComponent.setPreferredSize(minimal ? PANEL_SIZE_MINIMAL : PANEL_SIZE);
		panelComponent.setBackgroundColor(COLOR_BACKGROUND);
		panelComponent.setBorder(PANEL_BORDER);
	}
	
	private void renderActiveFightSection()
	{
		renderNextEgg();
	}

	/**
	 * Render start rotation line and return info for circle rendering if post-fight
	 * @return StartRotationInfo if post-fight and should render circle, null otherwise
	 */
	private StartRotationInfo renderStartRotation()
	{
		AraxxorEggType startRotation = plugin.getCurrentRotationStart();
		if (startRotation == null)
		{
			return null;
		}
		
		boolean isPostFight = plugin.isAraxxorReachedZeroHp();
		
		if (!isPostFight && plugin.getEggHistoryCount() >= 2)
		{
			return null;
		}

		if (isPostFight)
		{
			int lineIndex = panelComponent.getChildren().size();
			
			String specialAttackName = startRotation.getAttackName();
			String startDisplay = startRotation.getIcon() + " (" + specialAttackName + ")";
			addLine(LABEL_START, startDisplay, COLOR_WHITE);
			
			return new StartRotationInfo(startRotation, lineIndex);
		}
		else
		{
			String startText = startRotation.getIcon() + " (" + startRotation.getAttackName() + ")";
			addLine(LABEL_START, startText, COLOR_GOLD);
			return null;
		}
	}
	
	private void renderWaitingSection()
	{
		panelComponent.getChildren().add(LineComponent.builder()
			.left(LABEL_WAITING)
			.right("")
			.build());
	}
	
	private void renderInFightTiming(long enrageStartTime, long elapsedTime, long currentTimeInEnrage, long currentTimeToEnrage, boolean showLiveSplits, SplitComparisonMode comparisonMode, AraxxorEggType currentRotationStart, long rotationBestTime, long overallBestTimeToEnrage, long overallBestTimeInEnrage)
	{
		long normalTime = (enrageStartTime > 0 && currentTimeToEnrage > 0) ? currentTimeToEnrage : elapsedTime;
		normalTime += 1000;

		String normalTimeText = formatTimeSimple(normalTime);
		
		if (showLiveSplits)
		{
			SplitResult normalSplit = calculateSplitForPhase(normalTime, true, NORMAL_PHASE_THRESHOLD_SECONDS, comparisonMode, currentRotationStart, rotationBestTime, overallBestTimeToEnrage);
			String rightText = normalTimeText;
			if (!normalSplit.text.isEmpty()) {
				rightText += formatWithColor(normalSplit.text, normalSplit.color);
			}
			addLine(LABEL_NORMAL, rightText, COLOR_WHITE);
		}
		else
		{
			addLine(LABEL_NORMAL, normalTimeText, COLOR_WHITE);
		}

		if (enrageStartTime > 0)
		{
			long enrageTime = currentTimeInEnrage;
			String enrageTimeText = formatTimeSimple(enrageTime);
			
			if (showLiveSplits)
			{
				SplitResult enrageSplit = calculateSplitForPhase(enrageTime, false, ENRAGE_PHASE_THRESHOLD_SECONDS, comparisonMode, currentRotationStart, rotationBestTime, overallBestTimeInEnrage);
				String rightText = enrageTimeText;
				if (!enrageSplit.text.isEmpty()) {
					rightText += formatWithColor(enrageSplit.text, enrageSplit.color);
				}
				addLine(LABEL_ENRAGE, rightText, COLOR_WHITE);
			}
			else
			{
				addLine(LABEL_ENRAGE, enrageTimeText, COLOR_WHITE);
			}
		}
	}
	
	/**
	 * Render post-fight overlay and return rotation info for circle rendering
	 * @return StartRotationInfo if should render circle, null otherwise
	 */
	private StartRotationInfo renderPostFightOverlay(long elapsedTime, long currentTimeToEnrage, long currentTimeInEnrage, AraxxorEggType currentRotationStart, boolean showLiveSplits, SplitComparisonMode comparisonMode, int rotationBestHits, int rotationBestDamage, long rotationBestTime, long overallBestKillTime, int lastFightHits, int lastFightDamageTaken, int bestHitCount, int bestDamageTaken)
	{
		if (!plugin.isAraxxorReachedZeroHp())
		{
			renderAbortedFight();
			return null;
		}
		
		StartRotationInfo rotationInfo = renderStartRotation();
		addSeparator();
		
		long totalTime = elapsedTime;
		String totalTimeText = formatTimeCompact(totalTime);
		
		if (showLiveSplits)
		{
			SplitResult totalSplit = calculateTotalTimeSplit(totalTime, comparisonMode, currentRotationStart, rotationBestTime, overallBestKillTime);
			if (!totalSplit.text.isEmpty()) {
				String rightText = totalTimeText + formatWithColor(totalSplit.text, totalSplit.color);
				addLine(LABEL_TOTAL, rightText, COLOR_WHITE);
			} else {
				addLine(LABEL_TOTAL, totalTimeText, COLOR_WHITE);
			}
		} else {
			addLine(LABEL_TOTAL, totalTimeText, COLOR_WHITE);
		}
		
		addSeparator();
		
		long timeToEnrage = currentTimeToEnrage;
		long timeInEnrage = currentTimeInEnrage;
		
		long overallBestTimeToEnrage = plugin.getBestTimeToEnrage();
		long overallBestTimeInEnrage = plugin.getBestTimeInEnrage();
		
		if (timeToEnrage > 0)
		{
			long normalTime = timeToEnrage + 1000;
			String normalTimeText = formatTimeSimple(normalTime);
			
			if (showLiveSplits)
			{
				SplitResult normalSplit = calculatePostFightSplit(normalTime, true, comparisonMode, currentRotationStart, rotationBestTime, overallBestTimeToEnrage);
				if (!normalSplit.text.isEmpty()) {
					String rightText = normalTimeText + formatWithColor(normalSplit.text, normalSplit.color);
					addLine(LABEL_NORMAL, rightText, COLOR_WHITE);
				} else {
					addLine(LABEL_NORMAL, normalTimeText, COLOR_WHITE);
				}
			} else {
				addLine(LABEL_NORMAL, normalTimeText, COLOR_WHITE);
			}
		}

		if (timeInEnrage > 0)
		{
			String enrageTimeText = formatTimeSimple(timeInEnrage);
			
			if (showLiveSplits)
			{
				SplitResult enrageSplit = calculatePostFightSplit(timeInEnrage, false, comparisonMode, currentRotationStart, rotationBestTime, overallBestTimeInEnrage);
				if (!enrageSplit.text.isEmpty()) {
					String rightText = enrageTimeText + formatWithColor(enrageSplit.text, enrageSplit.color);
					addLine(LABEL_ENRAGE, rightText, COLOR_WHITE);
				} else {
					addLine(LABEL_ENRAGE, enrageTimeText, COLOR_WHITE);
				}
			} else {
				addLine(LABEL_ENRAGE, enrageTimeText, COLOR_WHITE);
			}
		}
		
		addSeparator();
			renderPostFightPerformance(showLiveSplits, comparisonMode, currentRotationStart, rotationBestHits, rotationBestDamage, lastFightHits, lastFightDamageTaken, bestHitCount, bestDamageTaken, elapsedTime);
		
		return rotationInfo;
	}
	
	private void renderAbortedFight()
	{
		panelComponent.getChildren().add(LineComponent.builder()
			.left(LABEL_WAITING)
			.right("")
			.build());
	}
	
	private void renderPostFightPerformance(boolean showLiveSplits, SplitComparisonMode comparisonMode, AraxxorEggType currentRotationStart, int rotationBestHits, int rotationBestDamage, int lastFightHits, int lastFightDamageTaken, int bestHitCount, int bestDamageTaken, long elapsedTime)
	{
		int currentHits = plugin.getCurrentFightHits();
		int currentDamageTaken = plugin.getCurrentFightDamageTaken();
		int currentDamageDealt = plugin.getCurrentFightDamageDealt();
		
		int displayHits = currentHits > 0 ? currentHits : lastFightHits;
		int displayDamageTaken = currentDamageTaken > 0 ? currentDamageTaken : lastFightDamageTaken;
		
		int maxHits = rotationBestHits >= 0 ? rotationBestHits : bestHitCount;
		int bestDamage = rotationBestDamage >= 0 ? rotationBestDamage : bestDamageTaken;
		
		boolean showPercentages = showLiveSplits;
		
		String hitsText;
		if (maxHits > 0) {
			StringBuilder sb = new StringBuilder(12);
			sb.append(displayHits).append('/').append(maxHits);
			hitsText = sb.toString();
		} else {
			hitsText = String.valueOf(displayHits);
		}
		if (showPercentages) {
			PercentageResult hitsPercent = calculateHitsPercentage(currentHits, comparisonMode, currentRotationStart, rotationBestHits, lastFightHits, bestHitCount);
			if (!hitsPercent.text.isEmpty()) {
				String rightText = hitsText + formatWithColor(hitsPercent.text, hitsPercent.color);
				addLine(LABEL_HITS, rightText, COLOR_WHITE);
			} else {
				addLine(LABEL_HITS, hitsText, COLOR_WHITE);
			}
		} else {
			addLine(LABEL_HITS, hitsText, COLOR_WHITE);
		}

		double avgHit = (currentHits > 0 && currentDamageDealt > 0) ? ((double) currentDamageDealt / currentHits) : 0.0;
		String avgHitText = String.valueOf((int) Math.round(avgHit));
		addLine(LABEL_AVG_HIT, avgHitText, COLOR_WHITE);

		double dps = (elapsedTime > 0 && currentDamageDealt > 0) ? (currentDamageDealt / (elapsedTime / 1000.0)) : 0.0;
		String dpsText = String.valueOf((int) Math.round(dps));
		addLine(LABEL_DPS, dpsText, COLOR_WHITE);

		addSeparator();

		String damageText;
		if (bestDamage > 0) {
			StringBuilder sb = new StringBuilder(16);
			sb.append(displayDamageTaken).append('/').append(bestDamage).append(" HP");
			damageText = sb.toString();
		} else {
			StringBuilder sb = new StringBuilder(12);
			sb.append(displayDamageTaken).append(" HP");
			damageText = sb.toString();
		}
		if (showPercentages) {
			PercentageResult damagePercent = calculateDamagePercentage(currentDamageTaken, comparisonMode, currentRotationStart, rotationBestDamage, lastFightDamageTaken, bestDamageTaken);
			if (!damagePercent.text.isEmpty()) {
				String rightText = damageText + formatWithColor(damagePercent.text, damagePercent.color);
				addLine(LABEL_LOST_HP, rightText, COLOR_WHITE);
			} else {
				addLine(LABEL_LOST_HP, damageText, COLOR_WHITE);
			}
		} else {
			addLine(LABEL_LOST_HP, damageText, COLOR_WHITE);
		}
	}
	
	private void renderInFightPerformance(int currentFightHits, double averageHit, double dps, int currentFightDamageTaken, boolean showLiveSplits, SplitComparisonMode comparisonMode, AraxxorEggType currentRotationStart, int rotationBestHits, int rotationBestDamage, boolean bossReached0Hp, int lastFightHits, int lastFightDamageTaken, int bestHitCount, int bestDamageTaken)
	{
		addSeparator();
		
		boolean showPercentages = bossReached0Hp;
		
		String hitsText = String.valueOf(currentFightHits);
		if (showLiveSplits && showPercentages) {
			PercentageResult hitsPercent = calculateHitsPercentage(currentFightHits, comparisonMode, currentRotationStart, rotationBestHits, lastFightHits, bestHitCount);
			if (!hitsPercent.text.isEmpty()) {
				String rightText = hitsText + formatWithColor(hitsPercent.text, hitsPercent.color);
				addLine(LABEL_HITS, rightText, COLOR_WHITE);
			} else {
				addLine(LABEL_HITS, hitsText, COLOR_WHITE);
			}
		} else {
			addLine(LABEL_HITS, hitsText, COLOR_WHITE);
		}
		
		String avgHitText = averageHit > 0 ? String.valueOf((int) Math.round(averageHit)) : "0";
		addLine(LABEL_AVG_HIT, avgHitText, COLOR_WHITE);
		
		String dpsText = dps > 0 ? String.valueOf((int) Math.round(dps)) : "0";
		addLine(LABEL_DPS, dpsText, COLOR_WHITE);
		
		addSeparator();
		
		String damageText = String.valueOf(currentFightDamageTaken) + " HP";
		if (showLiveSplits && showPercentages) {
			PercentageResult damagePercent = calculateDamagePercentage(currentFightDamageTaken, comparisonMode, currentRotationStart, rotationBestDamage, lastFightDamageTaken, bestDamageTaken);
			if (!damagePercent.text.isEmpty()) {
				String rightText = damageText + formatWithColor(damagePercent.text, damagePercent.color);
				addLine(LABEL_LOST_HP, rightText, COLOR_WHITE);
			} else {
				addLine(LABEL_LOST_HP, damageText, COLOR_WHITE);
			}
		} else {
			addLine(LABEL_LOST_HP, damageText, COLOR_WHITE);
		}
	}
	
	private void renderStartCircle(Graphics2D graphics, Dimension mainDimension, StartRotationInfo rotationInfo)
	{
		if (rotationInfo == null || rotationInfo.lineIndex < 0 || rotationInfo.lineIndex >= panelComponent.getChildren().size())
		{
			return;
		}
		
		net.runelite.client.ui.overlay.components.LayoutableRenderableEntity child = panelComponent.getChildren().get(rotationInfo.lineIndex);
		if (!(child instanceof net.runelite.client.ui.overlay.components.LineComponent))
		{
			return;
		}
		
		net.runelite.client.ui.overlay.components.LineComponent lineComponent = (net.runelite.client.ui.overlay.components.LineComponent) child;
		java.awt.Rectangle lineBounds = lineComponent.getBounds();
		
		if (lineBounds.isEmpty())
		{
			return;
		}
		
		java.awt.FontMetrics fm = graphics.getFontMetrics();
		int fmHeight = fm.getHeight();
		int yPos = lineBounds.y + fmHeight;
		
		String rightText = rotationInfo.rotation.getIcon() + " (" + rotationInfo.rotation.getAttackName() + ")";
		int rightTextWidth = fm.stringWidth(rightText);
		int lineWidth = lineBounds.width;
		int rightTextStartX = lineBounds.x + lineWidth - rightTextWidth;
		
		graphics.setColor(rotationInfo.rotation.getColor());
		graphics.drawString(rotationInfo.rotation.getIcon(), rightTextStartX, yPos);
	}
	
	// ==================== SPLIT CALCULATION ====================
	
	private SplitResult calculateSplitForPhase(long currentTime, boolean isNormalPhase, int thresholdSeconds, SplitComparisonMode comparisonMode, AraxxorEggType currentRotationStart, long rotationBestTime, long overallBestTime)
	{
		long diff = calculateDiff(currentTime, isNormalPhase, comparisonMode, currentRotationStart, rotationBestTime, overallBestTime);
		
		if (diff < -3000)
		{
			return SplitResult.none();
		}
		
		long diffSeconds = diff / 1000;
		
		if (diff > 0)
		{
			StringBuilder sb = new StringBuilder(" (+");
			sb.append(diffSeconds).append("s)");
			return new SplitResult(sb.toString(), COLOR_RED);
		}
		else if (diff < 0)
		{
			if (isPB(currentTime, isNormalPhase, currentRotationStart, rotationBestTime, overallBestTime))
			{
				return new SplitResult(" ★", COLOR_GOLD);
			}
			else
			{
				long absDiffSeconds = Math.abs(diffSeconds);
				StringBuilder sb = new StringBuilder(" (-");
				sb.append(absDiffSeconds).append("s)");
				return new SplitResult(sb.toString(), COLOR_GREEN_SUCCESS);
			}
		}
		else
		{
			return new SplitResult(" (0s)", COLOR_WHITE);
		}
	}
	
	private SplitResult calculatePostFightSplit(long currentTime, boolean isNormalPhase, SplitComparisonMode comparisonMode, AraxxorEggType currentRotationStart, long rotationBestTime, long overallBestTime)
	{
		long diff = calculateDiff(currentTime, isNormalPhase, comparisonMode, currentRotationStart, rotationBestTime, overallBestTime);
		
		if (diff < -3000)
		{
			return SplitResult.none();
		}
		
		long diffSeconds = diff / 1000;
		
		if (diff > 0)
		{
			StringBuilder sb = new StringBuilder(" (+");
			sb.append(diffSeconds).append("s)");
			return new SplitResult(sb.toString(), COLOR_RED);
		}
		else if (diff < 0)
		{
			long bestTime = isNormalPhase ? plugin.getBestTimeToEnrage() : plugin.getBestTimeInEnrage();
			long rotationBest = isNormalPhase && currentRotationStart != null ? rotationBestTime : -1;
			if (isPB(currentTime, isNormalPhase, currentRotationStart, rotationBest, bestTime))
			{
				return new SplitResult(" ★", COLOR_GOLD);
			}
			else
			{
				long absDiffSeconds = Math.abs(diffSeconds);
				StringBuilder sb = new StringBuilder(" (-");
				sb.append(absDiffSeconds).append("s)");
				return new SplitResult(sb.toString(), COLOR_GREEN_SUCCESS);
			}
		}
		else
		{
			return new SplitResult(" (0s)", COLOR_WHITE);
		}
	}
	
	private SplitResult calculateTotalTimeSplit(long totalTime, SplitComparisonMode comparisonMode, AraxxorEggType currentRotationStart, long rotationBestTime, long overallBestKillTime)
	{
		long compareTime = -1;
		boolean isPB = false;
		SplitComparisonMode mode = comparisonMode;
		
		if (mode == SplitComparisonMode.TARGET)
		{
			// Use target total time if set
			int targetSeconds = config.targetTotalTime();
			if (targetSeconds > 0)
			{
				compareTime = targetSeconds * 1000L;
			}
			// If no target time exists, fall back to best time
			if (compareTime <= 0)
			{
				// Use cached rotation and overall best times (performance optimization)
				// Check if this is a PB (either rotation-specific or overall)
				isPB = (rotationBestTime > 0 && totalTime == rotationBestTime) ||
					   (overallBestKillTime > 0 && totalTime == overallBestKillTime);
				
				// Use rotation best time for comparison if available, otherwise use overall best
				compareTime = rotationBestTime > 0 ? rotationBestTime : overallBestKillTime;
			}
		}
		else if (mode == SplitComparisonMode.LAST_KILL)
		{
			// Calculate total time from last fight phase times
			long lastNormal = plugin.getLastFightNormalTime();
			long lastEnrage = plugin.getLastFightEnrageTime();
			if (lastNormal > 0)
			{
				compareTime = lastNormal + (lastEnrage > 0 ? lastEnrage : 0);
			}
			// If no last kill data exists, fall back to best time
			if (compareTime <= 0)
			{
				// Use cached rotation and overall best times (performance optimization)
				// Check if this is a PB (either rotation-specific or overall)
				isPB = (rotationBestTime > 0 && totalTime == rotationBestTime) ||
					   (overallBestKillTime > 0 && totalTime == overallBestKillTime);
				
				// Use rotation best time for comparison if available, otherwise use overall best
				compareTime = rotationBestTime > 0 ? rotationBestTime : overallBestKillTime;
			}
		}
		else
		{
			// PERSONAL_BEST mode - use cached rotation and overall best times
			// Check if this is a PB (either rotation-specific or overall)
			isPB = (rotationBestTime > 0 && totalTime == rotationBestTime) ||
				   (overallBestKillTime > 0 && totalTime == overallBestKillTime);
			
			// Use rotation best time for comparison if available, otherwise use overall best
			compareTime = rotationBestTime > 0 ? rotationBestTime : overallBestKillTime;
		}
		
		// If no comparison time exists, can't show split
		if (compareTime <= 0)
		{
			return SplitResult.none();
		}
		
		long diff = totalTime - compareTime;
		
		if (diff < -3000)
		{
			return SplitResult.none();
		}
		
		if (isPB)
		{
			return new SplitResult(" ★", COLOR_GOLD);
		}
		
		long diffSeconds = diff / 1000;
		
		if (diff > 0)
		{
			StringBuilder sb = new StringBuilder(8);
			sb.append(" (+").append(diffSeconds).append("s)");
			return new SplitResult(sb.toString(), COLOR_RED);
		}
		else if (diff < 0)
		{
			long absDiffSeconds = Math.abs(diffSeconds);
			StringBuilder sb = new StringBuilder(8);
			sb.append(" (-").append(absDiffSeconds).append("s)");
			return new SplitResult(sb.toString(), COLOR_GREEN_SUCCESS);
		}
		else
		{
			return new SplitResult(" (0s)", COLOR_WHITE);
		}
	}
	
	private boolean isPB(long currentTime, boolean isNormalPhase, AraxxorEggType currentRotationStart, long rotationBestTime, long overallBestTime)
	{
		boolean isOverallPB = overallBestTime > 0 && currentTime == overallBestTime;
		
		if (!isNormalPhase)
		{
			return isOverallPB;
		}
		
		if (currentRotationStart != null && rotationBestTime > 0)
		{
			boolean isRotationPB = currentTime == rotationBestTime;
			return isOverallPB || isRotationPB;
		}
		
		return isOverallPB;
	}
	
	private long getRotationBestTime(AraxxorEggType rotation)
	{
		if (rotation == null)
		{
			return -1;
		}
		return plugin.getRotationBestTime(rotation);
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
	
	// ==================== UTILITY METHODS ====================
	
	private void addSeparator()
	{
		panelComponent.getChildren().add(LineComponent.builder()
			.left("")
			.right("")
			.build());
	}
	
	private void addLine(String left, String right, Color rightColor)
	{
		panelComponent.getChildren().add(LineComponent.builder()
			.left(left)
			.right(right)
			.rightColor(rightColor)
			.build());
	}
	private static final double TICKS_TO_SECONDS = 0.6;
	
	private void renderNextEgg()
	{
		int ticksRemaining = plugin.getTicksUntilNextHatch();
		if (ticksRemaining < 0)
		{
			ticksRemaining = 0;
		}
		double secondsRemaining = ticksRemaining > 0 ? ticksRemaining * TICKS_TO_SECONDS : 0;

		int eggHistoryCount = plugin.getEggHistoryCount();

		if (eggHistoryCount >= 3)
		{
			renderNextEggWithKnownPattern(eggHistoryCount, ticksRemaining, secondsRemaining);
		}
		else
		{
			renderNextEggUnknownPattern(ticksRemaining, secondsRemaining);
		}
	}

	private void renderNextEggWithKnownPattern(int eggHistoryCount,
			int ticksRemaining, double secondsRemaining)
	{
		AraxxorEggType nextEgg = plugin.getNextEggType();

		if (nextEgg == null) {
			nextEgg = AraxxorEggType.RED;
		}

		renderNormalEggCountdown(nextEgg, ticksRemaining, secondsRemaining);
	}


	private void renderNormalEggCountdown(AraxxorEggType nextEgg, int ticksRemaining, double secondsRemaining)
	{
		StringBuilder eggText = new StringBuilder();
		
		boolean wasSkipped = plugin.wasNextEggJustSkipped();
		AraxxorEggType skippedEggType = plugin.getLastEggDespawnType();
		
		AraxxorEggType displayEgg;
		Color textColor;
		
		if (wasSkipped && skippedEggType != null)
		{
			displayEgg = skippedEggType;
			textColor = COLOR_WHITE;
		}
		else
		{
			displayEgg = nextEgg;
			textColor = displayEgg.getColor();
		}
		
		eggText.append(displayEgg.getIcon());

		boolean anyMinionsSpawned = plugin.getLastMinionSpawnTime() > 0;
		if (wasSkipped && skippedEggType != null)
		{
			eggText.append(" SKIPPED!");
		}
		else if (anyMinionsSpawned || ticksRemaining >= 0)
		{
			eggText.append(" ");
			if (ticksRemaining > 1)
			{
				eggText.append(Math.round(secondsRemaining)).append("s");
			}
			else if (ticksRemaining >= 0 && ticksRemaining <= 1)
			{
				eggText.append("(NOW!)");
			}
		}

		panelComponent.getChildren().add(LineComponent.builder()
			.left(LABEL_NEXT)
			.right(eggText.toString())
			.rightColor(textColor)
			.build());
	}
	
	private void renderNextEggMinimal()
	{
		int ticksRemaining = plugin.getTicksUntilNextHatch();
		if (ticksRemaining < 0)
		{
			ticksRemaining = 0;
		}
		double secondsRemaining = ticksRemaining > 0 ? ticksRemaining * TICKS_TO_SECONDS : 0;

		int eggHistoryCount = plugin.getEggHistoryCount();

		if (eggHistoryCount >= 3)
		{
			renderNextEggWithKnownPatternMinimal(eggHistoryCount, ticksRemaining, secondsRemaining);
		}
		else
		{
			renderNextEggUnknownPatternMinimal(ticksRemaining, secondsRemaining);
		}
	}
	
	private void renderNextEggWithKnownPatternMinimal(int eggHistoryCount,
			int ticksRemaining, double secondsRemaining)
	{
		AraxxorEggType nextEgg = plugin.getNextEggType();

		if (nextEgg == null) {
			nextEgg = AraxxorEggType.RED;
		}

		renderNormalEggCountdownMinimal(nextEgg, ticksRemaining, secondsRemaining);
	}
	
	private void renderNormalEggCountdownMinimal(AraxxorEggType nextEgg, int ticksRemaining, double secondsRemaining)
	{
		StringBuilder eggText = new StringBuilder();
		
		boolean wasSkipped = plugin.wasNextEggJustSkipped();
		AraxxorEggType skippedEggType = plugin.getLastEggDespawnType();
		
		AraxxorEggType displayEgg;
		Color iconColor;
		
		if (wasSkipped && skippedEggType != null)
		{
			displayEgg = skippedEggType;
			iconColor = COLOR_WHITE;
		}
		else
		{
			displayEgg = nextEgg;
			iconColor = displayEgg.getColor();
		}
		
		// Color the egg icon with rotation color, then reset to white for countdown
		eggText.append(ColorUtil.colorTag(iconColor));
		eggText.append(displayEgg.getIcon());
		eggText.append(ColorUtil.colorTag(COLOR_WHITE));

		boolean anyMinionsSpawned = plugin.getLastMinionSpawnTime() > 0;
		if (wasSkipped && skippedEggType != null)
		{
			eggText.append(" SKIPPED!");
		}
		else if (anyMinionsSpawned || ticksRemaining >= 0)
		{
			eggText.append(" ");
			if (ticksRemaining > 1)
			{
				eggText.append(Math.round(secondsRemaining)).append("s");
			}
			else if (ticksRemaining >= 0 && ticksRemaining <= 1)
			{
				eggText.append("(NOW!)");
			}
		}

		// In minimal mode, countdown text is white (icon is colored via tags)
		panelComponent.getChildren().add(LineComponent.builder()
			.left(LABEL_NEXT)
			.right(eggText.toString())
			.rightColor(COLOR_WHITE)
			.build());
	}
	
	private void renderNextEggUnknownPatternMinimal(int ticksRemaining, double secondsRemaining)
	{
		String countdownDisplay = "";

		if (ticksRemaining > 1)
		{
			countdownDisplay = new StringBuilder("(")
				.append(Math.round(secondsRemaining))
				.append("s)")
				.toString();
		}
		else if (ticksRemaining >= 0 && ticksRemaining <= 1)
		{
			countdownDisplay = "(NOW!)";
		}

		// In minimal mode, countdown is always white
		panelComponent.getChildren().add(LineComponent.builder()
			.left("First egg:")
			.right(countdownDisplay)
			.rightColor(COLOR_WHITE)
			.build());
	}

	private void renderNextEggUnknownPattern(int ticksRemaining, double secondsRemaining)
	{
		String countdownDisplay = "";
		Color countdownColor = Color.YELLOW;

		if (ticksRemaining > 1)
		{
			countdownDisplay = new StringBuilder("(")
				.append(Math.round(secondsRemaining))
				.append("s)")
				.toString();
		}
		else if (ticksRemaining >= 0 && ticksRemaining <= 1)
		{
			countdownDisplay = "(NOW!)";
		}

		panelComponent.getChildren().add(LineComponent.builder()
			.left("First egg:")
			.right(countdownDisplay)
			.rightColor(countdownColor)
			.build());
	}

	private String formatTimeSimple(long milliseconds)
	{
		if (milliseconds < 0)
		{
			return "0:00";
		}
		
		long roundedMs = (milliseconds / 1000) * 1000;
		if (cachedFormattedTimeSimpleMs == roundedMs && cachedFormattedTimeSimple != null)
		{
			return cachedFormattedTimeSimple;
		}
		
		long totalSeconds = milliseconds / 1000;
		long minutes = totalSeconds / 60;
		long seconds = totalSeconds % 60;
		
		StringBuilder sb = new StringBuilder(8);
		sb.append(minutes).append(':');
		if (seconds < 10) {
			sb.append('0');
		}
		sb.append(seconds);
		
		cachedFormattedTimeSimple = sb.toString();
		cachedFormattedTimeSimpleMs = roundedMs;
		return cachedFormattedTimeSimple;
	}

	private String formatTimeCompact(long milliseconds) {
		if (milliseconds < 0) {
			return "0s";
		}
		
		long roundedMs = (milliseconds / 1000) * 1000;
		if (cachedFormattedTimeCompactMs == roundedMs && cachedFormattedTimeCompact != null)
		{
			return cachedFormattedTimeCompact;
		}
		
		long totalSeconds = milliseconds / 1000;
		String result;
		if (totalSeconds < 60) {
			StringBuilder sb = new StringBuilder(8);
			sb.append(totalSeconds).append('s');
			result = sb.toString();
		} else {
			long minutes = totalSeconds / 60;
			long seconds = totalSeconds % 60;
			StringBuilder sb = new StringBuilder(8);
			sb.append(minutes).append(':');
			if (seconds < 10) {
				sb.append('0');
			}
			sb.append(seconds);
			result = sb.toString();
		}
		
		cachedFormattedTimeCompact = result;
		cachedFormattedTimeCompactMs = roundedMs;
		return cachedFormattedTimeCompact;
	}

	private long calculateDiff(long currentTime, boolean isNormalPhase, SplitComparisonMode mode, AraxxorEggType currentRotationStart, long rotationBestTime, long overallBestTime) {
		long compareTime = -1;

		if (mode == SplitComparisonMode.TARGET) {
			// Use total time target for all comparisons (simplified to just total time)
			int targetSeconds = config.targetTotalTime();
			if (targetSeconds > 0) {
				compareTime = targetSeconds * 1000L;
			}
			if (compareTime <= 0) {
				compareTime = overallBestTime;
			}
		} else if (mode == SplitComparisonMode.LAST_KILL) {
			long lastNormal = plugin.getLastFightNormalTime();
			long lastEnrage = plugin.getLastFightEnrageTime();
			compareTime = isNormalPhase ? lastNormal : lastEnrage;
			
			if (compareTime <= 0) {
				compareTime = overallBestTime;
			}
		} else {
			compareTime = overallBestTime;
		}


		if (compareTime > 0) {
			long adjustedCurrentTime = currentTime - 1000;
			long diff = adjustedCurrentTime - compareTime;
			return diff;
		}
		
		return -10000;
	}

	private PercentageResult calculateHitsPercentage(int currentHits, SplitComparisonMode comparisonMode, AraxxorEggType currentRotationStart, int rotationBestHits, int lastFightHits, int bestHitCount) {
		int compareHits = -1;
		int bestHits = -1;
		SplitComparisonMode mode = comparisonMode;
		
		if (mode == SplitComparisonMode.TARGET) {
			compareHits = rotationBestHits >= 0 ? rotationBestHits : bestHitCount;
			bestHits = compareHits;
		} else if (mode == SplitComparisonMode.LAST_KILL) {
			compareHits = lastFightHits;
			if (compareHits > 0 && compareHits == currentHits) {
				compareHits = rotationBestHits >= 0 ? rotationBestHits : bestHitCount;
			} else if (compareHits <= 0) {
				compareHits = rotationBestHits >= 0 ? rotationBestHits : bestHitCount;
			}
			bestHits = rotationBestHits >= 0 ? rotationBestHits : bestHitCount;
		} else {
			compareHits = rotationBestHits >= 0 ? rotationBestHits : bestHitCount;
			bestHits = compareHits;
		}
		
		if (compareHits <= 0 || currentHits <= 0) {
			return PercentageResult.none();
		}
		
		if (bestHits > 0 && currentHits == bestHits) {
			return new PercentageResult(" ★", COLOR_GOLD);
		}
		
		double percentage = ((double)(currentHits - compareHits) / compareHits) * 100;
		if (Math.abs(percentage) < 0.5) {
			return PercentageResult.none();
		}
		
		StringBuilder sb = new StringBuilder(12);
		sb.append(" (");
		if (percentage > 0) sb.append("+");
		sb.append((int)Math.round(percentage)).append("%)");
		Color color = percentage > 0 ? COLOR_GREEN_SUCCESS : COLOR_RED;
		return new PercentageResult(sb.toString(), color);
	}

	private PercentageResult calculateDamagePercentage(int currentDamage, SplitComparisonMode comparisonMode, AraxxorEggType currentRotationStart, int rotationBestDamage, int lastFightDamageTaken, int bestDamageTaken) {
		int compareDamage = -1;
		int bestDamage = -1;
		SplitComparisonMode mode = comparisonMode;
		
		if (mode == SplitComparisonMode.TARGET) {
			compareDamage = rotationBestDamage >= 0 ? rotationBestDamage : bestDamageTaken;
			bestDamage = compareDamage;
		} else if (mode == SplitComparisonMode.LAST_KILL) {
			compareDamage = lastFightDamageTaken;
			if (compareDamage > 0 && compareDamage == currentDamage) {
				compareDamage = rotationBestDamage >= 0 ? rotationBestDamage : bestDamageTaken;
			} else if (compareDamage <= 0) {
				compareDamage = rotationBestDamage >= 0 ? rotationBestDamage : bestDamageTaken;
			}
			bestDamage = rotationBestDamage >= 0 ? rotationBestDamage : bestDamageTaken;
		} else {
			compareDamage = rotationBestDamage >= 0 ? rotationBestDamage : bestDamageTaken;
			bestDamage = compareDamage;
		}
		
		if (compareDamage <= 0 || currentDamage < 0) {
			return PercentageResult.none();
		}
		
		if (bestDamage > 0 && currentDamage == bestDamage) {
			return new PercentageResult(" ★", COLOR_GOLD);
		}
		
		double percentage = ((double)(currentDamage - compareDamage) / compareDamage) * 100;
		if (Math.abs(percentage) < 0.5) {
			return PercentageResult.none();
		}
		
		StringBuilder sb = new StringBuilder(12);
		sb.append(" (");
		if (percentage > 0) sb.append("+");
		sb.append((int)Math.round(percentage)).append("%)");
		Color color = percentage < 0 ? COLOR_GREEN_SUCCESS : COLOR_RED;
		return new PercentageResult(sb.toString(), color);
	}


}

