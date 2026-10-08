package com.anvil;

import java.util.Locale;

/** Reject recording paths that plainly belong to a different operating system. */
final class ObsPathPolicy
{
	private ObsPathPolicy() {}

	static boolean isCompatible(String path)
	{
		return isCompatible(path, System.getProperty("os.name", ""));
	}

	static boolean isCompatible(String path, String osName)
	{
		if (path == null || path.trim().isEmpty())
		{
			return false;
		}
		String p = path.trim();
		String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
		boolean windowsPath = p.matches("^[A-Za-z]:[\\\\/].*") || p.startsWith("\\\\");
		if (os.contains("win"))
		{
			return windowsPath;
		}
		if (windowsPath || !p.startsWith("/"))
		{
			return false;
		}
		if (os.contains("mac") || os.contains("darwin"))
		{
			return !p.startsWith("/home/");
		}
		// `/Users/<name>` is macOS's home root. Treating it as a Linux backup is the exact
		// cross-machine failure this class exists to prevent.
		return !p.startsWith("/Users/");
	}
}
