package com.anvil;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;

@ConfigGroup("osrsbingo")
public interface AnvilConfig extends Config
{
	// ---- Sections (collapsible groups, top to bottom) ----
	// Split by purpose: Setup + Bingo are event-participation; the "Notifications:" sections are
	// clan-wide Discord posts that fire with or without an active bingo.

	@ConfigSection(
		name = "Setup",
		description = "Connect the plugin to your bingo site.",
		position = 1
	)
	String setupSection = "setupSection";

	@ConfigSection(
		name = "Bingo",
		description = "In-event behaviour — only matters while you're in a bingo.",
		position = 2
	)
	String bingoSection = "bingoSection";

	@ConfigSection(
		name = "Notifications: Deaths & kills",
		description = "Clan Discord posts (with screenshot) for your deaths and PvP kills. Independent of bingo.",
		position = 3
	)
	String deathsSection = "deathsSection";

	@ConfigSection(
		name = "Notifications: Drops & pets",
		description = "Clan Discord posts for valuable / rare drops and pets. Independent of bingo.",
		position = 4
	)
	String dropsSection = "dropsSection";

	@ConfigSection(
		name = "Notifications: Combat achievements",
		description = "Clan Discord posts for CA tier clears and high-tier tasks. Independent of bingo.",
		position = 5,
		closedByDefault = true
	)
	String caSection = "caSection";

	@ConfigSection(
		name = "Clips",
		description = "On-demand replay clips via OBS. Off by default — requires OBS with the WebSocket server + Replay Buffer running.",
		position = 6,
		closedByDefault = true
	)
	String clipsSection = "clipsSection";

	@ConfigSection(
		name = "Support",
		description = "Trouble with a drop or login? Export a debug log to send your clan admin.",
		position = 7,
		closedByDefault = true
	)
	String supportSection = "supportSection";

	// ---- Setup ----

	@ConfigItem(
		keyName = "apiUrl",
		name = "Site URL",
		description = "The base URL of your Anvil site, e.g. https://your-clan.vercel.app (no trailing slash). If you leave off https://, it's added automatically. Ask your clan admin if unsure.",
		position = 1,
		section = "setupSection"
	)
	default String apiUrl()
	{
		return "";
	}

	@ConfigItem(
		keyName = "playerToken",
		name = "Account Token",
		description = "Your account token from the bingo site (Profile → Plugin). One token works across every event you're signed up for.",
		position = 2,
		secret = true,
		section = "setupSection"
	)
	default String playerToken()
	{
		return "";
	}

	// ---- Bingo ----

