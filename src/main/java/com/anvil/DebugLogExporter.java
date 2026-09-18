package com.anvil;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * One-click support export: a caller-supplied diagnostic header written to a shareable .txt in the
 * plugin's own {@code debug/} folder, with its path on the clipboard so it can be dragged into
 * Discord. All file work runs off the client thread by the caller.
 *
 * <p>IT USED TO CARRY THE CLIENT LOG, and that was most of its value — the Anvil-tagged lines out of
 * {@code .runelite/logs/client.log} plus the recent tail, so "my drop never submitted" arrived with
 * the stack trace already attached. A plugin can no longer open that file: {@link Filepath} cannot
 * escape the directory it is rooted at, and client.log belongs to RuneLite, not to us.
 *
 * <p>What is left is the header — site URL set, token set, RSN, queue depth, versions — which
 * answers the configuration half of most reports and none of the "it threw something" half. The
 * honest fallback is to keep asking for the log in the message, since the member can still attach it
 * themselves.
 */
@Slf4j
@Singleton
public class DebugLogExporter
{
	private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	/** The plugin's own `debug/` folder, handed over by AnvilPlugin at startUp. Null = no export. */
	private volatile Filepath root;

	@Inject
	public DebugLogExporter()
	{
	}

	public static class Result
	{
		/** Where it landed, as text — the same string that went on the clipboard. */
		public final String path;

		Result(String path)
		{
			this.path = path;
		}
	}

	public void setRoot(Filepath dir)
	{
		this.root = dir;
	}

	/**
	 * Write the bundle, copy its path to the clipboard, and open the folder. Never throws — on failure
	 * it returns null and logs, so the caller can show a friendly fallback message.
	 */
	public Result export(String header)
	{
		Filepath dir = root;
		if (dir == null)
		{
			log.warn("Anvil: no plugin directory, cannot export a debug log");
			return null;
		}
		try
		{
			if (!dir.exists())
			{
				dir.createDirectories();
			}

			String nl = System.lineSeparator();
			StringBuilder sb = new StringBuilder();
			sb.append(header).append(nl).append(nl);
			// Said here rather than left as an absence, because the person reading this file is a
			// clan admin wondering where the stack trace went.
			sb.append("=== Client log ===").append(nl);
			sb.append("Not included: plugins can no longer read RuneLite's client.log.").append(nl);
			sb.append("If this is a crash or a drop that never submitted, attach your own copy too —")
				.append(nl);
			sb.append("it is in the 'logs' folder next to this one, named client.log.").append(nl);

			Filepath out = dir.joinSegment("anvil-debug-" + LocalDateTime.now().format(FILE_TS) + ".txt");
			out.write(sb.toString());

			String path = out.toString();
			copyToClipboard(path);

			log.info("Anvil: exported debug log to {}", path);
			return new Result(path);
		}
		catch (Exception e)
		{
			log.warn("Anvil: debug log export failed: {}", e.getMessage());
			return null;
		}
	}

	private void copyToClipboard(String value)
	{
		Clipboards.copy(value);
	}

}
