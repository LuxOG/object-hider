package com.objecthider;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(ObjectHiderConfig.GROUP)
public interface ObjectHiderConfig extends Config
{
	String GROUP = "objecthider";

	@ConfigItem(
		keyName = "hiddenObjectIds",
		name = "Hidden Object IDs",
		description = "Comma-separated list of object IDs to hide. Right-click any object in-game to add it. Copy and paste this list to share it with other users.",
		position = 1
	)
	default String hiddenObjectIds()
	{
		return "";
	}

	@ConfigItem(
		keyName = "hideMenuEnabled",
		name = "Show hide/unhide menu option",
		description = "Show the 'Hide Object' and 'Unhide Object' options when right-clicking objects in-game.",
		position = 2
	)
	default boolean hideMenuEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "revealHidden",
		name = "Reveal Hidden Objects",
		description = "Temporarily reveals all hidden objects so they can be right-clicked and unhidden. "
			+ "Objects in your hidden list will appear with a cyan tint so you can identify them. "
			+ "Turn this off when done to restore hiding.",
		position = 3
	)
	default boolean revealHidden()
	{
		return false;
	}
}
