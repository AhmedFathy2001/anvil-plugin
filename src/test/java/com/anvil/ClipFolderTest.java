package com.anvil;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Turning the path OBS reports into a file NAME, which is the only part of it the plugin may act on.
 *
 * <p>The path itself is off limits: a Filepath cannot be built from a string, so the clip is found
 * by looking up its name inside a directory the plugin already holds. Get the name wrong and a clip
 * that is sitting right there in our own folder reads as "somewhere we may not look", and the player
 * is asked for a folder they already gave us.
 *
 * <p>Both separators matter regardless of the OS running this code: OBS reports Windows paths with
 * backslashes, and RuneLite on Windows is where most clips are taken.
 */
public class ClipFolderTest
{
	@Test
	public void readsTheNameOffAWindowsPath()
	{
		assertEquals("Replay 2026-09-20 03-15-42.mkv",
			ClipFolder.fileName("C:\\Users\\ahmed\\Videos\\Replay 2026-09-20 03-15-42.mkv"));
	}

	@Test
	public void readsTheNameOffAUnixPath()
	{
		assertEquals("Replay 2026-09-20.mp4", ClipFolder.fileName("/home/ahmed/Videos/Replay 2026-09-20.mp4"));
	}

	@Test
	public void keepsABareName()
	{
		assertEquals("clip.mp4", ClipFolder.fileName("clip.mp4"));
	}

	@Test
	public void ignoresATrailingSeparator()
	{
		assertEquals("Videos", ClipFolder.fileName("/home/ahmed/Videos/"));
	}

	/**
	 * Nothing usable is null, never the empty string: the caller treats null as "we can't reach this
	 * clip" and queues it, whereas an empty name handed to joinSegment is an exception per clip.
	 */
	@Test
	public void nothingUsableIsNull()
	{
		assertNull(ClipFolder.fileName(null));
		assertNull(ClipFolder.fileName(""));
		assertNull(ClipFolder.fileName("   "));
		assertNull(ClipFolder.fileName("/"));
		assertNull(ClipFolder.fileName("\\\\"));
	}

	/**
	 * A name that escapes the directory is the sandbox's whole concern, so it must survive parsing
	 * intact rather than being quietly normalised — joinSegment is what rejects it, loudly.
	 */
	@Test
	public void doesNotUnwindTraversal()
	{
		assertEquals("..", ClipFolder.fileName("/home/ahmed/Videos/.."));
	}
}
