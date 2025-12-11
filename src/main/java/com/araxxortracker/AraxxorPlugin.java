package com.araxxortracker;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.Getter;
import lombok.Value;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.ChatMessageType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.Hitsplat;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ServerNpcLoot;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.time.Duration;
import java.time.Instant;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.callback.ClientThread;

@PluginDescriptor(
	name = "Araxxor Tracker",
	description = "Comprehensive Araxxor boss fight tracker with mechanics, timing, and phase detection",
	tags = {"boss", "combat", "timer", "araxxor", "pvm"},
	enabledByDefault = true
)
public class AraxxorPlugin extends Plugin
{
	private static final int ARAXXOR_NPC_ID = NpcID.ARAXXOR;
	private static final int ARAXXOR_DEAD_ID = NpcID.ARAXXOR_DEAD;
	
	private static final int EARLY_DESPAWN_THRESHOLD = 10;
	private static final int MAX_NPC_ITERATION_CHECK = 50;
	private static final int MAX_AREA_DISTANCE = 50;
	private static final double ENRAGE_HP_THRESHOLD = 0.25;
	private static final long GAME_TIMER_OFFSET_MS = 1800;
	
	private static final int ANIM_SPECIAL_1 = 11476;
	private static final int ANIM_SPECIAL_2 = 11488;
	private static final int ANIM_SPECIAL_3 = 11481;
	
	private static final Pattern ARAXXOR_KILL_TIME_PATTERN = Pattern.compile(
		"Fight duration: <col=[0-9a-f]{6}>(?<time>[0-9:]+(?:\\.[0-9]+)?)</col>\\."
	);

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private AraxxorOverlay overlay;

	@Inject
	private AraxxorConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private AraxxorWorldOverlay worldOverlay;

	@Inject
	private AraxxorStatsOverlay statsOverlay;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ClientThread clientThread;

	private AraxxorConfigPanel configPanel;
	private NavigationButton navButton;

	@Getter
	private long fightStartTime = -1;

	@Getter
	private long fightEndTime = -1;
	
	private long deathTime = -1;

	@Getter
	private boolean isFightActive = false;

	@Getter
	private int killCount = 0;

	@Getter
	private NPC araxxorNpc = null;
	
	private boolean cachedInAraxxorArea = false;
	private long lastAreaCheckTime = -1;
	private static final long AREA_CHECK_CACHE_MS = 2000;
	
	@Getter
	private boolean araxxorReachedZeroHp = false;

	@Getter
	private AraxxorPhase currentPhase = AraxxorPhase.NORMAL;
	
	@Getter
	private WorldPoint firstEggPosition = null;
	
	@Getter
	private AraxxorEggType firstEggType = null;

	private final AraxxorEggType[] eggHistory = new AraxxorEggType[3];
	private int eggHistoryCount = 0;
	
	private final java.util.Map<AraxxorEggType, Integer> eggHistoryIndex = new HashMap<>();
	
	private java.util.List<AraxxorEggType> cachedEggHistoryList = null;
	private int cachedEggHistoryListVersion = -1;
	
	@Getter
	private long lastMinionSpawnTime = -1;
	
	private static class EggTiming {
		int hatchTick = -1;
		int despawnTick = -1;
		AraxxorEggType type;
		WorldPoint position;
		
		EggTiming(AraxxorEggType type, WorldPoint position) {
			this.type = type;
			this.position = position;
		}
		
		int getLastEventTick() {
			return Math.max(hatchTick, despawnTick);
		}
		
		boolean hadEvent() {
			return hatchTick > 0 || despawnTick > 0;
		}
	}
	
	private final EggTiming[] eggTimings = new EggTiming[9];
	private int eggTimingsCount = 0;

	@Getter
	private Map<AraxxorEggType, Integer> activeMinions = new java.util.concurrent.ConcurrentHashMap<>();
	
	@Getter
	private long enrageStartTime = -1;
	
	@Getter
	private int currentFightHits = 0;
	
	@Getter
	private int currentFightDamageDealt = 0;
	
	@Getter
	private int currentFightDamageTaken = 0;
	
	@Getter
	private int lastFightHits = 0;
	@Getter
	private int lastFightDamageDealt = 0;
	@Getter
	private int lastFightDamageTaken = 0;
	
	@Getter
	private long lastFightNormalTime = -1;
	@Getter
	private long lastFightEnrageTime = -1;
	
	private long bestKillTime = -1;
	private long bestTimeToEnrage = -1;
	private long bestTimeInEnrage = -1;

	private long bestWhiteStartTime = -1;
	private long bestRedStartTime = -1;
	private long bestGreenStartTime = -1;
	private AraxxorEggType currentRotationStart = null;
	private int bestHitCount = -1;
	private int bestDamageTaken = -1;
	
	private int bestWhiteStartHits = -1;
	private int bestRedStartHits = -1;
	private int bestGreenStartHits = -1;
	
	private int bestWhiteStartDamage = -1;
	private int bestRedStartDamage = -1;
	private int bestGreenStartDamage = -1;
	
	private final long[] recentKillTimes = new long[5];
	private int recentKillTimesIndex = 0;
	private int recentKillTimesCount = 0;
	
	private long lastKillTimestamp = -1;
	private double cachedKillsPerHour = 0.0;
	private long cachedKillsPerHourTimestamp = -1;
	
	private long cachedTimeInEnrage = -1;
	private long cachedTimeInEnrageCalculationTime = -1;

	@Getter
	private int currentGameTick = 0;
	
	private static final int MAX_SESSION_KILLS = 1024;
	private static final Duration MAX_AGE = Duration.ofDays(365L);
	
	@Getter
	private List<AraxxorKillRecord> sessionKills = new ArrayList<>();
	@Getter
	private List<AraxxorKillRecord> tripKills = new ArrayList<>();
	
	private static final int MAX_TRIP_KILLS = 1024;
	
	private long cachedSessionTotalValue = 0;
	
	private static final long SESSION_TIMEOUT_MS = 45 * 60 * 1000L;
	
	public List<List<AraxxorKillRecord>> groupKillsIntoSessions(List<AraxxorKillRecord> allKills)
	{
		if (allKills.isEmpty())
		{
			return new ArrayList<>();
		}
		
		List<AraxxorKillRecord> sorted = new ArrayList<>(allKills);
		sorted.sort(Comparator.comparingLong(AraxxorKillRecord::getTimestamp));
		
		List<List<AraxxorKillRecord>> sessions = new ArrayList<>();
		List<AraxxorKillRecord> currentSession = new ArrayList<>();
		
		for (int i = 0; i < sorted.size(); i++)
		{
			AraxxorKillRecord kill = sorted.get(i);
			
			if (currentSession.isEmpty())
			{
				currentSession.add(kill);
			}
			else
			{
				AraxxorKillRecord lastKill = currentSession.get(currentSession.size() - 1);
				long gap = kill.getTimestamp() - lastKill.getTimestamp();
				
				if (gap > SESSION_TIMEOUT_MS)
				{
					sessions.add(currentSession);
					currentSession = new ArrayList<>();
				}
				currentSession.add(kill);
			}
		}
		
		if (!currentSession.isEmpty())
		{
			sessions.add(currentSession);
		}
		
		return sessions;
	}
	
