package com.renatus;

import java.awt.image.BufferedImage;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.infobox.Counter;

/** On-screen counter of splits waiting for the player's confirmation. */
class PendingSplitsInfoBox extends Counter
{
	PendingSplitsInfoBox(BufferedImage image, Plugin plugin, int count)
	{
		super(image, plugin, count);
	}

	@Override
	public String getTooltip()
	{
		int count = getCount();
		return "Renatus: " + count + (count == 1 ? " split" : " splits") + " waiting for your confirmation in Discord";
	}
}
