package com.renatus;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(RenatusConfig.GROUP)
public interface RenatusConfig extends Config
{
	String GROUP = "renatus-pvm";

	@ConfigItem(
		keyName = "enableApi",
		name = "Connect to clan server",
		description = "Fetch your rank, splits and the clan leaderboard from the Renatus clan server",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		position = 0
	)
	default boolean enableApi()
	{
		return false;
	}

	@ConfigItem(
		keyName = "apiKey",
		name = "API key",
		description = "Your personal key: run /get-api-key in the Renatus Discord and paste it here",
		secret = true,
		position = 1
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigItem(
		keyName = "pendingReminder",
		name = "Pending split reminder",
		description = "On login, remind you of splits you've been tagged in but haven't confirmed in Discord yet",
		position = 3
	)
	default boolean pendingReminder()
	{
		return true;
	}

	@ConfigItem(
		keyName = "newSplitAlert",
		name = "New split alert",
		description = "Chat message and notification when someone tags you in a new split to confirm (checked every 5 minutes while logged in)",
		position = 4
	)
	default boolean newSplitAlert()
	{
		return true;
	}

	@ConfigItem(
		keyName = "pendingInfoBox",
		name = "Pending split info box",
		description = "Show an info box with the number of splits waiting for your confirmation",
		position = 5
	)
	default boolean pendingInfoBox()
	{
		return true;
	}

	@Range(min = 5, max = 50)
	@ConfigItem(
		keyName = "leaderboardSize",
		name = "Leaderboard size",
		description = "How many members to show on the leaderboard tab",
		position = 6
	)
	default int leaderboardSize()
	{
		return 10;
	}
}
