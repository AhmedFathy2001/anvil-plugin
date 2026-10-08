package com.anvil;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Locale;

/** Folder management is safe only when the OBS process and RuneLite share a filesystem. */
final class ObsHostPolicy
{
	private ObsHostPolicy() {}

	static boolean isLocal(String host)
	{
		if (host == null)
		{
			return false;
		}
		String h = host.trim().toLowerCase(Locale.ROOT);
		if ("localhost".equals(h) || "localhost.".equals(h) || "::1".equals(h) || "[::1]".equals(h)
			|| "0:0:0:0:0:0:0:1".equals(h))
		{
			return true;
		}
		if (h.matches("127(?:\\.\\d{1,3}){3}"))
		{
			return true;
		}

		// A Mac may connect to its own OBS using its LAN address. That address is local on the Mac and
		// remote on a Linux machine sharing the same synced RuneLite config — exactly the distinction
		// the path guard needs. Resolution failures are safe: unknown means “do not manage”.
		try
		{
			String unwrapped = h.startsWith("[") && h.endsWith("]") ? h.substring(1, h.length() - 1) : h;
			for (InetAddress address : InetAddress.getAllByName(unwrapped))
			{
				if (address.isLoopbackAddress() || NetworkInterface.getByInetAddress(address) != null)
				{
					return true;
				}
			}
		}
		catch (Exception ignored)
		{
			// A host we cannot prove belongs to this computer is remote for folder-management purposes.
		}
		return false;
	}

	static boolean canManage(boolean requested, String host)
	{
		return requested && isLocal(host);
	}
}
