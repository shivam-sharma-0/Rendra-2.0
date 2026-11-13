package com.code.pal.agent.contract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DesignPresets {
    private static final String SANS = "Inter, ui-sans-serif, system-ui, -apple-system, \"Segoe UI\", Roboto, sans-serif";
    private static final String SERIF = "ui-serif, Georgia, Cambria, \"Times New Roman\", serif";
    private static final String ROUNDED = "ui-rounded, \"SF Pro Rounded\", Nunito, " + SANS;
    private static final String DARK_SHADOW = "0 1px 0 rgb(255 255 255 / 4%) inset, 0 10px 30px rgb(0 0 0 / 40%)";
    private static final String DARK_SHADOW_LG = "0 1px 0 rgb(255 255 255 / 6%) inset, 0 18px 48px rgb(0 0 0 / 55%)";
    private static final String LIGHT_SHADOW = "0 1px 2px rgb(15 23 42 / 6%), 0 8px 24px rgb(15 23 42 / 6%)";
    private static final String LIGHT_SHADOW_LG = "0 2px 4px rgb(15 23 42 / 6%), 0 18px 40px rgb(15 23 42 / 10%)";
    private static final String DARK_GLOW = "radial-gradient(1100px 560px at 12% -12%, color-mix(in srgb, var(--app-accent) 20%, transparent), transparent 62%), "
            + "radial-gradient(900px 480px at 100% 0%, color-mix(in srgb, var(--app-accent) 10%, transparent), transparent 60%)";
    private static final String LIGHT_GLOW = "radial-gradient(1000px 480px at 50% -18%, color-mix(in srgb, var(--app-accent) 9%, transparent), transparent 70%)";
    private static final Map<String, Map<String, String>> PRESETS = new LinkedHashMap<>();

    static {
        preset("midnight", true, "#07080f", "#10131d", "#171b29", "#eef0fb", "#a3a9c2", "#262c40", "#8b5cf6", "16px", SANS, DARK_GLOW);
        preset("graphite", true, "#0c0f14", "#141922", "#1b212c", "#e7eaf0", "#9aa4b2", "#262d39", "#2dd4bf", "12px", SANS,
                "radial-gradient(900px 420px at 0% -10%, color-mix(in srgb, var(--app-accent) 10%, transparent), transparent 60%)");
        preset("clean", false, "#f6f7fb", "#ffffff", "#f1f3f9", "#0f172a", "#5b6578", "#e2e6ef", "#4f46e5", "14px", SANS, LIGHT_GLOW);
        preset("corporate", false, "#f3f5f8", "#ffffff", "#eef2f7", "#0b1324", "#556275", "#d9e0ea", "#1d4ed8", "8px", SANS, "none");
        preset("editorial", false, "#faf7f2", "#fffdf9", "#f4eee5", "#1f1a14", "#6d6358", "#e7ddcf", "#b4532a", "10px", SERIF,
                "radial-gradient(900px 420px at 85% -15%, color-mix(in srgb, var(--app-accent) 8%, transparent), transparent 65%)");
        preset("playful", false, "#fff7f0", "#ffffff", "#fff0e3", "#1e1b2e", "#625c76", "#f1e1d3", "#e11d48", "22px", ROUNDED,
                "radial-gradient(800px 420px at 0% -10%, color-mix(in srgb, var(--app-accent) 12%, transparent), transparent 60%), "
                        + "radial-gradient(700px 380px at 100% 0%, rgb(250 204 21 / 14%), transparent 60%)");
    }

    static final List<String> NAMES = List.copyOf(PRESETS.keySet());
    private static final Map<String, String> ALIASES = Map.ofEntries(Map.entry("dark", "midnight"), Map.entry("neon", "midnight"),
            Map.entry("gaming", "midnight"), Map.entry("game", "midnight"), Map.entry("night", "midnight"),
            Map.entry("slate", "graphite"), Map.entry("developer", "graphite"), Map.entry("tech", "graphite"),
            Map.entry("light", "clean"), Map.entry("saas", "clean"), Map.entry("minimal", "clean"), Map.entry("modern", "clean"),
            Map.entry("business", "corporate"), Map.entry("enterprise", "corporate"), Map.entry("finance", "corporate"),
            Map.entry("professional", "corporate"), Map.entry("warm", "editorial"), Map.entry("blog", "editorial"),
            Map.entry("magazine", "editorial"), Map.entry("fun", "playful"), Map.entry("bright", "playful"),
            Map.entry("kids", "playful"), Map.entry("colorful", "playful"));

    private DesignPresets() {
    }

    private static void preset(String name, boolean dark, String background, String surface, String surface2, String text,
            String muted, String border, String accent, String radius, String display, String glow) {
        var tokens = new LinkedHashMap<String, String>();
        tokens.put("color-scheme", dark ? "dark" : "light");
        tokens.put("--app-background", background);
        tokens.put("--app-surface", surface);
        tokens.put("--app-surface-2", surface2);
        tokens.put("--app-text", text);
        tokens.put("--app-muted", muted);
        tokens.put("--app-border", border);
        tokens.put("--app-accent", accent);
        tokens.put("--app-radius", radius);
        tokens.put("--app-font", SANS);
        tokens.put("--app-font-display", display);
        tokens.put("--app-shadow", dark ? DARK_SHADOW : LIGHT_SHADOW);
        tokens.put("--app-shadow-lg", dark ? DARK_SHADOW_LG : LIGHT_SHADOW_LG);
        tokens.put("--app-glow", glow);
        PRESETS.put(name, tokens);
    }

    static String name(String proposed) {
        if (proposed == null)
            return null;
        String value = proposed.strip().toLowerCase(Locale.ROOT);
        return PRESETS.containsKey(value) ? value : ALIASES.get(value);
    }

    static String guess(String background) {
        int[] color = rgb(background);
        return color != null && luminance(color) < 0.4 ? "midnight" : "clean";
    }

    static void css(String name, String proposedAccent, StringBuilder css) {
        var tokens = new LinkedHashMap<>(PRESETS.get(name));
        int[] surface = rgb(tokens.get("--app-surface"));
        int[] accent = rgb(proposedAccent);
        if (accent != null && contrast(accent, surface) >= 3)
            tokens.put("--app-accent", hex(accent));
        int[] chosen = rgb(tokens.get("--app-accent"));
        tokens.put("--app-on-accent", contrast(chosen, new int[] {255, 255, 255}) >= contrast(chosen, new int[] {17, 24, 39})
                ? "#ffffff" : "#111827");
        tokens.forEach((token, value) -> css.append("  ").append(token).append(": ").append(value).append(";\n"));
        for (var alias : Map.of("background", "background", "foreground", "text", "accent", "accent", "muted", "muted",
                "radius", "radius", "spacing", "space", "font", "font").entrySet().stream().sorted(Map.Entry.comparingByKey()).toList())
            css.append("  --rendra-").append(alias.getKey()).append(": var(--app-").append(alias.getValue()).append(");\n");
    }

    static int[] rgb(String value) {
        if (value == null || !value.matches("#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})"))
            return null;
        String digits = value.length() == 4 ? value.substring(1).replaceAll("(.)", "$1$1") : value.substring(1);
        return new int[] {Integer.parseInt(digits.substring(0, 2), 16), Integer.parseInt(digits.substring(2, 4), 16),
                Integer.parseInt(digits.substring(4, 6), 16)};
    }

    static double luminance(int[] color) {
        double total = 0;
        double[] weights = {0.2126, 0.7152, 0.0722};
        for (int index = 0; index < 3; index++) {
            double channel = color[index] / 255.0;
            total += weights[index] * (channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4));
        }
        return total;
    }

    static double contrast(int[] first, int[] second) {
        double a = luminance(first), b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    static int[] mix(int[] base, int[] other, double amount) {
        return new int[] {(int) Math.round(base[0] + (other[0] - base[0]) * amount),
                (int) Math.round(base[1] + (other[1] - base[1]) * amount), (int) Math.round(base[2] + (other[2] - base[2]) * amount)};
    }

    static String hex(int[] color) {
        return String.format("#%02x%02x%02x", color[0], color[1], color[2]);
    }
}
