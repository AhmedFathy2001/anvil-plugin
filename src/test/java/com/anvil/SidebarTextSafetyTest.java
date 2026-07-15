package com.anvil;

import javax.swing.plaf.basic.BasicHTML;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * §2/§6 — the Swing plain-text guard for federated strings. A {@link javax.swing.JLabel} renders its text
 * as HTML when it begins with {@code <html}, so a federated clan/tile/activity name from an untrusted
 * upstream could inject markup. {@link AnvilSidebarPanel#plainText(String)} — through which every federated
 * field the panel renders is routed — must neutralize that so it can only render as literal text.
 *
 * <p>The authoritative check is {@link BasicHTML#isHTMLString(String)}: it is exactly what the label UI
 * uses to decide whether to build an HTML view. If it returns {@code false} for our output, Swing renders
 * the string verbatim — no markup, no injection.</p>
 */
public class SidebarTextSafetyTest
{
	@Test
	public void htmlInjectedNameRendersAsLiteralText()
	{
		String injected = "<html><b>pwned</b><img src=x></html>";
		assertTrue("precondition: the raw string WOULD be treated as HTML by a JLabel",
			BasicHTML.isHTMLString(injected));

		String safe = AnvilSidebarPanel.plainText(injected);
		assertFalse("sanitized federated string must NOT be treated as HTML", BasicHTML.isHTMLString(safe));
		assertFalse("no live '<html' prefix survives", safe.toLowerCase().startsWith("<html"));
		assertTrue("the intended text is still visible (as literal, escaped markup)", safe.contains("pwned"));
	}

	@Test
	public void neutralizesLeadingWhitespaceAndMixedCase()
	{
		// A JLabel trims leading whitespace before its HTML sniff, and the tag match is case-insensitive.
		for (String injected : new String[] { "   <HTML>x</HTML>", "\t<Html><i>y</i>", "<html>" })
		{
			String safe = AnvilSidebarPanel.plainText(injected);
			assertFalse("‘" + injected + "’ must not render as HTML", BasicHTML.isHTMLString(safe));
		}
	}

	@Test
	public void ordinaryStringsPassThroughUnchanged()
	{
		assertEquals("Clan A", AnvilSidebarPanel.plainText("Clan A"));
		assertEquals("Any barrows item", AnvilSidebarPanel.plainText("Any barrows item"));
		assertEquals("You + Kayle", AnvilSidebarPanel.plainText("You + Kayle"));
		// A stray '<' that isn't the HTML trigger is left alone (JLabel wouldn't treat it as HTML either).
		assertEquals("<3 clan", AnvilSidebarPanel.plainText("<3 clan"));
		assertFalse(BasicHTML.isHTMLString(AnvilSidebarPanel.plainText("<3 clan")));
	}

	@Test
	public void nullBecomesEmpty()
	{
		assertEquals("", AnvilSidebarPanel.plainText(null));
	}
}
