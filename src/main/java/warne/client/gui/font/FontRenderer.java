package warne.client.gui.font;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import warne.client.features.modules.client.HudEditor;

import java.awt.*;
import java.io.Closeable;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static warne.client.core.manager.IManager.mc;

public class FontRenderer implements Closeable {

    private final float size;

    public FontRenderer(Font font, float sizePx, int charactersPerPage, int paddingBetweenCharacters, @Nullable String prebakeCharacters) {
        this.size = sizePx;
    }

    public FontRenderer(Font font, float sizePx) {
        this.size = sizePx;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private TextRenderer tr() {
        return mc.textRenderer;
    }

    /** Scale factor: our logical size relative to MC default font height (9). */
    private float scale() {
        return size / 9f;
    }

    public static String stripControlCodes(String text) {
        char[] chars = text.toCharArray();
        StringBuilder f = new StringBuilder();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c == '§') { i++; continue; }
            f.append(c);
        }
        return f.toString();
    }

    // ── Draw methods ──────────────────────────────────────────────────────────

    public void drawString(MatrixStack stack, String s, double x, double y, int color) {
        drawString(stack, s, (float) x, (float) y,
                ((color >> 16) & 0xff) / 255f,
                ((color >> 8)  & 0xff) / 255f,
                ((color)       & 0xff) / 255f,
                ((color >> 24) & 0xff) / 255f);
    }

    public void drawString(MatrixStack stack, String s, double x, double y, Color color) {
        drawString(stack, s, (float) x, (float) y,
                color.getRed() / 255f, color.getGreen() / 255f,
                color.getBlue() / 255f, color.getAlpha() / 255f);
    }

    public void drawString(MatrixStack stack, String s, float x, float y, float r, float g, float b, float a) {
        drawString(stack, s, x, y, r, g, b, a, false, 0);
    }

    public void drawString(MatrixStack stack, String s, float x, float y,
                           float r, float g, float b, float a,
                           boolean gradient, int offset) {
        if (s == null || s.isEmpty()) return;

        int argb = toARGB(r, g, b, a);

        stack.push();
        stack.translate(x, y, 0);
        float sc = scale();
        stack.scale(sc, sc, 1f);

        if (gradient) {
            // Draw char by char with gradient colour
            float xOff = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                Color col = HudEditor.getColor(i * offset);
                int gradArgb = toARGB(col.getRed() / 255f, col.getGreen() / 255f,
                        col.getBlue() / 255f, col.getAlpha() / 255f);
                tr().draw(String.valueOf(c), xOff, 0, gradArgb, false,
                        stack.peek().getPositionMatrix(),
                        mc.getBufferBuilders().getEntityVertexConsumers(),
                        TextRenderer.TextLayerType.NORMAL, 0, 0xF000F0);
                xOff += tr().getWidth(String.valueOf(c));
            }
            mc.getBufferBuilders().getEntityVertexConsumers().draw();
        } else {
            tr().draw(s, 0, 0, argb, false,
                    stack.peek().getPositionMatrix(),
                    mc.getBufferBuilders().getEntityVertexConsumers(),
                    TextRenderer.TextLayerType.NORMAL, 0, 0xF000F0);
            mc.getBufferBuilders().getEntityVertexConsumers().draw();
        }

        stack.pop();
    }

    // ── Centered variants ─────────────────────────────────────────────────────

    public void drawCenteredString(MatrixStack stack, String s, double x, double y, int color) {
        drawString(stack, s, (float)(x - getStringWidth(s) / 2f), (float) y, color);
    }

    public void drawCenteredString(MatrixStack stack, String s, double x, double y, Color color) {
        drawString(stack, s, (float)(x - getStringWidth(s) / 2f), (float) y, color);
    }

    public void drawCenteredString(MatrixStack stack, String s, float x, float y,
                                   float r, float g, float b, float a) {
        drawString(stack, s, x - getStringWidth(s) / 2f, y, r, g, b, a);
    }

    public void drawGradientString(MatrixStack stack, String s, float x, float y, int offset) {
        drawString(stack, s, x, y, 1f, 1f, 1f, 1f, true, offset);
    }

    public void drawGradientCenteredString(MatrixStack matrices, String s, float x, float y, int i) {
        drawGradientString(matrices, s, x - getStringWidth(s) / 2f, y, i);
    }

    // ── Metrics ───────────────────────────────────────────────────────────────

    public float getStringWidth(String text) {
        if (text == null || text.isEmpty()) return 0f;
        return tr().getWidth(stripControlCodes(text)) * scale();
    }

    public float getStringHeight(String text) {
        if (text == null || text.isEmpty()) return tr().fontHeight * scale();
        long lines = text.chars().filter(c -> c == '\n').count() + 1;
        return lines * tr().fontHeight * scale();
    }

    public float getFontHeight(String str) {
        return getStringHeight(str);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void close() {
        // nothing to release
    }

    // ── Utilities kept for compatibility ──────────────────────────────────────

    @Contract(value = "-> new", pure = true)
    public static @NotNull Identifier randomIdentifier() {
        return Identifier.of("warneclient", "temp/" + randomString());
    }

    private static String randomString() {
        return IntStream.range(0, 32)
                .mapToObj(i -> String.valueOf((char) new Random().nextInt('a', 'z' + 1)))
                .collect(Collectors.joining());
    }

    @Contract(value = "_ -> new", pure = true)
    public static int @NotNull [] RGBIntToRGB(int in) {
        return new int[]{(in >> 16) & 0xFF, (in >> 8) & 0xFF, in & 0xFF};
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static int toARGB(float r, float g, float b, float a) {
        int ai = (int)(a * 255) & 0xFF;
        int ri = (int)(r * 255) & 0xFF;
        int gi = (int)(g * 255) & 0xFF;
        int bi = (int)(b * 255) & 0xFF;
        // alpha=0 → fully transparent; treat 0 alpha as opaque for legacy callers
        if (ai == 0) ai = 255;
        return (ai << 24) | (ri << 16) | (gi << 8) | bi;
    }
}
