package com.objecthider;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@PluginDescriptor(
	name = "Object Hider",
	description = "Hide specific game objects by ID. Right-click any object to hide it, or enable Reveal Hidden to show them.",
	tags = {"hide", "object", "scenery", "blind", "render", "gpu"}
)
@Slf4j
public class ObjectHiderPlugin extends Plugin implements RenderCallback
{
	private static final String HIDE_OPTION = "Hide Object";
	private static final String UNHIDE_OPTION = "Unhide Object";

	/**
	 * Zone array index offset: (EXTENDED_SCENE_SIZE - SCENE_SIZE) / 2 / 8
	 * EXTENDED = 184, SCENE = 104 → offset = (184-104)/2 = 40 → 40>>3 = 5
	 */
	private static final int ZONE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2 >> 3;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ObjectHiderConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	@Provides
	ObjectHiderConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ObjectHiderConfig.class);
	}

	// ConcurrentHashMap.newKeySet() because drawObject() is called from the maploader thread.
	private final Set<Integer> hiddenObjectIds = ConcurrentHashMap.newKeySet();

	@Override
	protected void startUp()
	{
		parseHiddenObjectIds();
		renderCallbackManager.register(this);
		if (!hiddenObjectIds.isEmpty())
		{
			// Invalidate only zones containing hidden objects (safe — those zones are initialized)
			clientThread.invokeLater(this::invalidateHiddenObjectZones);
		}
	}

	@Override
	protected void shutDown()
	{
		renderCallbackManager.unregister(this);
		// Capture IDs before clearing so we can restore the correct zones
		Set<Integer> toRestore = new HashSet<>(hiddenObjectIds);
		hiddenObjectIds.clear();
		if (!toRestore.isEmpty())
		{
			clientThread.invokeLater(() -> invalidateZonesForIds(toRestore));
		}
	}

	// --- RenderCallback ---

	@Override
	public boolean drawObject(Scene scene, TileObject object)
	{
		if (hiddenObjectIds.contains(object.getId()))
		{
			// In reveal mode show them (they're selectable/unhideable); otherwise suppress.
			return config.revealHidden();
		}
		return true;
	}

	// --- Right-click menu ---

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!config.hideMenuEnabled())
		{
			return;
		}

		// Only inject once per object (on the Examine entry).
		// Objects without an Examine entry are handled in onMenuOpened.
		if (MenuAction.of(event.getType()) != MenuAction.EXAMINE_OBJECT)
		{
			return;
		}

		int objectId = event.getIdentifier();
		boolean isHidden = hiddenObjectIds.contains(objectId);

		if (config.revealHidden() && isHidden)
		{
			client.createMenuEntry(-1)
				.setOption(UNHIDE_OPTION)
				.setTarget(event.getTarget())
				.setIdentifier(objectId)
				.setType(MenuAction.RUNELITE)
				.setParam0(event.getActionParam0())
				.setParam1(event.getActionParam1());
		}
		else if (!config.revealHidden())
		{
			client.createMenuEntry(-1)
				.setOption(HIDE_OPTION)
				.setTarget(event.getTarget())
				.setIdentifier(objectId)
				.setType(MenuAction.RUNELITE)
				.setParam0(event.getActionParam0())
				.setParam1(event.getActionParam1());
		}
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (!config.hideMenuEnabled())
		{
			return;
		}

		Tile tile = client.getSelectedSceneTile();
		if (tile == null)
		{
			return;
		}

		// Collect object IDs already covered by injected entries (from onMenuEntryAdded)
		Set<Integer> coveredIds = new HashSet<>();
		for (MenuEntry entry : event.getMenuEntries())
		{
			if (HIDE_OPTION.equals(entry.getOption()) || UNHIDE_OPTION.equals(entry.getOption()))
			{
				coveredIds.add(entry.getIdentifier());
			}
		}

		// Gather all tile objects on the hovered tile
		List<TileObject> tileObjects = new ArrayList<>();
		GameObject[] gameObjects = tile.getGameObjects();
		if (gameObjects != null)
		{
			for (GameObject go : gameObjects)
			{
				if (go != null)
				{
					tileObjects.add(go);
				}
			}
		}
		WallObject wallObject = tile.getWallObject();
		if (wallObject != null)
		{
			tileObjects.add(wallObject);
		}
		DecorativeObject decorativeObject = tile.getDecorativeObject();
		if (decorativeObject != null)
		{
			tileObjects.add(decorativeObject);
		}
		GroundObject groundObject = tile.getGroundObject();
		if (groundObject != null)
		{
			tileObjects.add(groundObject);
		}

		// Inject an entry for any object not already covered
		for (TileObject obj : tileObjects)
		{
			int objectId = obj.getId();
			if (coveredIds.contains(objectId))
			{
				continue;
			}
			coveredIds.add(objectId);

			boolean isHidden = hiddenObjectIds.contains(objectId);
			ObjectComposition def = client.getObjectDefinition(objectId);
			String rawName = def != null ? def.getName() : null;
			String displayName = (rawName != null && !"null".equals(rawName)) ? rawName : ("Object #" + objectId);
			String targetName = "<col=ffff>" + displayName + "</col>";

			if (config.revealHidden() && isHidden)
			{
				client.createMenuEntry(-1)
					.setOption(UNHIDE_OPTION)
					.setTarget(targetName)
					.setIdentifier(objectId)
					.setType(MenuAction.RUNELITE);
			}
			else if (!config.revealHidden())
			{
				client.createMenuEntry(-1)
					.setOption(HIDE_OPTION)
					.setTarget(targetName)
					.setIdentifier(objectId)
					.setType(MenuAction.RUNELITE);
			}
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (event.getMenuAction() != MenuAction.RUNELITE)
		{
			return;
		}

		if (HIDE_OPTION.equals(event.getMenuOption()))
		{
			log.info("ObjectHider: hiding object id={}", event.getId());
			addToHiddenList(event.getId());
		}
		else if (UNHIDE_OPTION.equals(event.getMenuOption()))
		{
			log.info("ObjectHider: unhiding object id={}", event.getId());
			removeFromHiddenList(event.getId());
		}
	}

	private void addToHiddenList(int objectId)
	{
		hiddenObjectIds.add(objectId);
		saveHiddenObjectIds();
		clientThread.invokeLater(() -> invalidateZonesForId(objectId));
	}

	private void removeFromHiddenList(int objectId)
	{
		hiddenObjectIds.remove(objectId);
		saveHiddenObjectIds();
		clientThread.invokeLater(() -> invalidateZonesForId(objectId));
	}

	/**
	 * Scans the scene for tile objects matching any of the given IDs and invalidates their zones.
	 * Only zones where we actually find a matching object are invalidated — those zones are
	 * guaranteed to be initialized by the GPU, avoiding the {@code assert zone.initialized} crash.
	 */
	private void invalidateZonesForIds(Set<Integer> objectIds)
	{
		assert client.isClientThread();
		if (objectIds.isEmpty())
		{
			return;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}
		Scene scene = wv.getScene();
		DrawCallbacks dc = client.getDrawCallbacks();
		if (dc == null)
		{
			return;
		}

		Set<Long> done = new HashSet<>();
		Tile[][][] tiles = scene.getTiles();
		for (Tile[][] plane : tiles)
		{
			if (plane == null)
			{
				continue;
			}
			for (Tile[] col : plane)
			{
				if (col == null)
				{
					continue;
				}
				for (Tile tile : col)
				{
					if (tile == null)
					{
						continue;
					}
					TileObject found = findAnyObjectOnTile(tile, objectIds);
					if (found != null)
					{
						invalidateZoneForObject(scene, dc, found, done);
					}
				}
			}
		}
	}

	/** Convenience overload for a single object ID. */
	private void invalidateZonesForId(int objectId)
	{
		invalidateZonesForIds(Set.of(objectId));
	}

	/** Invalidates zones for all currently hidden object IDs (used on startup). */
	private void invalidateHiddenObjectZones()
	{
		invalidateZonesForIds(new HashSet<>(hiddenObjectIds));
	}

	private void invalidateZoneForObject(Scene scene, DrawCallbacks dc, TileObject obj, Set<Long> done)
	{
		// Extract scene tile coordinates from the TileObject hash (bits 0-6 = sceneX, bits 7-13 = sceneZ)
		long hash = obj.getHash();
		int sceneX = (int) (hash & 127);
		int sceneZ = (int) ((hash >> 7) & 127);
		// Convert to zone array indices (zone is 8 tiles; add ZONE_OFFSET for extended scene padding)
		int zx = (sceneX >> 3) + ZONE_OFFSET;
		int zz = (sceneZ >> 3) + ZONE_OFFSET;
		long key = ((long) zx << 32) | zz;
		if (done.add(key))
		{
			dc.invalidateZone(scene, zx, zz);
		}
	}

	private TileObject findAnyObjectOnTile(Tile tile, Set<Integer> objectIds)
	{
		WallObject wo = tile.getWallObject();
		if (wo != null && objectIds.contains(wo.getId()))
		{
			return wo;
		}
		DecorativeObject deco = tile.getDecorativeObject();
		if (deco != null && objectIds.contains(deco.getId()))
		{
			return deco;
		}
		GroundObject ground = tile.getGroundObject();
		if (ground != null && objectIds.contains(ground.getId()))
		{
			return ground;
		}
		GameObject[] gos = tile.getGameObjects();
		if (gos != null)
		{
			for (GameObject go : gos)
			{
				if (go != null && objectIds.contains(go.getId()))
				{
					return go;
				}
			}
		}
		return null;
	}

	private void saveHiddenObjectIds()
	{
		String value = hiddenObjectIds.stream()
			.map(String::valueOf)
			.collect(Collectors.joining(","));
		configManager.setConfiguration(ObjectHiderConfig.GROUP, "hiddenObjectIds", value);
	}

	private void parseHiddenObjectIds()
	{
		hiddenObjectIds.clear();
		String raw = config.hiddenObjectIds();
		if (raw == null || raw.trim().isEmpty())
		{
			return;
		}
		Arrays.stream(raw.split(","))
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.forEach(s -> {
				try
				{
					hiddenObjectIds.add(Integer.parseInt(s));
				}
				catch (NumberFormatException e)
				{
					log.warn("Invalid object ID in hidden list: {}", s);
				}
			});
		log.info("ObjectHider: loaded {} hidden object IDs", hiddenObjectIds.size());
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!Objects.equals(event.getGroup(), ObjectHiderConfig.GROUP))
		{
			return;
		}
		if (Objects.equals(event.getKey(), "hiddenObjectIds"))
		{
			parseHiddenObjectIds();
		}
		// When reveal mode toggles, invalidate only zones with hidden objects
		if (Objects.equals(event.getKey(), "revealHidden"))
		{
			clientThread.invokeLater(this::invalidateHiddenObjectZones);
		}
	}
}
