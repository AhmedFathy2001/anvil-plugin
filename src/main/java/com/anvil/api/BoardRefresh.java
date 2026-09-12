package com.anvil.api;

/**
 * Ask the site for the board again, right now.
 *
 * <p>A credit should pull the board back: the tile's new total is what the side panel shows, and the
 * server may have completed it. The trackers should not have to know how that happens.</p>
 *
 * <p>It exists as a type rather than as an injected {@link EventConfigStore} because the store
 * reaches back for almost every tracker — injecting it directly would be a dependency cycle. The
 * plugin provides this from a {@code Provider}, so the store is built the first time somebody
 * actually asks for a refresh rather than while its own dependencies are still being constructed.</p>
 */
@FunctionalInterface
public interface BoardRefresh
{
	void now();
}
