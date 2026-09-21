package com.anvil;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.audio.AudioPlayer;
import net.runelite.client.util.Filepath;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Plays a clip when the bingo banner fires, and another when a mission drops. No clips ship with the
 * plugin — users supply their own WAV/PCM files (the only format stock Java decodes) through
 * {@link #importSounds}, which copies them into {@code sounds/banner/} or {@code sounds/mission/}.
 * The folder is what makes a clip a banner clip or a mission clip; there is no setting to keep in
 * step with it, and a mission folder left empty simply keeps the built-in chime. The old {@code .runelite/anvil-bingo-sounds}
 * folder is not readable from here and is not the one legacy directory the migration could carry, so
 * anyone who used it re-imports. Nothing plays until a file is added. Playback goes through RuneLite's {@link AudioPlayer} (plugin-hub policy requires
 * this over the raw Java Sound API).
 */
@Slf4j
@Singleton
public class BannerSoundService
{
	/**
	 * The plugin's own `sounds/` folder, handed over by AnvilPlugin at startUp.
	 *
	 * NOT MIGRATED, and deliberately so: the client moves exactly one legacy folder and that slot is
	 * spent on the pending queue, which holds screenshots of drops that cannot be taken again. A .wav
	 * is still wherever the user got it, and Add sounds re-imports it through the same picker they
	 * used the first time.
	 */
	private volatile Filepath root;

