package com.anvil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.game.ItemStack;

/**
 * Items a chat line already credited, held until the loot event for the same acquisition turns up.
 *
 * <p>A raid announces its unique the moment the raid ends ("You received a drop: Dexterous prayer
 * scroll (Chambers of Xeric)") and the drop path credits it then; the chest's loot event only fires
 * when the player opens it, minutes later — far outside the 3s per-tile window — and used to credit
 * the same item a second time. Each chat credit now cancels exactly one later loot copy of the item.
 */
final class ChatCredits
{
	/** Long enough to sit in the raid lobby before opening the chest. */
	static final long WINDOW_MS = 30 * 60_000;

	/** itemId → {quantity still owed, when it was credited}. */
	private final Map<Integer, long[]> owed = new HashMap<>();

	/** Lines naming the same item this close together (drop line + clog line) are one acquisition. */
	static final long SAME_DROP_MS = 10_000;

	synchronized void note(Collection<ItemStack> credited, long now)
	{
		for (ItemStack s : credited)
		{
			long[] o = owed.get(s.getId());
			long qty = o == null || now - o[1] > WINDOW_MS ? s.getQuantity()
				: now - o[1] <= SAME_DROP_MS ? Math.max(o[0], s.getQuantity())
				: o[0] + s.getQuantity();
			owed.put(s.getId(), new long[]{qty, now});
		}
	}

	/** {@code items} less whatever a chat line already credited; consumes those claims. */
	synchronized List<ItemStack> unclaimed(Collection<ItemStack> items, long now)
	{
		owed.values().removeIf(o -> now - o[1] > WINDOW_MS);
		List<ItemStack> out = new ArrayList<>(items.size());
		for (ItemStack s : items)
		{
			long[] o = owed.get(s.getId());
			int left = s.getQuantity();
			if (o != null)
			{
				int take = (int) Math.min(o[0], left);
				left -= take;
				o[0] -= take;
				if (o[0] <= 0)
				{
					owed.remove(s.getId());
				}
			}
			if (left > 0)
			{
				out.add(left == s.getQuantity() ? s : new ItemStack(s.getId(), left));
			}
		}
		return out;
	}
}
