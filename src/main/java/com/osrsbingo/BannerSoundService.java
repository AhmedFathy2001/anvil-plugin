package com.osrsbingo;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Desktop;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Plays a clip when the bingo banner fires. Clips are WAV/PCM (the only format stock Java decodes)
 * and come from two places: bundled clips under {@code resources/com/osrsbingo/sounds/} and user
 * clips in {@code <RuneLite dir>/anvil-bingo-sounds/}.
 */
@Slf4j
@Singleton
public class BannerSoundService
{
	static final String USER_DIR_NAME = "anvil-bingo-sounds";
	private static final String BUNDLED_PREFIX = "/com/osrsbingo/sounds/";

	private static final String[] BUNDLED_CLIPS = {
		"odablock-we-won.wav",
		"c-engineer-completed.wav",
	};

	private final OsrsBingoConfig config;
	private final java.util.concurrent.atomic.AtomicBoolean playing = new java.util.concurrent.atomic.AtomicBoolean(false);
	private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "anvil-banner-sound");
		t.setDaemon(true);
		return t;
	});

	@Inject
	public BannerSoundService(OsrsBingoConfig config)
	{
		this.config = config;
	}

	public static File userDir()
	{
		return new File(RuneLite.RUNELITE_DIR, USER_DIR_NAME);
	}

	public void ensureUserDir()
	{
		File dir = userDir();
		if (!dir.exists())
		{
			//noinspection ResultOfMethodCallIgnored
			dir.mkdirs();
		}
	}

	public void play()
	{
		if (!config.bannerSound())
		{
			return;
		}
		audioExecutor.submit(this::playBlocking);
	}

	private void playBlocking()
	{
		// Skip if a clip is already sounding — back-to-back completions shouldn't stack audio.
		if (playing.get())
		{
			return;
		}
		try
		{
			List<String> userClips = new ArrayList<>();
			File[] files = userDir().listFiles((d, name) -> name.toLowerCase().endsWith(".wav"));
			if (files != null)
			{
				for (File f : files)
				{
					userClips.add(f.getAbsolutePath());
				}
			}

			List<String> bundled = new ArrayList<>();
			for (String name : BUNDLED_CLIPS)
			{
				if (BannerSoundService.class.getResource(BUNDLED_PREFIX + name) != null)
				{
					bundled.add(BUNDLED_PREFIX + name);
				}
			}

			// A named clip overrides random selection; user files win over bundled on a name clash.
			String wanted = config.bannerSoundClip();
			wanted = wanted == null ? "" : wanted.trim();
			if (!wanted.isEmpty())
			{
				if (!wanted.toLowerCase().endsWith(".wav"))
				{
					wanted += ".wav";
				}
				for (String p : userClips)
				{
					if (new File(p).getName().equalsIgnoreCase(wanted))
					{
						playFile(new File(p));
						return;
					}
				}
				for (String r : bundled)
				{
					if (r.substring(r.lastIndexOf('/') + 1).equalsIgnoreCase(wanted))
					{
						playResource(r);
						return;
					}
				}
				return;
			}

			int total = userClips.size() + bundled.size();
			if (total == 0)
			{
				return;
			}

			int pick = ThreadLocalRandom.current().nextInt(total);
			if (pick < userClips.size())
			{
				playFile(new File(userClips.get(pick)));
			}
			else
			{
				playResource(bundled.get(pick - userClips.size()));
			}
		}
		catch (Exception e)
		{
			log.debug("Banner sound skipped: {}", e.getMessage());
		}
	}

	private void playFile(File file) throws Exception
	{
		try (InputStream in = new BufferedInputStream(new FileInputStream(file)))
		{
			play(in);
		}
	}

	private void playResource(String resourcePath) throws Exception
	{
		try (InputStream in = BannerSoundService.class.getResourceAsStream(resourcePath))
		{
			if (in != null)
			{
				play(new BufferedInputStream(in));
			}
		}
	}

	private void play(InputStream in) throws Exception
	{
		try (AudioInputStream ais = AudioSystem.getAudioInputStream(in))
		{
			Clip clip = AudioSystem.getClip();
			clip.open(ais);
			applyVolume(clip);
			clip.addLineListener(event ->
			{
				if (event.getType() == LineEvent.Type.STOP)
				{
					playing.set(false);
					clip.close();
				}
			});
			playing.set(true);
			clip.start();
		}
	}

	private void applyVolume(Clip clip)
	{
		int vol = Math.max(0, Math.min(100, config.bannerSoundVolume()));
		if (!clip.isControlSupported(FloatControl.Type.MASTER_GAIN))
		{
			return;
		}
		FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
		float dB = vol <= 0
			? gain.getMinimum()
			: (float) (20.0 * Math.log10(vol / 100.0));
		gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), dB)));
	}

	/** Opens a file picker and copies the chosen WAV(s) into the user sounds folder. */
	public void importSounds()
	{
		SwingUtilities.invokeLater(() ->
		{
			JFileChooser chooser = new JFileChooser();
			chooser.setDialogTitle("Add banner sounds");
			chooser.setMultiSelectionEnabled(true);
			chooser.setFileFilter(new FileNameExtensionFilter("WAV audio (*.wav)", "wav"));
			if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION)
			{
				return;
			}
			ensureUserDir();
			for (File src : chooser.getSelectedFiles())
			{
				try
				{
					Files.copy(src.toPath(), new File(userDir(), src.getName()).toPath(),
						StandardCopyOption.REPLACE_EXISTING);
				}
				catch (Exception e)
				{
					log.warn("Could not import sound {}: {}", src.getName(), e.getMessage());
				}
			}
		});
	}

	/** Opens the user sounds folder in the OS file manager. */
	public void openFolder()
	{
		ensureUserDir();
		if (!Desktop.isDesktopSupported())
		{
			return;
		}
		audioExecutor.submit(() ->
		{
			try
			{
				Desktop.getDesktop().open(userDir());
			}
			catch (Exception e)
			{
				log.warn("Could not open sounds folder: {}", e.getMessage());
			}
		});
	}

	public void shutdown()
	{
		audioExecutor.shutdownNow();
	}
}