	private final AnvilConfig config;
	private final AudioPlayer audioPlayer;
	private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "anvil-banner-sound");
		t.setDaemon(true);
		return t;
	});

	@Inject
	public BannerSoundService(AnvilConfig config, AudioPlayer audioPlayer)
	{
		this.config = config;
		this.audioPlayer = audioPlayer;
	}

	public void setRoot(Filepath dir)
	{
		this.root = dir;
		migrateLooseClips();
	}

	/**
	 * WHICH CLIP IS THIS? Answered by the folder it is in, not by a setting.
	 *
	 * <p>A mission dropping is the opposite kind of news from a tile being finished, so the two
	 * should not sound alike — but "different how?" used to be a toggle choosing between the banner
	 * clip and a built-in chime, with no way to supply a mission clip at all. A folder each answers
	 * it without asking anything: drop a .wav in {@code mission/} and missions play it, leave it
	 * empty and they keep the built-in chime.</p>
	 */
	public enum Kind
	{
		BANNER("banner"),
		MISSION("mission");

		private final String folder;

		Kind(String folder)
		{
			this.folder = folder;
		}
	}

	/** One kind's directory, created on demand, or null when there is nowhere to read or write. */
	private Filepath dir(Kind kind)
	{
		Filepath d = root;
		if (d == null)
		{
			return null;
		}
		try
		{
			Filepath sub = d.joinSegment(kind.folder);
			if (!sub.exists())
			{
				sub.createDirectories();
			}
			return sub;
		}
		catch (IOException e)
		{
			log.warn("Anvil: cannot create the {} sounds directory: {}", kind.folder, e.getMessage());
			return null;
		}
	}

	/**
	 * Clips added before the folders existed sat loose in {@code sounds/}, where nothing looks any
	 * more. They are banner clips — that is all there was — so they are moved once, quietly. Doing it
	 * here rather than asking means nobody's sounds stop working because the layout changed under
	 * them.
	 */
	private void migrateLooseClips()
	{
		Filepath d = root;
		if (d == null || !d.isDirectory())
		{
			return;
		}
		try (Stream<Filepath> walk = d.walk(1))
		{
			List<Filepath> loose = walk
				.filter(f -> f.isFile() && f.getFileName().toLowerCase().endsWith(".wav"))
				.collect(Collectors.toList());
			if (loose.isEmpty())
			{
				return;
			}
			Filepath banner = dir(Kind.BANNER);
			if (banner == null)
			{
				return;
			}
			for (Filepath f : loose)
			{
				try
				{
					f.moveTo(banner.joinSegment(f.getFileName()), StandardCopyOption.REPLACE_EXISTING);
				}
				catch (Exception e)
				{
					log.debug("Could not move {} into banner/: {}", f.getFileName(), e.getMessage());
				}
			}
			log.debug("Anvil: moved {} clip(s) into banner/", loose.size());
		}
		catch (IOException e)
		{
			log.debug("Could not migrate loose clips: {}", e.getMessage());
		}
	}

	/** Every .wav in one kind's folder. Depth 1 — a clip is a file in it, never deeper. */
	private List<Filepath> wavFiles(Kind kind)
	{
		Filepath dir = dir(kind);
		if (dir == null)
		{
			return new ArrayList<>();
		}
		try (Stream<Filepath> walk = dir.walk(1))
		{
			return walk
				.filter(f -> f.isFile() && f.getFileName().toLowerCase().endsWith(".wav"))
				.collect(Collectors.toList());
		}
		catch (IOException e)
		{
			log.debug("Could not list {} sounds: {}", kind.folder, e.getMessage());
			return new ArrayList<>();
		}
	}

	/** True if this kind has at least one .wav (i.e. it could play something of its own). */
	public boolean hasClips(Kind kind)
	{
		return !wavFiles(kind).isEmpty();
	}

	/** All .wav filenames for one kind, case-insensitively sorted (for the in-tab manager). */
	public List<String> listClips(Kind kind)
	{
		List<String> out = new ArrayList<>();
		for (Filepath f : wavFiles(kind))
		{
			out.add(f.getFileName());
		}
		out.sort(String.CASE_INSENSITIVE_ORDER);
		return out;
	}

	/**
	 * A clip filename as something to read: "odablock-we-won.wav" becomes "Odablock we won".
	 *
	 * <p>Clips arrive as downloaded files, so their names are slugs. Printed verbatim they made both
	 * the in-game list and the side panel look like a directory listing that had leaked into the
	 * interface. Lives here rather than in either panel because both of them show the same files.
	 */
	public static String displayName(String file)
	{
		String s = file == null ? "" : file.trim();
		int dot = s.lastIndexOf('.');
		if (dot > 0)
		{
			s = s.substring(0, dot);
		}
		s = s.replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").trim();
		if (s.isEmpty())
		{
			return file == null ? "" : file;
		}
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	/**
	 * True if {@code name} is in the play cycle. The cycle is the comma-separated allowlist in
	 * {@link AnvilConfig#bannerSoundClip()}; an empty allowlist means "every clip plays".
	 */
	public boolean isSelected(String name)
	{
		Set<String> sel = parseSelected(config.bannerSoundClip());
		return sel.isEmpty() || sel.contains(name.toLowerCase());
	}

	/** Parse the comma-separated clip allowlist into lowercased ".wav" filenames. Empty = play all. */
	static Set<String> parseSelected(String csv)
	{
		Set<String> out = new LinkedHashSet<>();
		if (csv == null)
		{
			return out;
		}
		for (String part : csv.split(","))
		{
			String s = part.trim().toLowerCase();
			if (s.isEmpty())
			{
				continue;
			}
			if (!s.endsWith(".wav"))
			{
				s += ".wav";
			}
			out.add(s);
		}
		return out;
	}

	public void play(Kind kind)
	{
		if (!config.bannerSound())
		{
			return;
		}
		audioExecutor.submit(() -> playBlocking(kind));
	}

	private void playBlocking(Kind kind)
	{
		try
		{
			List<Filepath> files = wavFiles(kind);
			if (files.isEmpty())
			{
				return;
			}

			// The cycle is the allowlist (comma-separated filenames); empty = every clip is eligible.
			// Each banner plays one at random from the eligible set, so a multi-clip cycle varies.
			// Mission clips have no allowlist: the folder is already the choice of what plays.
			Set<String> selected = kind == Kind.BANNER ? parseSelected(config.bannerSoundClip()) : Collections.emptySet();
			List<Filepath> candidates = new ArrayList<>();
			for (Filepath f : files)
			{
				if (selected.isEmpty() || selected.contains(f.getFileName().toLowerCase()))
				{
					candidates.add(f);
				}
			}
			if (candidates.isEmpty())
			{
				return;
			}

			playFile(candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
		}
		catch (Exception e)
		{
			log.debug("Banner sound skipped: {}", e.getMessage());
		}
	}

	private void playFile(Filepath file) throws Exception
	{
		// AudioPlayer takes a Filepath directly as of 1.12.39, so the clip never leaves the sandbox.
		audioPlayer.play(file, gainDb());
	}

	/**
	 * Convert the 0–100 volume config to a MASTER_GAIN decibel value AudioPlayer applies. 100 → 0 dB
	 * (unattenuated); lower volumes attenuate logarithmically; 0 → effectively silent. AudioPlayer
	 * clamps to the line's supported range.
	 */
	private float gainDb()
	{
		int vol = Math.max(0, Math.min(100, config.bannerSoundVolume()));
		return vol <= 0 ? -80f : (float) (20.0 * Math.log10(vol / 100.0));
	}

	/**
	 * Opens a file picker and copies the chosen WAV(s) into the user sounds folder. {@code onImported}
	 * (may be null) is invoked with the successfully-copied filenames so the caller can confirm in chat;
	 * it's skipped when the user cancels or nothing copied.
	 */
	public void importSounds(Kind kind, Consumer<List<String>> onImported)
	{
		SwingUtilities.invokeLater(() ->
		{
			// Filepath.Chooser rather than a bare JFileChooser: the user picking a file is how a
			// plugin is granted anything outside its own directory, and it is the same dialog they
			// were already looking at. Nothing else about this flow changes — the clips are copied
			// in, and playback still only ever reads our own folder.
			List<Filepath> chosen = new Filepath.Chooser()
				.setIsOpen()
				.setAcceptsFiles()
				.setMultiSelectionEnabled(true)
				.setDialogTitle(kind == Kind.MISSION ? "Add mission sounds" : "Add banner sounds")
				.addExtensionFilter("WAV audio", "wav")
				.showDialog((java.awt.Component) null);
			if (chosen == null || chosen.isEmpty())
			{
				return;
			}
			Filepath dir = dir(kind);
			if (dir == null)
			{
				return;
			}
			List<String> imported = new ArrayList<>();
			for (Filepath src : chosen)
			{
				String name = src.getFileName();
				try
				{
					src.copyTo(dir.joinSegment(name), StandardCopyOption.REPLACE_EXISTING);
					imported.add(name);
				}
				catch (Exception e)
				{
					log.warn("Could not import sound {}: {}", name, e.getMessage());
				}
			}
			if (onImported != null && !imported.isEmpty())
			{
				onImported.accept(imported);
			}
		});
	}

	/** Copies the user sounds folder's path — see Clipboards for why it can't be opened directly. */
	public void copyFolderPath()
	{
		Filepath dir = root;
		if (dir == null)
		{
			return;
		}
		// The PARENT, not one kind's folder: both `banner/` and `mission/` are inside it, so one
		// paste lands somewhere the player can see the whole arrangement and drag files between them.
		try
		{
			if (!dir.exists())
			{
				dir.createDirectories();
			}
		}
		catch (IOException e)
		{
			log.debug("Could not create the sounds folder: {}", e.getMessage());
			return;
		}
		// LinkBrowser::open is restricted for hub releases, so the path goes on the clipboard and the
		// player pastes it wherever they were going to open it.
		Clipboards.copy(dir.toString());
	}

	public void shutdown()
	{
		audioExecutor.shutdownNow();
	}
}
