package io.github.untalluisnava.nbclaude.terminal;

import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import javax.swing.UIManager;
import javax.swing.text.AttributeSet;
import javax.swing.text.StyleConstants;
import org.netbeans.api.editor.mimelookup.MimeLookup;
import org.netbeans.api.editor.mimelookup.MimePath;
import org.netbeans.api.editor.settings.FontColorNames;
import org.netbeans.api.editor.settings.FontColorSettings;
import org.openide.util.Lookup;
import org.openide.util.LookupListener;

/**
 * JediTerm settings derived from the active NetBeans editor color profile ("Tools > Options > Fonts
 * & Colors"), so the terminal matches the editor: same background, foreground, selection, search
 * highlight and font.
 *
 * <p>The 16-color ANSI palette is built by keeping each ANSI slot's canonical hue (so red still
 * means "removed/error" and green "added/success" to programs like the {@code claude} CLI) but
 * borrowing the editor's own token colors whenever one falls close to that hue, and otherwise
 * tuning lightness for readable contrast against the editor background. Combined with one of Claude
 * Code's "ANSI colors only" themes ({@code /theme}), the CLI ends up drawn in the editor's palette.
 *
 * <p>Colors are re-read whenever the profile changes; {@code onChange} is then run (on whatever
 * thread NetBeans fires the change) so the owner can repaint. The font is only read by JediTerm
 * when the widget is created, so font changes apply to the next terminal session.
 */
public final class NetBeansTerminalSettingsProvider extends DefaultSettingsProvider {

  // Token categories of the "All Languages" profile; every language inherits from these, so they
  // describe the scheme's palette regardless of which languages are installed.
  private static final String[] TOKEN_CATEGORIES = {
    "keyword",
    "string",
    "comment",
    "number",
    "character",
    "error",
    "field",
    "method",
    "operator",
    "identifier",
    "url",
    "mod-annotation",
    "mod-deprecated",
    "mod-static"
  };

  // Canonical hues (degrees) for ANSI red, green, yellow, blue, magenta, cyan.
  private static final float[] ANSI_HUES = {0f, 120f, 55f, 220f, 300f, 185f};

  // How far (degrees) an editor token color may be from a canonical hue and still stand in for it.
  // Kept tight because neighbouring slots matter: an orange keyword color must not become "red".
  private static final float HUE_TOLERANCE = 25f;

  private static final double MIN_CONTRAST = 3.0;

  private final Lookup.Result<FontColorSettings> settingsResult;
  private final LookupListener listener;
  private volatile Snapshot snapshot;

  public NetBeansTerminalSettingsProvider(Runnable onChange) {
    settingsResult = MimeLookup.getLookup(MimePath.EMPTY).lookupResult(FontColorSettings.class);
    snapshot = Snapshot.read(settingsResult);
    listener =
        ev -> {
          snapshot = Snapshot.read(settingsResult);
          onChange.run();
        };
    settingsResult.addLookupListener(listener);
  }

  /** Stops following color profile changes. */
  public void dispose() {
    settingsResult.removeLookupListener(listener);
  }

  @Override
  public ColorPalette getTerminalColorPalette() {
    return snapshot.palette;
  }

  @Override
  public TerminalColor getDefaultForeground() {
    return TerminalColor.fromColor(toJediColor(snapshot.foreground));
  }

  @Override
  public TerminalColor getDefaultBackground() {
    return TerminalColor.fromColor(toJediColor(snapshot.background));
  }

  @Override
  public TextStyle getSelectionColor() {
    Snapshot s = snapshot;
    return style(s.selectionForeground, s.selectionBackground);
  }

  @Override
  public TextStyle getFoundPatternColor() {
    Snapshot s = snapshot;
    return style(s.searchForeground, s.searchBackground);
  }

  @Override
  public TextStyle getHyperlinkColor() {
    Snapshot s = snapshot;
    return style(s.hyperlink, s.background);
  }

  @Override
  public boolean useInverseSelectionColor() {
    return false;
  }

  @Override
  public Font getTerminalFont() {
    Snapshot s = snapshot;
    return new Font(s.fontFamily, Font.PLAIN, Math.round(s.fontSize));
  }