	public SessionSummary calculateSessionSummary(List<AraxxorKillRecord> sessionKills)
	{
		if (sessionKills.isEmpty())
		{
			return null;
		}
		
		long totalGP = 0;
		long totalItems = 0;
		long bestTime = Long.MAX_VALUE;
		AraxxorEggType bestRotation = null;
		long sessionStartTime = Long.MAX_VALUE;
		
		for (AraxxorKillRecord kill : sessionKills)
		{
			totalGP += kill.getLootValue();
			if (kill.getLoot() != null)
			{
				totalItems += kill.getLoot().values().stream().mapToLong(Long::longValue).sum();
			}
			
			if (kill.getKillTime() < bestTime)
			{
				bestTime = kill.getKillTime();
				bestRotation = kill.getRotation();
			}
			
			if (kill.getTimestamp() < sessionStartTime)
			{
				sessionStartTime = kill.getTimestamp();
			}
		}
		
		return new SessionSummary(sessionStartTime, totalGP, totalItems, bestTime, bestRotation);
	}
	
	@Value
	public static class SessionSummary
	{
		long sessionStartTime;
		long totalGP;
		long totalItems;
		long bestTime;
		AraxxorEggType bestRotation;
	}

	@Provides
	AraxxorConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AraxxorConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		// Add overlays immediately
		if (overlayManager != null)
		{
			overlayManager.add(overlay);
			overlayManager.add(worldOverlay);
			overlayManager.add(statsOverlay);
		}
		
		resetFight();
		
		// Create config panel with minimal initialization
		configPanel = new AraxxorConfigPanel(configManager, config, this, itemManager, clientThread, client);

		// Create nav button immediately with placeholder
		BufferedImage placeholderIcon = createPlaceholderIcon();
		navButton = NavigationButton.builder()
			.tooltip("Araxxor Tracker")
			.icon(placeholderIcon)
			.priority(8)
			.panel(configPanel)
			.build();

		clientToolbar.addNavigation(navButton);
		loadIconAsync();
		
