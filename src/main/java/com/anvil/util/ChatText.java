package com.anvil.util;

import java.util.regex.Pattern;

/**
 * Chat styling, removed — in both of the forms the game uses.
 *
 * <p>RuneLite's {@code <col=…>} / {@code <img=…>} markup is the familiar one. The other is Jagex's
 * older {@code @tag@} colour codes, which now include named ones the game puts inline with the text:
 * a Combat Achievement line arrives as "…combat task: {@code @ach_comp@}Phantom Muspah
 * Speed-Chaser." Stripping only the angle-bracket form left that code glued to the front of the task
 * name, so it reached the tile matcher, the Discord title and the wiki link built from it.</p>
 *
 * <p><b>Deliberately narrow.</b> A code is short and alphanumeric, so an {@code @} in ordinary text
 * needs a second one close behind it to be touched at all — and none of the lines parsed here (kill
 * counts, personal bests, diaries, quests, collection-log unlocks) can carry an {@code @} in a
 * name.</p>
 */
public final class ChatText
{
    private ChatText()
    {
    }

    public static final Pattern CHAT_TAG = Pattern.compile("<[^>]*>|@[A-Za-z0-9_]{1,20}@");

    /** A chat line with its styling removed, ready to parse. Null-safe: an absent line is "". */
    public static String strip(String msg)
    {
        return msg == null ? "" : CHAT_TAG.matcher(msg).replaceAll("").trim();
    }
}