  @Override
  public float getTerminalFontSize() {
    return snapshot.fontSize;
  }

  private static TextStyle style(Color foreground, Color background) {
    return new TextStyle(
        TerminalColor.fromColor(toJediColor(foreground)),
        TerminalColor.fromColor(toJediColor(background)));
  }

  private static com.jediterm.core.Color toJediColor(Color color) {
    return new com.jediterm.core.Color(color.getRed(), color.getGreen(), color.getBlue());
  }

  /** Immutable view of the editor profile, swapped wholesale on every change. */
  private static final class Snapshot {

    final Color foreground;
    final Color background;
    final Color selectionForeground;
    final Color selectionBackground;
    final Color searchForeground;
    final Color searchBackground;
    final Color hyperlink;
    final String fontFamily;
    final float fontSize;
    final ColorPalette palette;

    private Snapshot(FontColorSettings settings) {
      AttributeSet defaults =
          settings == null ? null : settings.getFontColors(FontColorNames.DEFAULT_COLORING);
      foreground =
          colorOr(defaults, StyleConstants.Foreground, uiColor("TextArea.foreground", Color.BLACK));
      background =
          colorOr(defaults, StyleConstants.Background, uiColor("TextArea.background", Color.WHITE));

      AttributeSet selection =
          settings == null ? null : settings.getFontColors(FontColorNames.SELECTION_COLORING);
      selectionBackground =
          colorOr(selection, StyleConstants.Background, mix(background, foreground, 0.3));
      selectionForeground = colorOr(selection, StyleConstants.Foreground, foreground);

      AttributeSet search =
          settings == null
              ? null
              : settings.getFontColors(FontColorNames.HIGHLIGHT_SEARCH_COLORING);
      searchBackground =
          colorOr(search, StyleConstants.Background, mix(background, Color.YELLOW, 0.5));
      searchForeground = colorOr(search, StyleConstants.Foreground, foreground);

      Font fallbackFont = new Font(Font.MONOSPACED, Font.PLAIN, 13);
      fontFamily =
          defaults != null && defaults.getAttribute(StyleConstants.FontFamily) instanceof String f
              ? f
              : fallbackFont.getFamily();
      fontSize =
          defaults != null && defaults.getAttribute(StyleConstants.FontSize) instanceof Integer i
              ? i
              : fallbackFont.getSize();

      List<Color> tokenColors = new ArrayList<>();
      if (settings != null) {
        for (String category : TOKEN_CATEGORIES) {
          AttributeSet token = settings.getTokenFontColors(category);
          if (token != null && token.getAttribute(StyleConstants.Foreground) instanceof Color c) {
            tokenColors.add(c);
          }
        }
      }
      Color[] colors = buildAnsiPalette(foreground, background, tokenColors);
      hyperlink = colors[4];
      palette = new FixedPalette(colors);
    }

    static Snapshot read(Lookup.Result<FontColorSettings> result) {
      FontColorSettings settings = result.allInstances().stream().findFirst().orElse(null);
      return new Snapshot(settings);
    }
  }

  /**
   * Builds the 16 ANSI colors: 0-7 normal, 8-15 bright. Black/white (and their bright variants) are
   * blends of the editor background and foreground; the six chromatic slots come from {@link
   * #pickChromatic}.
   */
  static Color[] buildAnsiPalette(Color foreground, Color background, List<Color> tokenColors) {
    boolean dark = luminance(background) < 0.5;
    Color[] colors = new Color[16];

    colors[0] = dark ? mix(background, foreground, 0.12) : mix(foreground, background, 0.05);
    colors[7] = dark ? mix(background, foreground, 0.80) : mix(foreground, background, 0.75);
    colors[8] = mix(background, foreground, dark ? 0.45 : 0.55);
    colors[15] = dark ? brighten(foreground, 0.25) : mix(foreground, background, 0.92);

    Color[] chromatic = matchTokenColors(tokenColors);
    for (int i = 0; i < ANSI_HUES.length; i++) {
      Color base = chromatic[i];
      if (base == null) {
        base = Color.getHSBColor(ANSI_HUES[i] / 360f, dark ? 0.55f : 0.85f, dark ? 0.85f : 0.65f);
      }
      Color normal = ensureContrast(base, background, MIN_CONTRAST);
      colors[1 + i] = normal;
      colors[9 + i] =
          ensureContrast(brighten(normal, dark ? 0.3 : 0.2), background, MIN_CONTRAST - 0.5);
    }
    return colors;
  }

