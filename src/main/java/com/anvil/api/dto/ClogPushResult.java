package com.anvil.api.dto;

import com.anvil.clog.ClogFullSync;

/**
 * POST /api/plugin/clog — push the WHOLE collection log as a flat obtained-item list.
 *
 * The page-by-page route sends what the player has drawn; this sends everything the server
 * transmitted (see {@link ClogFullSync}). No page names travel: the site owns the catalogue and
 * maps ids onto pages, so a Jagex reshuffle is a dataset rebuild there rather than a release here.
 */
/** What a whole-log push changed, so an automatic sync can stay quiet when it changed nothing. */
public final class ClogPushResult
{
	public int added;
	public int removed;
	public int updated;

	public boolean movedAnything()
	{
		return added > 0 || removed > 0 || updated > 0;
	}
}
