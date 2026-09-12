package com.anvil.api.dto;

import com.anvil.AnvilPlugin;

public class NotifyChannels
{
	// True when the site has a Discord webhook configured for this notification type. The plugin
	// posts notifications to its OWN server (POST /api/plugin/config's sibling /api/plugin/notify),
	// which forwards them to Discord server-side — the plugin never receives or calls the webhook
	// URL itself. (RuneLite plugin-hub rule: a plugin may not take a URL from a response and call
	// it.) Clips are the exception: they upload straight to a user-pasted webhook in plugin config.
	public boolean rareDrops;
	public boolean deaths;
	public boolean combatAchievements;
	public boolean pvpKills;

	// Channels that used to ride along with one of the above, split so a clan can give each its own
	// Discord channel. Boxed on purpose: `false` and "this site is older than the split" are
	// different answers, and only a null can say the second. An absent flag inherits the channel it
	// used to share (AnvilPlugin.notifyEnabled), so a new plugin against an old site behaves exactly
	// as it did before rather than going quiet.
	public Boolean pets;
	public Boolean levels;
	public Boolean quests;
	public Boolean diaries;
	public Boolean collectionLog;
}
