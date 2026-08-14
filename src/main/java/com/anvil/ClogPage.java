package com.anvil;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One collection-log page as the game drew it: the entry name, every item the account has obtained
 * on it, and the counters the log prints under the title ("Abyssal Sire kills: 1,204").
 *
 * <p>Deliberately RuneLite-free so the accumulator and its tests never need a client. Reading the
 * widgets is {@link ClogPageReader}'s job; deciding what to send is {@link ClogSync}'s.
 *
 * <p><b>Only obtained items are carried.</b> The site already ships the full 1,712-item catalogue
 * (src/data/clog.json), so the missing half is derivable — sending it would double every payload to
 * say "still nothing here".
 */
final class ClogPage
{
	/** Entry title as the log prints it — "Abyssal Sire". The site maps it to its own page id. */
	final String name;
	/** Obtained item ids, in the order the log lists them. Parallel to {@link #quantities}. */
	final int[] itemIds;
	final int[] quantities;
	/** The log's own "Obtained: 5/9" pair — the site shows progress without owning the catalogue. */
	final int obtained;
	final int total;
	/** The counter lines under the title, label → value. Empty for pages that print none. */
	final Map<String, Integer> counts;
	/** 64-bit content hash — what makes "has this page changed?" a comparison, not a diff. */
	final long hash;

	ClogPage(String name, int[] itemIds, int[] quantities, int obtained, int total, Map<String, Integer> counts)
	{
		this.name = name;
		this.itemIds = itemIds;
		this.quantities = quantities;
		this.obtained = obtained;
		this.total = total;
		this.counts = counts == null || counts.isEmpty()
			? Collections.emptyMap()
			: Collections.unmodifiableMap(new LinkedHashMap<>(counts));
		this.hash = computeHash();
	}

	/**
	 * FNV-1a over the page's contents.
	 *
	 * <p>Hand-rolled rather than {@code Objects.hash} / a JSON round-trip because this runs on the
	 * client thread every time the log redraws — which is every keystroke while a player scrolls the
	 * log. No boxing, no allocation, no string building; a page is folded in a few hundred nanoseconds
	 * and the result is what stops us re-sending 125 identical pages.
	 */
	private long computeHash()
	{
		long h = 0xcbf29ce484222325L;
		h = fold(h, name == null ? 0 : name.hashCode());
		h = fold(h, obtained);
		h = fold(h, total);
		for (int i = 0; i < itemIds.length; i++)
		{
			h = fold(h, itemIds[i]);
			h = fold(h, quantities[i]);
		}
		for (Map.Entry<String, Integer> e : counts.entrySet())
		{
			h = fold(h, e.getKey().hashCode());
			h = fold(h, e.getValue());
		}
		return h;
	}

	private static long fold(long h, int value)
	{
		h ^= value;
		return h * 0x100000001b3L;
	}
}
