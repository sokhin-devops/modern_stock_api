package com.modernstock.business;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.modernstock.common.ApiException;

/**
 * The business color (FR-6.26): one of the five tested palettes, or the Owner's own color as #rrggbb. An own color is
 * darkened until white text on it is readable (4.5:1), and refused when it is red-like (red means a problem in the app)
 * or nearly gray (it would not read as a color). The web app runs the same check (shared/colors.ts) before saving.
 */
public final class ThemeColors {

    public static final Set<String> PALETTES = Set.of("blue", "indigo", "purple", "teal", "green");
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final double MIN_CONTRAST = 4.5;
    /** HSL saturation under this is "nearly gray". */
    private static final double MIN_SATURATION = 0.25;

    private ThemeColors() {
    }

    /** The color to store: a palette name as it is, an own color checked and darkened; null for the default. */
    public static String normalize(String color) {
        if (color == null || color.isBlank()) {
            return null;
        }
        if (PALETTES.contains(color)) {
            return color;
        }
        if (!HEX.matcher(color).matches()) {
            throw ApiException.badRequest("THEME_COLOR_INVALID");
        }
        int[] rgb = rgb(color);
        double[] hsl = hsl(rgb);
        if (hsl[1] < MIN_SATURATION) {
            throw ApiException.rule("THEME_COLOR_GRAY");
        }
        if (hsl[0] < 20 || hsl[0] >= 335) {
            throw ApiException.rule("THEME_COLOR_RED");
        }
        // Darker in 5% steps until white text passes 4.5:1
        for (int i = 0; i < 60 && contrastWithWhite(rgb) < MIN_CONTRAST; i++) {
            rgb = new int[] {(int) Math.round(rgb[0] * 0.95), (int) Math.round(rgb[1] * 0.95), (int) Math.round(rgb[2] * 0.95)};
        }
        return String.format(Locale.ROOT, "#%02x%02x%02x", rgb[0], rgb[1], rgb[2]);
    }

    private static int[] rgb(String hex) {
        int v = Integer.parseInt(hex.substring(1), 16);
        return new int[] {(v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff};
    }

    /** Hue in degrees, saturation and lightness 0..1. */
    private static double[] hsl(int[] rgb) {
        double r = rgb[0] / 255.0;
        double g = rgb[1] / 255.0;
        double b = rgb[2] / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double l = (max + min) / 2;
        double d = max - min;
        if (d == 0) {
            return new double[] {0, 0, l};
        }
        double s = d / (1 - Math.abs(2 * l - 1));
        double h = max == r ? ((g - b) / d) % 6 : max == g ? (b - r) / d + 2 : (r - g) / d + 4;
        h *= 60;
        return new double[] {h < 0 ? h + 360 : h, s, l};
    }

    private static double contrastWithWhite(int[] rgb) {
        return 1.05 / (luminance(rgb) + 0.05);
    }

    private static double luminance(int[] rgb) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = rgb[i] / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }
}
