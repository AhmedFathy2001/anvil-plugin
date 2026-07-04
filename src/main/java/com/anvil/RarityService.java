package com.anvil;

import com.google.gson.Gson;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * NPC drop rarity, backed by the bundled npc_drops.json.
 * Adapted from Dink (pajlads/DinkPlugin), BSD-2-Clause — see THIRD_PARTY_NOTICES.md.
 */
@Singleton
public class RarityService extends AbstractRarityService {
    @Inject
    RarityService(Gson gson, ItemManager itemManager) {
        super("/npc_drops.json", 1024, gson, itemManager);
    }
}
