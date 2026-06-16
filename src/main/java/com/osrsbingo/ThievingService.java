package com.osrsbingo;

import com.google.gson.Gson;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.OptionalDouble;

/**
 * Pickpocket drop rarity, backed by the bundled thieving.json (plus a couple of hard-coded shards
 * the wiki doesn't express cleanly).
 * Adapted from Dink (pajlads/DinkPlugin), BSD-2-Clause — see THIRD_PARTY_NOTICES.md.
 */
@Singleton
public class ThievingService extends AbstractRarityService {

    @Inject
    ThievingService(Gson gson, ItemManager itemManager) {
        super("/thieving.json", 32, gson, itemManager);
    }

    @Override
    public OptionalDouble getRarity(String sourceName, int itemId, int quantity) {
        if (itemId == ItemID.BLOOD_SHARD) {
            // https://oldschool.runescape.wiki/w/Blood_shard#Item_sources
            return OptionalDouble.of(1.0 / 5000);
        }

        if (itemId == ItemID.PRIF_TELEPORT_SEED) {
            // https://oldschool.runescape.wiki/w/Enhanced_crystal_teleport_seed#Item_sources
            return OptionalDouble.of(1.0 / 1024);
        }

        return super.getRarity(sourceName, itemId, quantity);
    }
}
