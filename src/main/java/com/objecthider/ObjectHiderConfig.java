package com.objecthider;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(ObjectHiderConfig.GROUP)
public interface ObjectHiderConfig extends Config
{
	String GROUP = "objecthider";

	@ConfigItem(
		keyName = "hideMenuEnabled",
		name = "Show hide/unhide menu option",
		description = "Show the 'Hide Object' and 'Unhide Object' options when right-clicking objects in-game.",
		position = 1
	)
	default boolean hideMenuEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "revealHidden",
		name = "Reveal Hidden Objects",
		description = "Temporarily reveals all hidden objects so they can be right-clicked and unhidden. "
			+ "Turn this off when done to restore hiding.",
		position = 2
	)
	default boolean revealHidden()
	{
		return false;
	}
}