		// Load heavy data in background thread to avoid blocking client
		new Thread(() -> {
			try
			{
				loadStats();
				loadKillsFromConfig();
				// Update UI on Swing EDT after data is loaded
				if (configPanel != null)
				{
					javax.swing.SwingUtilities.invokeLater(() -> {
						configPanel.refreshStats();
					});
				}
			}
			catch (Exception e)
			{
				// Silently handle loading errors
			}
		}, "AraxxorDataLoader").start();
	}

	@Override
	protected void shutDown() throws Exception
	{
		if (overlayManager != null)
		{
			overlayManager.remove(overlay);
			overlayManager.remove(worldOverlay);
			overlayManager.remove(statsOverlay);
		}
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}
		resetFight();
		saveStats();
		
		if (configPanel != null)
		{
			configPanel.cleanup();
		}
		
		configPanel = null;
		navButton = null;
	}

	@Subscribe
	public void onGameTick(GameTick event)
		{
			if (!isFightActive)
			{
				return;
			}

			currentGameTick++;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN || 
			event.getGameState() == GameState.HOPPING)
		{
			resetFight();
		}
	}
	
	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
		{
			if (!isFightActive)
			{
				return;
			}
			
			if (araxxorReachedZeroHp)
			{
				return;
			}

			Actor actor = event.getActor();

			if (actor instanceof NPC && isAraxxor((NPC) actor))
			{
				handleAraxxorAnimation((NPC) actor);
		}
	}
	
	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
		{
			if (!isFightActive)
			{
				return;
			}
			
			if (event.getActor() instanceof NPC && isAraxxor((NPC) event.getActor()))
			{
				Hitsplat hitsplat = event.getHitsplat();
				if (hitsplat.isMine() && hitsplat.getAmount() > 0)
				{
					currentFightHits++;
					currentFightDamageDealt += hitsplat.getAmount();
				}
			}

			if (event.getActor() == client.getLocalPlayer())
			{
				int damage = event.getHitsplat().getAmount();
				if (damage > 0)
				{
					currentFightDamageTaken += damage;
			}
		}
	}
	
	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (!isFightActive)
		{
			return;
		}
		
		if (event.getActor() instanceof NPC && isAraxxor((NPC) event.getActor()))
		{
			araxxorReachedZeroHp = true;
			deathTime = System.currentTimeMillis();
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
		{
			NPC npc = event.getNpc();
			if (npc == null)
			{
				return;
			}
			
			int npcId = npc.getId();

			if (npcId == ARAXXOR_NPC_ID)
			{
				araxxorNpc = npc;
				startFight();
				return;
			}

			if (!isFightActive)
			{
				return;
			}

			if (AraxxorEggType.isEgg(npcId))
			{
				WorldPoint position = npc.getWorldLocation();
				if (position != null)
				{
					handleEggSpawned(npcId, position);
				}
			}
			else if (AraxxorEggType.isMinion(npcId))
			{
				handleMinionSpawned(npcId);
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
		{
			NPC npc = event.getNpc();
			if (npc == null)
			{
				return;
			}
			
			int npcId = npc.getId();

			if (npcId == ARAXXOR_NPC_ID || npcId == ARAXXOR_DEAD_ID)
			{
				stopFight();
				araxxorNpc = null;
				cachedInAraxxorArea = false;
				lastAreaCheckTime = -1;
				return;
			}

			if (!isFightActive)
			{
				return;
			}

			if (AraxxorEggType.isEgg(npcId) && !araxxorReachedZeroHp)
			{
				if (currentGameTick > EARLY_DESPAWN_THRESHOLD)
				{
					handleEggDespawned(npcId);
				}
			}

			if (AraxxorEggType.isMinion(npcId))
			{
				handleMinionDespawned(npcId);
		}
	}
	
	@Subscribe
	public void onChatMessage(ChatMessage event)
		{
			if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
			{
				return;
			}
			
			if (!araxxorReachedZeroHp || fightEndTime != -1)
			{
				return;
			}
			
			String message = event.getMessage();
			java.util.regex.Matcher matcher = ARAXXOR_KILL_TIME_PATTERN.matcher(message);
			
			if (matcher.find())
			{
				String timeString = matcher.group("time");
				long killTimeMs = parseTimeStringToMs(timeString);

				if (killTimeMs > 0)
				{
					fightEndTime = fightStartTime + killTimeMs;
					updateStats();
			}
		}
	}
	
	private long parseTimeStringToMs(String timeString)
	{
		if (timeString == null || timeString.isEmpty()) {
			return -1;
		}
		
		if (timeString.length() > 20) {
			return -1;
		}
		
		try
		{
			String[] parts = timeString.split(":", 3);
			if (parts.length == 2)
			{
				int minutes = Integer.parseInt(parts[0]);
				double seconds = Double.parseDouble(parts[1]);
				if (minutes < 0 || minutes > 99 || seconds < 0 || seconds >= 60) {
					return -1;
				}
				double totalSeconds = minutes * 60.0 + seconds;
				if (totalSeconds > (Long.MAX_VALUE / 1000.0)) {
					return -1;
				}
				return (long) (totalSeconds * 1000);
			}
			else if (parts.length == 3)
			{
				int hours = Integer.parseInt(parts[0]);
				int minutes = Integer.parseInt(parts[1]);
				double seconds = Double.parseDouble(parts[2]);
				if (hours < 0 || hours > 23 || minutes < 0 || minutes >= 60 || seconds < 0 || seconds >= 60) {
					return -1;
				}
				double totalSeconds = hours * 3600.0 + minutes * 60.0 + seconds;
				if (totalSeconds > (Long.MAX_VALUE / 1000.0)) {
					return -1;
				}
				return (long) (totalSeconds * 1000);
			}
		}
		catch (NumberFormatException e)
		{
		}
		
		return -1;
	}

	private void startFight()
	{
		fightStartTime = System.currentTimeMillis();
		fightEndTime = -1;
		deathTime = -1;
		isFightActive = true;
		araxxorReachedZeroHp = false;
		currentPhase = AraxxorPhase.NORMAL;
		lastAraxxorAnimation = -1;
		enrageStartTime = -1;
		currentFightHits = 0;
		currentFightDamageDealt = 0;
		currentFightDamageTaken = 0;
		currentGameTick = 0;
		lastMinionSpawnTime = -1;
		cachedInAraxxorArea = false;
		lastAreaCheckTime = -1;
		
		for (int i = 0; i < eggTimingsCount; i++)
		{
			eggTimings[i] = null;
		}
		eggTimingsCount = 0;
		
		eggHistoryCount = 0;
		eggHistoryIndex.clear();
		cachedEggHistoryList = null;
		cachedEggHistoryListVersion = -1;
		
		firstEggType = null;
		currentRotationStart = null;
		firstEggPosition = null;
		
		activeMinions.clear();
	}
	
	public void resetTrip()
	{
		tripKills.clear();
	}

	private void stopFight()
	{
		if (!isFightActive)
		{
			return;
		}
		
		if (fightEndTime == -1)
		{
			fightEndTime = System.currentTimeMillis();
		}
		
		isFightActive = false;
		
		if (araxxorReachedZeroHp)
		{
			lastFightHits = currentFightHits;
			lastFightDamageDealt = currentFightDamageDealt;
			lastFightDamageTaken = currentFightDamageTaken;
			if (enrageStartTime > 0)
			{
				lastFightNormalTime = enrageStartTime - fightStartTime;
				lastFightEnrageTime = (fightEndTime != -1 ? fightEndTime : System.currentTimeMillis()) - enrageStartTime;
			}
			else
			{
				long totalTime = (fightEndTime != -1 ? fightEndTime : System.currentTimeMillis()) - fightStartTime;
				lastFightNormalTime = totalTime;
				lastFightEnrageTime = -1;
			}
			
			saveStats();
		}
		else
		{
			currentFightHits = 0;
			currentFightDamageDealt = 0;
			currentFightDamageTaken = 0;
		}
	}

	private void resetFight()
	{
		fightStartTime = -1;
		fightEndTime = -1;
		isFightActive = false;
		araxxorNpc = null;
		araxxorReachedZeroHp = false;
		currentPhase = AraxxorPhase.NORMAL;
		lastAraxxorAnimation = -1;
		enrageStartTime = -1;
		currentFightHits = 0;
		currentFightDamageDealt = 0;
		currentFightDamageTaken = 0;
		lastMinionSpawnTime = -1;
		cachedTimeInEnrage = -1;
		cachedTimeInEnrageCalculationTime = -1;
		cachedInAraxxorArea = false;
		lastAreaCheckTime = -1;

		eggHistoryCount = 0;
		eggHistoryIndex.clear();
		cachedEggHistoryList = null;
		cachedEggHistoryListVersion = -1;
		firstEggType = null;
		currentRotationStart = null;
		firstEggPosition = null;

		for (int i = 0; i < eggTimingsCount; i++)
		{
			eggTimings[i] = null;
		}
		eggTimingsCount = 0;

		activeMinions.clear();
	}

	public long getElapsedTime()
	{
		if (fightStartTime == -1)
		{
			return -1;
		}
		
		if (fightEndTime != -1)
		{
			return fightEndTime - fightStartTime;
		}
		
		if (deathTime != -1)
		{
			return (deathTime - fightStartTime) + GAME_TIMER_OFFSET_MS;
		}
		
		return (System.currentTimeMillis() - fightStartTime) + GAME_TIMER_OFFSET_MS;
	}

	public double getAverageHit()
	{
		if (currentFightHits == 0)
		{
			return 0.0;
		}
		return (double) currentFightDamageDealt / currentFightHits;
	}

	public double getDPS()
	{
		long elapsedTime = getElapsedTime();
		if (elapsedTime <= 0 || currentFightDamageDealt == 0)
		{
			return 0.0;
		}
		double elapsedSeconds = elapsedTime / 1000.0;
		return currentFightDamageDealt / elapsedSeconds;
	}
	
	private int lastAraxxorAnimation = -1;
	private int lastAraxxorAnimationTick = -1;
	
	private void handleAraxxorAnimation(NPC npc)
	{
		int animationId = npc.getAnimation();
		
		if (animationId == -1 || animationId == 0 || 
			(animationId == lastAraxxorAnimation && currentGameTick == lastAraxxorAnimationTick))
		{
			return;
		}

		lastAraxxorAnimation = animationId;
		lastAraxxorAnimationTick = currentGameTick;

		boolean isSpecialAttack = (animationId == ANIM_SPECIAL_1 || 
									animationId == ANIM_SPECIAL_2 || 
									animationId == ANIM_SPECIAL_3);
		
		if (isSpecialAttack)
		{
			if (currentPhase != AraxxorPhase.SPECIAL)
			{
				currentPhase = AraxxorPhase.SPECIAL;
			}
		}
		else if (currentPhase == AraxxorPhase.SPECIAL)
		{
			boolean isLowHp = araxxorNpc != null && araxxorNpc.getHealthScale() > 0 
				&& araxxorNpc.getHealthRatio() <= (araxxorNpc.getHealthScale() * ENRAGE_HP_THRESHOLD);
			
			if (isLowHp && enrageStartTime == -1 && fightStartTime != -1)
			{
				enrageStartTime = System.currentTimeMillis();
			}
			
			currentPhase = isLowHp ? AraxxorPhase.ENRAGED : AraxxorPhase.NORMAL;
		}
	}

	private boolean isAraxxor(NPC npc)
	{
		int id = npc.getId();
		return id == ARAXXOR_NPC_ID || id == ARAXXOR_DEAD_ID;
	}
	
	private static double calculateSouthEastScore(WorldPoint position)
	{
		if (position == null)
		{
			return Double.NEGATIVE_INFINITY;
		}
		return position.getX() - position.getY();
	}

	private void handleEggSpawned(int eggId, WorldPoint position)
	{
		AraxxorEggType eggType = AraxxorEggType.fromEggId(eggId);
		
		if (eggType == null || position == null)
		{
			return;
		}
		
		EggTiming timing = new EggTiming(eggType, position);
		if (eggTimingsCount < 9) {
			eggTimings[eggTimingsCount] = timing;
			eggTimingsCount++;
		}
		
		int unhatchedCount = 0;
		EggTiming firstEgg = null;
		double maxSouthEastScore = Double.NEGATIVE_INFINITY;
		
		for (int i = 0; i < eggTimingsCount; i++)
		{
			EggTiming t = eggTimings[i];
			if (t != null && !t.hadEvent())
			{
				unhatchedCount++;
				
				if (eggHistoryCount == 0 && t.position != null && t.type != null)
				{
					double southEastScore = calculateSouthEastScore(t.position);
					if (southEastScore > maxSouthEastScore)
					{
						maxSouthEastScore = southEastScore;
						firstEgg = t;
					}
				}
			}
		}
		
		if (unhatchedCount == 6 && eggHistoryCount == 0)
		{
			if (firstEgg != null)
			{
				firstEggType = firstEgg.type;
				currentRotationStart = firstEgg.type;
				firstEggPosition = firstEgg.position;

				eggHistory[0] = firstEgg.type;
				eggHistory[1] = firstEgg.type.getNext();
				eggHistory[2] = firstEgg.type.getNext().getNext();
				eggHistoryCount = 3;
				eggHistoryIndex.clear();
				eggHistoryIndex.put(eggHistory[0], 0);
				eggHistoryIndex.put(eggHistory[1], 1);
				eggHistoryIndex.put(eggHistory[2], 2);
				cachedEggHistoryList = null;
				cachedEggHistoryListVersion = eggHistoryCount;
			}
		}
	}

	private void handleEggDespawned(int eggId)
	{
		AraxxorEggType eggType = AraxxorEggType.fromEggId(eggId);
		
		if (eggType == null)
		{
			return;
		}
		
		EggTiming despawnedEgg = null;
		double maxSouthEastScore = Double.NEGATIVE_INFINITY;
		
		for (int i = 0; i < eggTimingsCount; i++)
		{
			EggTiming timing = eggTimings[i];
			if (timing != null && timing.type == eggType && !timing.hadEvent() && timing.position != null)
			{
				double southEastScore = calculateSouthEastScore(timing.position);
				if (southEastScore > maxSouthEastScore)
				{
					maxSouthEastScore = southEastScore;
					despawnedEgg = timing;
				}
			}
		}
		
		if (despawnedEgg != null && despawnedEgg.position != null)
		{
			despawnedEgg.despawnTick = currentGameTick;
		}
		else
		{
			createVirtualEggEvent(eggType, true);
		}
	}
	
	private void createVirtualEggEvent(AraxxorEggType eggType, boolean isDespawn)
	{
		if (eggTimingsCount >= 9)
		{
			return;
		}
		
		EggTiming virtual = new EggTiming(eggType, new WorldPoint(0, 0, 0));
		if (isDespawn)
		{
			virtual.despawnTick = currentGameTick;
		}
		else
		{
			virtual.hatchTick = currentGameTick;
		}
		
		eggTimings[eggTimingsCount] = virtual;
		eggTimingsCount++;
	}

	private void handleMinionSpawned(int minionId)
	{
		AraxxorEggType minionType = AraxxorEggType.fromMinionId(minionId);
		
		if (minionType != null)
		{
			activeMinions.merge(minionType, 1, Integer::sum);
			lastMinionSpawnTime = System.currentTimeMillis();
			
			EggTiming matchingEgg = null;
			double maxSouthEastScore = Double.NEGATIVE_INFINITY;
			int hatchedCount = 0;
			
			for (int i = 0; i < eggTimingsCount; i++) {
				EggTiming timing = eggTimings[i];
				if (timing == null) continue;
				
				if (timing.hatchTick > 0)
				{
					hatchedCount++;
				}
				
				if (!timing.hadEvent() && timing.type == minionType && timing.position != null) {
					double southEastScore = calculateSouthEastScore(timing.position);
					if (southEastScore > maxSouthEastScore) {
						maxSouthEastScore = southEastScore;
						matchingEgg = timing;
					}
				}
			}
			
			if (matchingEgg != null && matchingEgg.position != null) {
				matchingEgg.hatchTick = currentGameTick;
				hatchedCount++;
			}
			else
			{
				createVirtualEggEvent(minionType, false);
				hatchedCount++;
			}
			
		boolean isAfterDeath = araxxorReachedZeroHp;
		
		if (!isAfterDeath)
		{
			int historySize = eggHistoryCount;
			boolean wasFirstMinion = (hatchedCount <= 1);
			
			if (historySize == 0)
			{
				eggHistory[0] = minionType;
				eggHistoryCount = 1;
				eggHistoryIndex.clear();
				eggHistoryIndex.put(minionType, 0);
				cachedEggHistoryList = null;
				cachedEggHistoryListVersion = eggHistoryCount;
				firstEggType = minionType;
				currentRotationStart = minionType;
			}
		}
		}
	}

	private void handleMinionDespawned(int minionId)
	{
		AraxxorEggType minionType = AraxxorEggType.fromMinionId(minionId);
		
		if (minionType != null)
		{
			activeMinions.computeIfPresent(minionType, (k, v) -> v > 1 ? v - 1 : null);
		}
	}

	public boolean isInAraxxorArea()
	{
		Player localPlayer = client != null ? client.getLocalPlayer() : null;
		if (localPlayer == null)
		{
			cachedInAraxxorArea = false;
			return false;
		}
		
		long currentTime = System.currentTimeMillis();
		WorldPoint playerLocation = localPlayer.getWorldLocation();
		
		if (araxxorNpc != null && lastAreaCheckTime >= 0 && 
			(currentTime - lastAreaCheckTime) < AREA_CHECK_CACHE_MS)
		{
			WorldPoint npcLocation = araxxorNpc.getWorldLocation();
			if (npcLocation != null)
			{
				int distance = playerLocation.distanceTo2D(npcLocation);
				if (distance <= MAX_AREA_DISTANCE)
				{
					return cachedInAraxxorArea;
				}
			}
			araxxorNpc = null;
		}
		
		if (araxxorNpc != null)
		{
			WorldPoint npcLocation = araxxorNpc.getWorldLocation();
			if (npcLocation != null)
			{
				int distance = playerLocation.distanceTo2D(npcLocation);
				if (distance <= MAX_AREA_DISTANCE)
				{
					cachedInAraxxorArea = true;
					lastAreaCheckTime = currentTime;
					return true;
				}
			}
			araxxorNpc = null;
			cachedInAraxxorArea = false;
		}
		
		if (client != null)
		{
			var worldView = client.getTopLevelWorldView();
			if (worldView != null)
			{
				int checked = 0;
				for (NPC npc : worldView.npcs())
				{
					if (checked++ > MAX_NPC_ITERATION_CHECK)
					{
						break;
					}
					if (npc != null && isAraxxor(npc))
					{
						WorldPoint npcLocation = npc.getWorldLocation();
						if (npcLocation != null)
						{
							int distance = playerLocation.distanceTo2D(npcLocation);
							if (distance <= MAX_AREA_DISTANCE)
							{
								araxxorNpc = npc;
								cachedInAraxxorArea = true;
								lastAreaCheckTime = currentTime;
								return true;
							}
						}
					}
				}
			}
		}
		
		cachedInAraxxorArea = false;
		lastAreaCheckTime = currentTime;
		return false;
	}
	
	/**
	 * Reset phase and fight state if player is not actually in the Araxxor area
	 * This fixes the issue where overlay stays visible after teleporting out
	 */
	public void resetPhaseIfNotInArea()
	{
		boolean isInArea = isInAraxxorArea();
		
		if (!isInArea && (isFightActive || fightStartTime != -1))
		{
			resetFight();
			return;
		}
		
		if (!isInArea && !tripKills.isEmpty())
		{
			resetTrip();
		}
		
		if (!isInArea && currentPhase == AraxxorPhase.ENRAGED)
		{
			currentPhase = AraxxorPhase.NORMAL;
			enrageStartTime = -1;
		}
	}
	
	/**
	 * Update rotation-specific statistics for a given rotation type
	 */
	private void updateRotationStats(AraxxorEggType rotation, long killTime, int hits, int damageTaken)
	{
		long bestTime = getRotationBestTime(rotation);
		
		// Update best time (lower is better) - also update hits/damage from this PB kill
		if (bestTime == -1 || killTime < bestTime)
		{
			switch (rotation)
			{
				case WHITE: 
					bestWhiteStartTime = killTime;
					bestWhiteStartHits = hits;
					bestWhiteStartDamage = damageTaken;
					break;
				case RED: 
					bestRedStartTime = killTime;
					bestRedStartHits = hits;
					bestRedStartDamage = damageTaken;
					break;
				case GREEN: 
					bestGreenStartTime = killTime;
					bestGreenStartHits = hits;
					bestGreenStartDamage = damageTaken;
					break;
			}
		}
	}
	
	/**
	 * Load saved statistics from config
	 */
	private void loadStats()
	{
		if (configManager == null)
		{
			return;
		}

		Long bestKillTimeObj = configManager.getConfiguration("arraxxor", "bestKillTime", Long.class);
		bestKillTime = (bestKillTimeObj != null) ? bestKillTimeObj : -1L;
		
		Long bestTimeToEnrageObj = configManager.getConfiguration("arraxxor", "bestTimeToEnrage", Long.class);
		bestTimeToEnrage = (bestTimeToEnrageObj != null) ? bestTimeToEnrageObj : -1L;
		
		Long bestTimeInEnrageObj = configManager.getConfiguration("arraxxor", "bestTimeInEnrage", Long.class);
		bestTimeInEnrage = (bestTimeInEnrageObj != null) ? bestTimeInEnrageObj : -1L;

		Long bestWhiteStartTimeObj = configManager.getConfiguration("arraxxor", "bestWhiteStartTime", Long.class);
		bestWhiteStartTime = (bestWhiteStartTimeObj != null) ? bestWhiteStartTimeObj : -1L;

		Long bestRedStartTimeObj = configManager.getConfiguration("arraxxor", "bestRedStartTime", Long.class);
		bestRedStartTime = (bestRedStartTimeObj != null) ? bestRedStartTimeObj : -1L;

		Long bestGreenStartTimeObj = configManager.getConfiguration("arraxxor", "bestGreenStartTime", Long.class);
		bestGreenStartTime = (bestGreenStartTimeObj != null) ? bestGreenStartTimeObj : -1L;

		Integer bestHitCountObj = configManager.getConfiguration("arraxxor", "bestHitCount", Integer.class);
		bestHitCount = (bestHitCountObj != null) ? bestHitCountObj : -1;
		
		Integer bestDamageTakenObj = configManager.getConfiguration("arraxxor", "bestDamageTaken", Integer.class);
		bestDamageTaken = (bestDamageTakenObj != null) ? bestDamageTakenObj : -1;
		
		Integer bestWhiteStartHitsObj = configManager.getConfiguration("arraxxor", "bestWhiteStartHits", Integer.class);
		bestWhiteStartHits = (bestWhiteStartHitsObj != null) ? bestWhiteStartHitsObj : -1;
		
		Integer bestRedStartHitsObj = configManager.getConfiguration("arraxxor", "bestRedStartHits", Integer.class);
		bestRedStartHits = (bestRedStartHitsObj != null) ? bestRedStartHitsObj : -1;
		
		Integer bestGreenStartHitsObj = configManager.getConfiguration("arraxxor", "bestGreenStartHits", Integer.class);
		bestGreenStartHits = (bestGreenStartHitsObj != null) ? bestGreenStartHitsObj : -1;
		
		Integer bestWhiteStartDamageObj = configManager.getConfiguration("arraxxor", "bestWhiteStartDamage", Integer.class);
		bestWhiteStartDamage = (bestWhiteStartDamageObj != null) ? bestWhiteStartDamageObj : -1;
		
		Integer bestRedStartDamageObj = configManager.getConfiguration("arraxxor", "bestRedStartDamage", Integer.class);
		bestRedStartDamage = (bestRedStartDamageObj != null) ? bestRedStartDamageObj : -1;
		
		Integer bestGreenStartDamageObj = configManager.getConfiguration("arraxxor", "bestGreenStartDamage", Integer.class);
		bestGreenStartDamage = (bestGreenStartDamageObj != null) ? bestGreenStartDamageObj : -1;
		
		String recentKillsStr = configManager.getConfiguration("arraxxor", "recentKillTimes", String.class);
		recentKillTimesCount = 0;
		recentKillTimesIndex = 0;
		if (recentKillsStr != null && !recentKillsStr.isEmpty())
		{
			String[] parts = recentKillsStr.split(",");
			for (String part : parts)
			{
				try
				{
					long killTime = Long.parseLong(part.trim());
					recentKillTimes[recentKillTimesCount] = killTime;
					recentKillTimesCount++;
					if (recentKillTimesCount >= 5) break;
				}
				catch (NumberFormatException e)
				{
					// Ignore invalid entries
				}
			}
			recentKillTimesIndex = recentKillTimesCount % 5;
		}
		
		Integer killCountObj = configManager.getConfiguration("arraxxor", "killCount", Integer.class);
		killCount = (killCountObj != null) ? killCountObj : 0;
		
		Long lastKillTimestampObj = configManager.getConfiguration("arraxxor", "lastKillTimestamp", Long.class);
		lastKillTimestamp = (lastKillTimestampObj != null) ? lastKillTimestampObj : -1L;
		
		Long lastFightNormalTimeObj = configManager.getConfiguration("arraxxor", "lastFightNormalTime", Long.class);
		lastFightNormalTime = (lastFightNormalTimeObj != null) ? lastFightNormalTimeObj : -1L;
		
		Long lastFightEnrageTimeObj = configManager.getConfiguration("arraxxor", "lastFightEnrageTime", Long.class);
		lastFightEnrageTime = (lastFightEnrageTimeObj != null) ? lastFightEnrageTimeObj : -1L;
		
		Integer lastFightHitsObj = configManager.getConfiguration("arraxxor", "lastFightHits", Integer.class);
		lastFightHits = (lastFightHitsObj != null) ? lastFightHitsObj : 0;
		
		Integer lastFightDamageDealtObj = configManager.getConfiguration("arraxxor", "lastFightDamageDealt", Integer.class);
		lastFightDamageDealt = (lastFightDamageDealtObj != null) ? lastFightDamageDealtObj : 0;
		
		Integer lastFightDamageTakenObj = configManager.getConfiguration("arraxxor", "lastFightDamageTaken", Integer.class);
		lastFightDamageTaken = (lastFightDamageTakenObj != null) ? lastFightDamageTakenObj : 0;
	}
	
	/**
	 * Save statistics to config
	 */
	private void saveStats()
	{
		if (configManager == null)
		{
			return;
		}

		configManager.setConfiguration("arraxxor", "bestKillTime", bestKillTime);
		configManager.setConfiguration("arraxxor", "bestTimeToEnrage", bestTimeToEnrage);
		configManager.setConfiguration("arraxxor", "bestTimeInEnrage", bestTimeInEnrage);
		configManager.setConfiguration("arraxxor", "bestWhiteStartTime", bestWhiteStartTime);
		configManager.setConfiguration("arraxxor", "bestRedStartTime", bestRedStartTime);
		configManager.setConfiguration("arraxxor", "bestGreenStartTime", bestGreenStartTime);
		configManager.setConfiguration("arraxxor", "bestHitCount", bestHitCount);
		configManager.setConfiguration("arraxxor", "bestDamageTaken", bestDamageTaken);
		configManager.setConfiguration("arraxxor", "bestWhiteStartHits", bestWhiteStartHits);
		configManager.setConfiguration("arraxxor", "bestRedStartHits", bestRedStartHits);
		configManager.setConfiguration("arraxxor", "bestGreenStartHits", bestGreenStartHits);
		configManager.setConfiguration("arraxxor", "bestWhiteStartDamage", bestWhiteStartDamage);
		configManager.setConfiguration("arraxxor", "bestRedStartDamage", bestRedStartDamage);
		configManager.setConfiguration("arraxxor", "bestGreenStartDamage", bestGreenStartDamage);
		
		String[] killTimeStrings = new String[recentKillTimesCount];
		for (int i = 0; i < recentKillTimesCount; i++)
		{
			killTimeStrings[i] = String.valueOf(recentKillTimes[i]);
		}
		configManager.setConfiguration("arraxxor", "recentKillTimes", String.join(",", killTimeStrings));
		
		configManager.setConfiguration("arraxxor", "killCount", killCount);
		configManager.setConfiguration("arraxxor", "lastKillTimestamp", lastKillTimestamp);
		configManager.setConfiguration("arraxxor", "lastFightNormalTime", lastFightNormalTime);
		configManager.setConfiguration("arraxxor", "lastFightEnrageTime", lastFightEnrageTime);
		configManager.setConfiguration("arraxxor", "lastFightHits", lastFightHits);
		configManager.setConfiguration("arraxxor", "lastFightDamageDealt", lastFightDamageDealt);
		configManager.setConfiguration("arraxxor", "lastFightDamageTaken", lastFightDamageTaken);
	}
	
	/**
	 * Update statistics after a successful kill
	 */
	private void updateStats()
	{
		long killTime = getElapsedTime();
		if (killTime <= 0)
		{
			return;
		}
		
		if (araxxorReachedZeroHp)
		{
			killCount++;
		}
		
		lastFightHits = currentFightHits;
		lastFightDamageDealt = currentFightDamageDealt;
		lastFightDamageTaken = currentFightDamageTaken;
		
		// Update best time and ALL associated stats from this PB kill
		// (hits, damage, normal time, enrage time all come from the same kill)
		if (bestKillTime == -1 || killTime < bestKillTime)
		{
			bestKillTime = killTime;
			bestHitCount = currentFightHits;
			bestDamageTaken = currentFightDamageTaken;
		
			// Save normal/enrage times from this same PB kill
		if (enrageStartTime > 0)
		{
				bestTimeToEnrage = enrageStartTime - fightStartTime;
				bestTimeInEnrage = fightEndTime - enrageStartTime;
			}
		}

		if (currentRotationStart != null)
		{
			updateRotationStats(currentRotationStart, killTime, currentFightHits, currentFightDamageTaken);
		}
		
		recentKillTimes[recentKillTimesIndex] = killTime;
		recentKillTimesIndex = (recentKillTimesIndex + 1) % 5;
		if (recentKillTimesCount < 5)
		{
			recentKillTimesCount++;
		}
		
		// Update last kill timestamp for kills/hour decay calculation
		lastKillTimestamp = System.currentTimeMillis();
		cachedKillsPerHourTimestamp = -1;
		
		// Save to config
		saveStats();
		
		// Update config panel stats
		if (configPanel != null)
		{
			configPanel.refreshStats();
		}
	}
	
	/**
	 * Reset all statistics
	 */
	public void resetStats()
	{
		bestKillTime = -1;
		bestTimeToEnrage = -1;
		bestTimeInEnrage = -1;
		bestWhiteStartTime = -1;
		bestRedStartTime = -1;
		bestGreenStartTime = -1;
		bestHitCount = -1;
		bestDamageTaken = -1;
		bestWhiteStartHits = -1;
		bestRedStartHits = -1;
		bestGreenStartHits = -1;
		bestWhiteStartDamage = -1;
		bestRedStartDamage = -1;
		bestGreenStartDamage = -1;
		killCount = 0;
		
		for (int i = 0; i < recentKillTimes.length; i++)
		{
			recentKillTimes[i] = 0;
		}
		recentKillTimesCount = 0;
		recentKillTimesIndex = 0;
		
		lastKillTimestamp = -1;
		cachedKillsPerHour = 0.0;
		
		saveStats();
		
		// Update config panel stats
		if (configPanel != null)
		{
			configPanel.refreshStats();
		}
	}
	
	// Getters for statistics

	public long getBestKillTime()
	{
		return bestKillTime;
	}
	
	public long getBestTimeToEnrage()
	{
		return bestTimeToEnrage;
	}
	
	public long getBestTimeInEnrage()
	{
		return bestTimeInEnrage;
	}

	public long getBestWhiteStartTime()
	{
		return bestWhiteStartTime;
	}

	public long getBestRedStartTime()
	{
		return bestRedStartTime;
	}

	public long getBestGreenStartTime()
	{
		return bestGreenStartTime;
	}

	public AraxxorEggType getCurrentRotationStart()
	{
		return currentRotationStart;
	}

	public int getBestHitCount()
	{
		return bestHitCount;
	}
	
	public int getBestDamageTaken()
	{
		return bestDamageTaken;
	}
	
	public int getBestWhiteStartHits()
	{
		return bestWhiteStartHits;
	}
	
	public int getBestRedStartHits()
	{
		return bestRedStartHits;
	}
	
	public int getBestGreenStartHits()
	{
		return bestGreenStartHits;
	}
	
	public int getBestWhiteStartDamage()
	{
		return bestWhiteStartDamage;
	}
	
	public int getBestRedStartDamage()
	{
		return bestRedStartDamage;
	}
	
	public int getBestGreenStartDamage()
	{
		return bestGreenStartDamage;
	}
	
	/**
	 * Get best time for a specific rotation type
	 */
	public long getRotationBestTime(AraxxorEggType rotation)
	{
		if (rotation == null)
		{
			return -1;
		}
		switch (rotation)
		{
			case WHITE:
				return bestWhiteStartTime;
			case RED:
				return bestRedStartTime;
			case GREEN:
				return bestGreenStartTime;
			default:
				return -1;
		}
	}
	
	/**
	 * Get best hits for a specific rotation type
	 */
	public int getRotationBestHits(AraxxorEggType rotation)
	{
		if (rotation == null)
		{
			return -1;
		}
		switch (rotation)
		{
			case WHITE:
				return bestWhiteStartHits;
			case RED:
				return bestRedStartHits;
			case GREEN:
				return bestGreenStartHits;
			default:
				return -1;
		}
	}
	
	/**
	 * Get best damage taken for a specific rotation type
	 */
	public int getRotationBestDamage(AraxxorEggType rotation)
	{
		if (rotation == null)
		{
			return -1;
		}
		switch (rotation)
		{
			case WHITE:
				return bestWhiteStartDamage;
			case RED:
				return bestRedStartDamage;
			case GREEN:
				return bestGreenStartDamage;
			default:
				return -1;
		}
	}
	
	public long getCurrentTimeToEnrage()
	{
		if (enrageStartTime > 0)
		{
			// Add offset to match game timer (measured from fight start)
			return (enrageStartTime - fightStartTime) + GAME_TIMER_OFFSET_MS;
		}
		return -1;
	}
	
	/**
	 * Get average kill time from last 5 kills
	 */
	public long getAverageKillTime()
	{
		if (recentKillTimesCount == 0)
		{
			return -1;
		}
		
		long sum = 0;
		for (int i = 0; i < recentKillTimesCount; i++)
		{
			sum += recentKillTimes[i];
		}
		return sum / recentKillTimesCount;
	}
	
	/**
	 * Get kills per hour based on average of last 5 kills with graceful decay
	 * The rate gradually decreases when not actively fighting
	 * Cached for performance - recalculates only when needed
	 */
	public double getKillsPerHour()
	{
		long currentTime = System.currentTimeMillis();
		
		// Use cached value if it was calculated recently (within 1 second)
		// This avoids expensive Math.exp() calculation every render frame
		if (cachedKillsPerHourTimestamp > 0 && (currentTime - cachedKillsPerHourTimestamp) < 1000)
		{
			return cachedKillsPerHour;
		}
		
		long avgKillTime = getAverageKillTime();
		if (avgKillTime <= 0)
		{
			cachedKillsPerHour = 0.0;
			cachedKillsPerHourTimestamp = currentTime;
			return 0.0;
		}
		
		// Base kills per hour calculation
		double baseKillsPerHour = 3600000.0 / avgKillTime;
		
		// If no last kill timestamp, return base rate
		if (lastKillTimestamp <= 0)
		{
			cachedKillsPerHour = baseKillsPerHour;
			cachedKillsPerHourTimestamp = currentTime;
			return baseKillsPerHour;
		}
		
		// Calculate time since last kill
		long timeSinceLastKill = currentTime - lastKillTimestamp;
		
		// Apply exponential decay after 5 minutes of inactivity
		// Decay starts slow, accelerates over time
		// After 60 minutes: ~37% of original rate
		double DECAY_START_MS = 5 * 60 * 1000; // 5 minutes
		double DECAY_RATE = 1.0 / (60.0 * 60 * 1000); // Decay constant (per hour)
		
		if (timeSinceLastKill > DECAY_START_MS)
		{
			double decayTime = timeSinceLastKill - DECAY_START_MS;
			double decayFactor = Math.exp(-DECAY_RATE * decayTime);
			cachedKillsPerHour = baseKillsPerHour * decayFactor;
		}
		else
		{
			// No decay yet, return base rate
			cachedKillsPerHour = baseKillsPerHour;
		}
		
		cachedKillsPerHourTimestamp = currentTime;
		return cachedKillsPerHour;
	}
	
	public int getEggHistoryCount()
	{
		return eggHistoryCount;
	}
	
	public java.util.List<AraxxorEggType> getEggHistory()
	{
		if (cachedEggHistoryList != null && cachedEggHistoryListVersion == eggHistoryCount)
		{
			return cachedEggHistoryList;
		}
		
		java.util.List<AraxxorEggType> list = new java.util.ArrayList<>(eggHistoryCount);
		for (int i = 0; i < eggHistoryCount; i++)
		{
			list.add(eggHistory[i]);
		}
		cachedEggHistoryList = list;
		cachedEggHistoryListVersion = eggHistoryCount;
		return list;
	}
	
	public long getCurrentTimeInEnrage()
	{
		if (enrageStartTime <= 0)
		{
			return -1;
		}
		
		if (fightEndTime != -1 || deathTime != -1)
		{
			if (cachedTimeInEnrage >= 0 && cachedTimeInEnrageCalculationTime == enrageStartTime)
			{
				return cachedTimeInEnrage;
			}
			
			long currentTime = (fightEndTime != -1) ? fightEndTime : deathTime;
			cachedTimeInEnrage = (currentTime - enrageStartTime) + 1000;
			cachedTimeInEnrageCalculationTime = enrageStartTime;
			return cachedTimeInEnrage;
		}
		
		long currentTime = System.currentTimeMillis();
		return (currentTime - enrageStartTime) + 1000;
	}

	/**
	 * Handle loot received from Araxxor kill
	 */
	@Subscribe
	public void onServerNpcLoot(final ServerNpcLoot event)
		{
			final net.runelite.api.NPCComposition npc = event.getComposition();
			int npcId = npc.getId();
			
			if (npcId != ARAXXOR_NPC_ID && npcId != ARAXXOR_DEAD_ID)
			{
				return;
			}
			
			if (!araxxorReachedZeroHp)
			{
				return;
			}
			
			AraxxorKillRecord kill = new AraxxorKillRecord();
			kill.setTimestamp(System.currentTimeMillis());
			kill.setKillTime(getElapsedTime());
			kill.setRotation(currentRotationStart);
			kill.setHits(currentFightHits);
			kill.setDamageDealt(currentFightDamageDealt);
			kill.setDamageTaken(currentFightDamageTaken);
			
			long totalValue = 0;
			for (ItemStack item : event.getItems())
			{
				int itemId = item.getId();
				kill.getLoot().merge(itemId, (long)item.getQuantity(), Long::sum);
				
				int price;
				if (configPanel != null)
				{
					configPanel.cacheItemPrice(itemId);
					price = configPanel.getCachedItemPrice(itemId);
					if (price <= 0)
					{
						price = itemManager.getItemPrice(itemId);
					}
				}
				else
				{
					price = itemManager.getItemPrice(itemId);
				}
				
				totalValue += (long)price * item.getQuantity();
				
				if (configPanel != null)
				{
					configPanel.cacheItemName(itemId);
				}
			}
			kill.setLootValue(totalValue);
			
			long currentTime = System.currentTimeMillis();
			boolean isNewSession = (lastKillTimestamp != -1 && (currentTime - lastKillTimestamp) > SESSION_TIMEOUT_MS);
			
			if (isNewSession)
			{
				sessionKills.clear();
				cachedSessionTotalValue = 0;
				
				if (configPanel != null)
				{
					configPanel.clearItemCaches();
				}
			}
			
			lastKillTimestamp = currentTime;
			
			if (tripKills.size() >= MAX_TRIP_KILLS)
			{
				tripKills.remove(0);
			}
			tripKills.add(kill);
			
			if (sessionKills.size() >= MAX_SESSION_KILLS)
			{
				AraxxorKillRecord oldest = sessionKills.remove(0);
				cachedSessionTotalValue -= oldest.getLootValue();
				if (configManager != null)
				{
					configManager.unsetConfiguration("arraxxor", "kill_" + oldest.getTimestamp());
				}
			}
			sessionKills.add(kill);
			
			cachedSessionTotalValue += totalValue;
			
			saveKillToConfig(kill);
			
			if (configPanel != null)
			{
				configPanel.refreshStats();
		}
	}
	
	/**
	 * Save kill record to config (with storage limits)
	 */
	private void saveKillToConfig(AraxxorKillRecord kill)
	{
		if (configManager == null)
		{
			return;
		}
		
		String key = "kill_" + kill.getTimestamp();
		
		StringBuilder lootItemsStr = new StringBuilder();
		if (kill.getLoot() != null && !kill.getLoot().isEmpty())
		{
			boolean first = true;
			for (Map.Entry<Integer, Long> entry : kill.getLoot().entrySet())
			{
				if (!first)
				{
					lootItemsStr.append(",");
				}
				lootItemsStr.append(entry.getKey()).append(":").append(entry.getValue());
				first = false;
			}
		}
		
		String value = String.join("|",
			String.valueOf(kill.getTimestamp()),
			String.valueOf(kill.getKillTime()),
			kill.getRotation() != null ? kill.getRotation().name() : "UNKNOWN",
			String.valueOf(kill.getLootValue()),
			String.valueOf(kill.getHits()),
			String.valueOf(kill.getDamageDealt()),
			String.valueOf(kill.getDamageTaken()),
			lootItemsStr.toString()
		);
		try
		{
			configManager.setConfiguration("arraxxor", key, value);
		}
		catch (Exception e)
		{
		}
	}
	
	/**
	 * Load kills from config on startup
	 */
	private void loadKillsFromConfig()
	{
		if (configManager == null)
		{
			return;
		}
		
		sessionKills.clear();
		cachedSessionTotalValue = 0;
		
		Instant cutoff = Instant.now().minus(MAX_AGE);
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
				if (value.length() > 10000)
				{
					continue;
				}
				
				String[] valueParts = value.split("\\|", 8);
				if (valueParts.length >= 7)
				{
					long timestamp = Long.parseLong(valueParts[0]);
					
					if (timestamp < 0 || timestamp > System.currentTimeMillis() + 86400000L)
					{
						continue;
					}
					
					if (Instant.ofEpochMilli(timestamp).isBefore(cutoff))
					{
						configManager.unsetConfiguration("arraxxor", key);
						continue;
					}
					
					AraxxorKillRecord kill = new AraxxorKillRecord();
					kill.setTimestamp(timestamp);
					
					long killTime = Long.parseLong(valueParts[1]);
					if (killTime < 0 || killTime > 3600000L)
					{
						continue;
					}
					kill.setKillTime(killTime);
					
					try
					{
						kill.setRotation(AraxxorEggType.valueOf(valueParts[2]));
					}
					catch (IllegalArgumentException e)
					{
						kill.setRotation(null);
					}
					
					long lootValue = Long.parseLong(valueParts[3]);
					if (lootValue < 0 || lootValue > Long.MAX_VALUE / 2)
					{
						continue;
					}
					kill.setLootValue(lootValue);
					
					int hits = Integer.parseInt(valueParts[4]);
					int damageDealt = Integer.parseInt(valueParts[5]);
					int damageTaken = Integer.parseInt(valueParts[6]);
					if (hits < 0 || hits > 10000 || damageDealt < 0 || damageDealt > 1000000 || 
						damageTaken < 0 || damageTaken > 1000000)
					{
						continue;
					}
					kill.setHits(hits);
					kill.setDamageDealt(damageDealt);
					kill.setDamageTaken(damageTaken);
					
					if (valueParts.length >= 8 && !valueParts[7].isEmpty())
					{
						if (valueParts[7].length() > 5000)
						{
							continue;
						}
						
						String[] lootItems = valueParts[7].split(",", 100);
						for (String lootItem : lootItems)
						{
							if (lootItem.length() > 50)
							{
								continue;
							}
							
							String[] itemParts = lootItem.split(":", 2);
							if (itemParts.length == 2)
							{
								int itemId = Integer.parseInt(itemParts[0]);
								long quantity = Long.parseLong(itemParts[1]);
								
								if (itemId < 0 || itemId > Integer.MAX_VALUE || quantity < 0 || quantity > 1000000L)
								{
									continue;
								}
								
								kill.getLoot().put(itemId, quantity);
							}
						}
					}
					
					
					sessionKills.add(kill);
					cachedSessionTotalValue += kill.getLootValue();
				}
			}
		catch (Exception e)
		{
		}
		}
		
		if (!sessionKills.isEmpty())
		{
			sessionKills.sort(Comparator.comparingLong(AraxxorKillRecord::getTimestamp));
			AraxxorKillRecord mostRecent = sessionKills.get(sessionKills.size() - 1);
			lastKillTimestamp = mostRecent.getTimestamp();
		}
		
		if (!sessionKills.isEmpty() && configPanel != null && clientThread != null)
		{
			AraxxorConfigPanel panelRef = configPanel;
			clientThread.invokeLater(() -> {
				if (panelRef == null)
				{
					return;
				}
				
				for (AraxxorKillRecord kill : sessionKills)
				{
					if (kill.getLoot() != null)
					{
						for (int itemId : kill.getLoot().keySet())
						{
							panelRef.cacheItemName(itemId);
							panelRef.cacheItemPrice(itemId);
						}
					}
				}
				panelRef.refreshStats();
			});
		}
	}
	
	/**
	 * Get total session loot value (cached)
	 */
	public long getSessionTotalValue()
	{
		return cachedSessionTotalValue;
	}
	
	/**
	 * Create a placeholder icon (fallback)
	 */
	private BufferedImage createPlaceholderIcon()
	{
		BufferedImage fallback = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = fallback.createGraphics();
		g.setColor(new java.awt.Color(139, 69, 19));
		g.fillRect(0, 0, 16, 16);
		g.setColor(java.awt.Color.WHITE);
		g.drawString("A", 4, 12);
		g.dispose();
		return fallback;
	}

	/**
	 * Load the Araxxor pet icon asynchronously and update the navigation button when ready
	 */
	private void loadIconAsync()
	{
		try
		{
			AsyncBufferedImage asyncImage = itemManager.getImage(ItemID.ARAXXORPET);
			if (asyncImage != null)
			{
				asyncImage.onLoaded(() ->
				{
					if (navButton != null)
					{
						BufferedImage resizedIcon = ImageUtil.resizeImage(asyncImage, 16, 16);
						NavigationButton updatedButton = NavigationButton.builder()
							.tooltip(navButton.getTooltip())
							.icon(resizedIcon)
							.priority(navButton.getPriority())
							.panel(navButton.getPanel())
							.build();
						
						clientToolbar.removeNavigation(navButton);
						navButton = updatedButton;
						clientToolbar.addNavigation(navButton);
					}
				});
			}
		}
		catch (Exception e)
		{
		}
	}
}


