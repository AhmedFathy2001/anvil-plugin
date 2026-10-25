package com.anvil;

import java.awt.image.BufferedImage;
import java.util.*;
import javax.inject.*;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.ui.overlay.worldmap.*;
import net.runelite.client.util.ImageUtil;

/**
 * Points the way to the starting shot's drawn spot while one is owed: the game's own hint arrow (over
 * the tile up close, on the minimap edge from afar) and a world-map pin that clamps to the map's edge
 * and jumps there on click. {@link #route} asks the Shortest Path plugin, when installed, for the
 * whole route; without it the message simply goes unheard.
 */
@Singleton
class StartSpotMarker
{
	private static final BufferedImage PIN = ImageUtil.resizeImage(
		ImageUtil.loadImageResource(StartSpotMarker.class, "/com/anvil/sidebar_icon.png"), 20, 20);

	private final Client client;
	private final WorldMapPointManager mapPoints;
	private final EventBus eventBus;

	private WorldPoint shown;
	private WorldMapPoint mapPoint;

	@Inject
	StartSpotMarker(Client client, WorldMapPointManager mapPoints, EventBus eventBus)
	{
		this.client = client;
		this.mapPoints = mapPoints;
		this.eventBus = eventBus;
	}

	/** The spot to mark, or null for none. Spots are drawn on the surface, so the plane is 0. */
	static WorldPoint target(PluginConfigResponse.StartProof proof)
	{
		return proof == null || proof.spot == null ? null : new WorldPoint(proof.spot.x, proof.spot.y, 0);
	}

	/**
	 * Client thread, every tick. Sets the arrow and pin once when the target appears or moves and takes
	 * them down when it goes, so a quest's own hint arrow is only ever replaced while a shot is owed.
	 */
	void update(WorldPoint target, String label)
	{
		if (Objects.equals(target, shown))
		{
			return;
		}
		clear();
		if (target == null)
		{
			return;
		}
		client.setHintArrow(target);
		mapPoint = WorldMapPoint.builder()
			.worldPoint(target)
			.image(PIN)
			.name("Anvil starting shot")
			.tooltip("Anvil starting shot" + (label != null ? ": " + label : ""))
			.snapToEdge(true)
			.jumpOnClick(true)
			.build();
		mapPoints.add(mapPoint);
		shown = target;
	}

	/** Client thread. Takes down our arrow (never someone else's) and our pin. */
	void clear()
	{
		if (shown != null && client.hasHintArrow() && shown.equals(client.getHintArrowPoint()))
		{
			client.clearHintArrow();
		}
		if (mapPoint != null)
		{
			mapPoints.remove(mapPoint);
			mapPoint = null;
		}
		shown = null;
	}

	/** Ask Shortest Path for a route to {@code target}. Client thread. */
	void route(WorldPoint target)
	{
		if (target != null)
		{
			eventBus.post(new PluginMessage("shortestpath", "path", Collections.singletonMap("target", target)));
		}
	}
}
