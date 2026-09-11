package com.anvil.util;

import java.awt.Color;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;

/**
 * Everything Anvil says in the chatbox, said the same way.
 *
 * <p>A gold {@code [Anvil]} then the line in white, so a member can tell our messages from the
 * game's at a glance and from another plugin's at all.</p>
 *
 * <p><b>Why a class for one method.</b> Ninety-odd call sites reach this, spread across every part
 * of the plugin — and the parts are being pulled out into collaborators of their own. A collaborator
 * can take this in its constructor; it cannot reach a private method on the plugin.</p>
 *
 * <p><b>Why RuneLite's queue rather than {@code client.addChatMessage}.</b> It is the hub-idiomatic
 * path, and it is safe to call from any thread — which matters here, because these messages come
 * from the network thread as often as from the client thread, and every one of them used to have to
 * remember to hop. Timing is unchanged: the queue drains from the client tick, which is where
 * {@code clientThread.invokeLater} would have run it anyway.</p>
 *
 * <p>The rendered line is byte-for-byte what the hand-built one was.
 * {@link ChatMessageBuilder#append(Color, String)} wraps in a raw {@code <col=rrggbb>} tag with the
 * colour lower-cased to six hex digits, and does not escape the text — matching the old string
 * interpolation exactly. Note that the plain {@code append(String)} overload does NOT match: it runs
 * the text through Jagex escaping, which would turn a {@code <} in an event name into a bracket. The
 * message body must keep going through the colour overload.</p>
 */
@Singleton
public class AnvilChat
{
	/** Gold, for the tag. */
	private static final Color PREFIX = new Color(0xFF_D7_00);

	/** White, for what we actually came to say. */
	private static final Color BODY = new Color(0xFF_FF_FF);

	private final ChatMessageManager chatMessageManager;

	@Inject
	AnvilChat(ChatMessageManager chatMessageManager)
	{
		this.chatMessageManager = chatMessageManager;
	}

	/** Say one line in the chatbox. Safe from any thread. */
	public void send(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(line(message))
			.build());
	}

	/**
	 * The chatbox line for a message — the whole of what a member sees.
	 *
	 * <p>Package-private so a test can hold it to the byte. This used to be hand-built string
	 * concatenation and the rendered result has to stay exactly what it was.</p>
	 */
	static String line(String message)
	{
		return new ChatMessageBuilder()
			.append(PREFIX, "[Anvil]")
			.append(" ")
			.append(BODY, pipeSafe(message))
			.build();
	}

	/**
	 * A raw {@code '|'} gets mangled by the chat pipeline — an event named
	 * "The AFK Spot | July Bingo" printed as a bare "July Bingo." The names we interpolate are
	 * admin-authored and do contain them, so swap in the visually identical broken bar.
	 */
	private static String pipeSafe(String message)
	{
		return message.replace('|', '¦');
	}
}
