package warne.client.gui.font;

import net.minecraft.util.Identifier;

import java.awt.*;
import java.awt.image.BufferedImage;

/** Kept for compile compatibility only — rendering is handled by FontRenderer via MC TextRenderer. */
class GlyphMap {
    final char fromIncl, toExcl;
    final Font font;
    final Identifier bindToTexture;
    final int pixelPadding;
    int width = 1, height = 1;
    boolean generated = false;

    public GlyphMap(char from, char to, Font font, Identifier identifier, int padding) {
        this.fromIncl = from;
        this.toExcl = to;
        this.font = font;
        this.bindToTexture = identifier;
        this.pixelPadding = padding;
    }

    public Glyph getGlyph(char c) { return null; }
    public void destroy() { generated = false; }
    public boolean contains(char c) { return c >= fromIncl && c < toExcl; }
    public void generate() { generated = true; }

    public static void registerBufferedImageTexture(Identifier i, BufferedImage bi) {}
}