  /**
   * Assigns editor token colors to the six chromatic ANSI slots, closest hue match first, using
   * each token color for at most one slot (so e.g. blue and cyan never collapse into the same
   * color). Slots with no token color within {@link #HUE_TOLERANCE} are left {@code null}.
   */
  private static Color[] matchTokenColors(List<Color> tokenColors) {
    record Match(int slot, Color color, float distance) {}
    List<Match> matches = new ArrayList<>();
    for (Color candidate : tokenColors) {
      float[] hsb =
          Color.RGBtoHSB(candidate.getRed(), candidate.getGreen(), candidate.getBlue(), null);
      if (hsb[1] < 0.25f) {
        continue; // greys (comments, plain identifiers) say nothing about a hue
      }
      for (int slot = 0; slot < ANSI_HUES.length; slot++) {
        float distance = hueDistance(hsb[0] * 360f, ANSI_HUES[slot]);
        if (distance < HUE_TOLERANCE) {
          matches.add(new Match(slot, candidate, distance));
        }
      }
    }
    matches.sort((a, b) -> Float.compare(a.distance(), b.distance()));
    Color[] assigned = new Color[ANSI_HUES.length];
    List<Color> used = new ArrayList<>();
    for (Match match : matches) {
      if (assigned[match.slot()] == null && !used.contains(match.color())) {
        assigned[match.slot()] = match.color();
        used.add(match.color());
      }
    }
    return assigned;
  }

  private static float hueDistance(float a, float b) {
    float d = Math.abs(a - b) % 360f;
    return d > 180f ? 360f - d : d;
  }

  /** Moves {@code color}'s brightness away from the background until the WCAG ratio is met. */
  private static Color ensureContrast(Color color, Color background, double minRatio) {
    boolean darkBackground = luminance(background) < 0.5;
    float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
    Color current = color;
    for (int step = 0; step < 20 && contrast(current, background) < minRatio; step++) {
      if (darkBackground) {
        hsb[2] = Math.min(1f, hsb[2] + 0.05f);
        hsb[1] = Math.max(0f, hsb[1] - (hsb[2] >= 1f ? 0.05f : 0f));
      } else {
        hsb[2] = Math.max(0f, hsb[2] - 0.05f);
      }
      current = Color.getHSBColor(hsb[0], hsb[1], hsb[2]);
    }
    return current;
  }

  private static double contrast(Color a, Color b) {
    double la = luminance(a);
    double lb = luminance(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
  }

  private static double luminance(Color c) {
    return 0.2126 * channel(c.getRed())
        + 0.7152 * channel(c.getGreen())
        + 0.0722 * channel(c.getBlue());
  }

  private static double channel(int value) {
    double v = value / 255.0;
    return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
  }

  private static Color mix(Color from, Color to, double amount) {
    return new Color(
        (int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * amount),
        (int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * amount),
        (int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * amount));
  }

  private static Color brighten(Color color, double amount) {
    return mix(color, Color.WHITE, amount);
  }

  private static Color colorOr(AttributeSet attributes, Object key, Color fallback) {
    return attributes != null && attributes.getAttribute(key) instanceof Color c ? c : fallback;
  }

  private static Color uiColor(String key, Color fallback) {
    Color color = UIManager.getColor(key);
    return color != null ? color : fallback;
  }

  private static final class FixedPalette extends ColorPalette {

    private final com.jediterm.core.Color[] colors;

    FixedPalette(Color[] awtColors) {
      colors = new com.jediterm.core.Color[awtColors.length];
      for (int i = 0; i < awtColors.length; i++) {
        colors[i] = toJediColor(awtColors[i]);
      }
    }

    @Override
    protected com.jediterm.core.Color getForegroundByColorIndex(int index) {
      return colors[index];
    }

    @Override
    protected com.jediterm.core.Color getBackgroundByColorIndex(int index) {
      return colors[index];
    }
  }
}
