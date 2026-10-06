package com.anvil;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/**
 * The OBS folder we borrowed, kept per MACHINE: the player's own folder (never in synced config —
 * a Mac's /Users/... path once got restored into a Linux OBS) and which clients are running (so the
 * one that closes first doesn't pull the folder out from under the other).
 *
 * <p>Only the no-folder path is tested here: a real Filepath can only come from RuneLite (the plugin
 * directory or a chooser), and building one from a temp dir needs Filepath.Unchecked, which plugins
 * shouldn't touch even in tests.
 */
public class ObsFolderStateTest
{
	@Test
	public void noRootMeansNoStateAndNoThrow()
	{
		ObsFolderState s = new ObsFolderState();
		assertNull(s.backup());
		s.saveBackup("/x");
		s.heartbeat();
		s.leave();
		assertFalse(s.othersAlive());
	}
}