	@ConfigItem(
		keyName = "autoSubmit",
		name = "Auto Submit Drops",
		description = "Automatically screenshot and submit when a tracked bingo drop is received",
		position = 1,
		section = "bingoSection"
	)
	default boolean autoSubmit()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show Overlay",
		description = "Show the codeword and date verification overlay on screen",
		position = 2,
		section = "bingoSection"
	)
	default boolean showOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "teamCompletionBanner",
		name = "Team completion popups",
		description = "Show a banner when your team completes a tile — anyone on the team. If several complete "
			+ "at once, the hardest shows as a banner and the rest as chat messages.",
		position = 3,
		section = "bingoSection"
	)
	default boolean teamCompletionBanner()
	{
		return true;
	}

	@ConfigItem(
		keyName = "bannerSound",
		name = "Banner sound",
		description = "Play a sound clip when the bingo banner fires. Add your own .wav files via the "
			+ "'Banner sounds' button in the Bingo collection-log tab, or drop them into the '"
			+ BannerSoundService.USER_DIR_NAME + "' folder in your RuneLite directory. Nothing plays "
			+ "until you add at least one.",
		position = 5,
		section = "bingoSection"
	)
	default boolean bannerSound()
	{
		return true;
	}

	@ConfigItem(
		keyName = "bannerSoundVolume",
		name = "Banner volume",
		description = "Volume of the banner sound clip (0–100).",
		position = 6,
		section = "bingoSection"
	)
	default int bannerSoundVolume()
	{
		return 70;
	}

	@ConfigItem(
		keyName = "missionSound",
		name = "Distinct mission sound",
		description = "Play a short in-game chime when a mission drops or is claimed, instead of the same "
			+ "clip a completed tile plays — so you can tell a new objective from a finished one without "
			+ "looking. Turn off to use your banner clip for missions too.",
		position = 8,
		section = "bingoSection"
	)
	default boolean missionSound()
	{
		return true;
	}

	@ConfigItem(
		keyName = "bannerSoundClip",
		name = "Banner sound clip",
		// Hidden from the panel: the play-cycle allowlist is managed via the Bingo collection-log tab's
		// sound toggles, which read/write this key. Kept as a config key so the selection persists/syncs.
		description = "Comma-separated allowlist of banner clips to cycle (blank = all). Managed in the Bingo tab.",
		position = 7,
		section = "bingoSection",
		hidden = true
	)
	default String bannerSoundClip()
	{
		return "";
	}

	@ConfigItem(
		keyName = "bingoClogTab",
		name = "Bingo tab in Collection Log",
		description = "Show your bingo tasks as a custom tab inside the in-game Collection Log. "
			+ "Experimental — injects into the collection log interface and may need updating after game updates.",
		position = 4,
		section = "bingoSection"
	)
	default boolean bingoClogTab()
	{
		return true;
	}

	@ConfigItem(
		keyName = "dualProofFrames",
		name = "Two-frame drop proof",
		description = "Bake two frames into each drop screenshot — one the moment the drop lands, and one "
			+ "a couple of seconds later once the loot has settled on the floor.",
		position = 8,
		section = "bingoSection"
	)
	default boolean dualProofFrames()
	{
		return true;
	}

	// ---- Notifications: Deaths & kills ----

	@ConfigItem(
		keyName = "notifyDeaths",
		name = "Notify on death",
		description = "Post to the clan deaths channel — with a screenshot of the moment you died — when you die.",
		position = 1,
		section = "deathsSection"
	)
	default boolean notifyDeaths()
	{
		return true;
	}

	@ConfigItem(
		keyName = "deathMessage",
		name = "Death message",
		description = "Your death message. Use {name} for your RSN.",
		position = 2,
		section = "deathsSection"
	)
	default String deathMessage()
	{
		return "{name} just died!";
	}

	@ConfigItem(
		keyName = "notifyPvpKills",
		name = "Notify on PvP kill",
		description = "Post to the clan PvP-kills channel — with a screenshot of the tick your target hits 0 HP — when you kill a player you've damaged.",
		position = 3,
		section = "deathsSection"
	)
	default boolean notifyPvpKills()
	{
		return false;
	}

	// ---- Notifications: Drops & pets ----

	@ConfigItem(
		keyName = "notifyRareDrops",
		name = "Notify on rare drops",
		description = "Post valuable or very rare drops to the clan rare-drops channel.",
		position = 1,
		section = "dropsSection"
	)
	default boolean notifyRareDrops()
	{
		return true;
	}

	@ConfigItem(
		keyName = "rareDropMinValue",
		name = "Min drop value",
		description = "Post any drop worth at least this much (the higher of GE and high-alch value). Minimum enforced: 1,000,000. 0 disables value-based posts.",
		position = 2,
		section = "dropsSection"
	)
	default int rareDropMinValue()
	{
		return 5_000_000;
	}

	@ConfigItem(
		keyName = "rareDropMinRarity",
		name = "Min drop rarity (1 in N)",
		description = "Also post extremely rare NPC/pickpocket drops regardless of value (e.g. 10000 = rarer than 1/10000). Your clan can enforce a higher floor. Minimum enforced: 1/1000. 0 disables rarity-based posts.",
		position = 3,
		section = "dropsSection"
	)
	default int rareDropMinRarity()
	{
		// 1/10,000. Anything looser turns the channel into herb and seed rolls — at 1/2000 a normal
		// slayer task posts several times. Raised from 1/5000; see AnvilPlugin.migrateConfigDefaults.
		return 10_000;
	}

	@ConfigItem(
		keyName = "rareDropScreenshot",
		name = "Screenshot rare drops",
		description = "Attach a screenshot to rare-drop posts. Requires 'Notify on rare drops'.",
		position = 4,
		section = "dropsSection"
	)
	default boolean rareDropScreenshot()
	{
		return true;
	}

	@ConfigItem(
		keyName = "lootKeyMinValue",
		name = "Loot key value",
		description = "Post a loot key as one notification when its contents total at least this much. Applies to loot keys only — regular drops use 'Min drop value'. 0 disables loot-key posts.",
		position = 5,
		section = "dropsSection"
	)
	default int lootKeyMinValue()
	{
		return 1_000_000;
	}

	@ConfigItem(
		keyName = "notifyPets",
		name = "Notify on pets",
		description = "Post to the clan rare-drops channel when you receive a pet.",
		position = 6,
		section = "dropsSection"
	)
	default boolean notifyPets()
	{
		return true;
	}

	@ConfigItem(
		keyName = "petScreenshot",
		name = "Screenshot pets",
		description = "Attach a screenshot to pet posts. Requires 'Notify on pets'.",
		position = 7,
		section = "dropsSection"
	)
	default boolean petScreenshot()
	{
		return true;
	}

	// ---- Notifications: Combat achievements ----

	@ConfigItem(
		keyName = "notifyCombatAchievements",
		name = "Notify on combat achievements",
		description = "Post a message to the clan combat-achievements channel when you clear a CA tier (and high-tier individual tasks).",
		position = 1,
		section = "caSection"
	)
	default boolean notifyCombatAchievements()
	{
		return true;
	}

	@ConfigItem(
		keyName = "caMinTaskTier",
		name = "CA task min tier",
		description = "Post individual combat-task completions at or above this tier. Tier clears always post regardless. Set to Grandmaster to post only the rarest tasks.",
		position = 2,
		section = "caSection"
	)
	default CombatAchievementTier caMinTaskTier()
	{
		return CombatAchievementTier.MASTER;
	}

	@ConfigItem(
		keyName = "caScreenshot",
		name = "Screenshot combat tasks",
		description = "Attach a screenshot to combat-achievement posts, the way drop posts work. Requires 'Notify on combat achievements'.",
		position = 3,
		section = "caSection"
	)
	default boolean caScreenshot()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyClogSlots",
		name = "Notify on collection log slots",
		description = "Post every NEW collection-log slot to the clan achievements channel. Prestige items (Infernal cape, quivers, …) still go to the drops channel instead, so nothing posts twice.",
		position = 4,
		section = "caSection"
	)
	default boolean notifyClogSlots()
	{
		return true;
	}

	@ConfigItem(
		keyName = "clogScreenshot",
		name = "Screenshot collection log slots",
		description = "Attach a screenshot to collection-log posts. Requires 'Notify on collection log slots'.",
		position = 5,
		section = "caSection"
	)
	default boolean clogScreenshot()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyLevelUps",
		name = "Notify on 99s & high totals",
		description = "Post to the clan combat-achievements channel when you reach level 99 in a skill, hit a high total-level milestone (every 100 from 1800 up), or max.",
		position = 6,
		section = "caSection"
	)
	default boolean notifyLevelUps()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyDiaries",
		name = "Notify on diary completions",
		description = "Post to the clan achievements channel when you complete an achievement-diary tier.",
		position = 7,
		section = "caSection"
	)
	default boolean notifyDiaries()
	{
		return true;
	}

	@ConfigItem(
		keyName = "questAnnounce",
		name = "Announce quest completions",
		description = "Post quest completions to the clan achievements channel at or above this difficulty. "
			+ "\"Master & up\" covers Master and Grandmaster quests.",
		position = 8,
		section = "caSection"
	)
	default QuestAnnounceTier questAnnounce()
	{
		return QuestAnnounceTier.MASTER;
	}

	// ---- Clips ----

	@ConfigItem(
		keyName = "clipsEnabled",
		name = "Enable clip capture",
		description = "Master switch. When on, the plugin connects to OBS so you can save replay-buffer clips on demand. "
			+ "You must also have OBS running with the WebSocket server enabled and a Replay Buffer started.",
		position = 1,
		section = "clipsSection"
	)
	default boolean clipsEnabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = "clipHotkey",
		name = "Capture clip hotkey",
		description = "Press this to save the last X seconds (your OBS replay-buffer length) — for funny moments not covered by the OBS plugin's own triggers.",
		position = 2,
		section = "clipsSection"
	)
	default Keybind clipHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "obsHost",
		name = "OBS host",
		description = "Host of the OBS WebSocket server. Use localhost if OBS runs on this PC.",
		position = 3,
		section = "clipsSection"
	)
	default String obsHost()
	{
		return "localhost";
	}

	@ConfigItem(
		keyName = "obsPort",
		name = "OBS port",
		description = "OBS WebSocket server port (Tools → WebSocket Server Settings). Default 4455.",
		position = 4,
		section = "clipsSection"
	)
	default int obsPort()
	{
		return 4455;
	}

	@ConfigItem(
		keyName = "obsPassword",
		name = "OBS password",
		description = "OBS WebSocket server password (from 'Show Connect Info'). Leave blank if OBS authentication is off.",
		position = 5,
		secret = true,
		section = "clipsSection"
	)
	default String obsPassword()
	{
		return "";
	}

	@ConfigItem(
		keyName = "clipMaxMb",
		name = "Max auto-post size (MB)",
		description = "Clips up to this size are auto-posted to the clan clips channel; larger ones just get a quiet in-game notice (saved locally). Match this to your Discord upload limit (usually 25).",
		position = 6,
		section = "clipsSection"
	)
	default int clipMaxMb()
	{
		return 25;
	}

	@ConfigItem(
		keyName = "clipLengthSeconds",
		name = "Clip length (seconds)",
		description = "How many seconds each clip captures. Sets your OBS replay-buffer length; change it any "
			+ "time and OBS adopts the new length (it needs that many seconds to refill before a full clip).",
		position = 7,
		section = "clipsSection"
	)
	default int clipLengthSeconds()
	{
		return 15;
	}

	@ConfigItem(
		keyName = "clipMp4",
		name = "Save clips as MP4",
		description = "Tell OBS to record MP4 so clips preview/play inline in Discord (MKV has to be downloaded). "
			+ "This changes OBS's recording format, which also affects your normal OBS recordings. Turn off to "
			+ "leave OBS's format untouched.",
		position = 8,
		section = "clipsSection"
	)
	default boolean clipMp4()
	{
		return true;
	}

	@ConfigItem(
		keyName = "clipsWebhookUrl",
		name = "Clips Discord webhook URL",
		description = "Optional fallback. Clips normally go to your clan's clips channel through the Anvil "
			+ "site, with no setup on your side. Paste your own Discord webhook URL here to post clips when "
			+ "your clan hasn't set a clips channel up (or its site is too old to relay them) — those upload "
			+ "straight from your machine to this webhook. Leave blank to keep clips local in that case.",
		position = 9,
		section = "clipsSection"
	)
	default String clipsWebhookUrl()
	{
		return "";
	}

	@ConfigItem(
		keyName = "postObsTriggeredClips",
		name = "Post OBS-triggered clips too",
		description = "Also handle replay-buffer saves triggered by OBS itself or the \"Save Replay Buffer for OBS\" "
			+ "RuneLite plugin (its auto-clips on drops, deaths, etc.) — not just the hotkey above. They'll be "
			+ "posted/saved the same way. Leave off if you run more than one RuneLite client on this same OBS, or "
			+ "each would post a copy of every clip.",
		position = 10,
		section = "clipsSection"
	)
	default boolean postObsTriggeredClips()
	{
		return false;
	}

	// ---- Support ----

	@ConfigItem(
		keyName = "exportDebugLogHotkey",
		name = "Export debug log hotkey",
		description = "Press this to save a debug log you can send your clan admin. You can also just type "
			+ "::anvillog in the game chat. The log is saved to your .runelite/anvil-debug folder, which opens "
			+ "automatically, and its location is copied to your clipboard.",
		position = 1,
		section = "supportSection"
	)
	default Keybind exportDebugLogHotkey()
	{
		return Keybind.NOT_SET;
	}

}
