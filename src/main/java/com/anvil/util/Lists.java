package com.anvil.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Small list helpers shared by the view types. */
public final class Lists
{
	private Lists()
	{
	}

	/**
	 * A defensive, unmodifiable copy — or an empty list for a null one.
	 *
	 * <p>The view types are snapshots handed from a worker thread to Swing. Copying on the way in is
	 * what makes them safe to hold: the source list goes on being mutated by whoever built it, and a
	 * repaint reading it half-written is a crash in the middle of the side panel. Unmodifiable on top
	 * so the panel cannot mutate what it was shown either.</p>
	 *
	 * <p>Empty rather than null because every caller renders a list, and "nothing to show" is a
	 * normal state, not an absence to guard at each use.</p>
	 */
	public static <T> List<T> copyOrEmpty(List<T> src)
	{
		return src == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(src));
	}
}
