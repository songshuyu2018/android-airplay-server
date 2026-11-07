package io.github.jqssun.airplay.audio;

/** Album-derived light surfaces and independently contrast-checked foreground roles. */
public final class PlayerPalette {
    public final int base, glow, secondaryGlow, ink, body, accent;
    private PlayerPalette(int seed, int secondary) {
        base = mix(seed, 0xffffffff, .88);
        glow = mix(seed, 0xffffffff, .68);
        secondaryGlow = mix(secondary, 0xffffffff, .72);
        int[] backgrounds = {base, glow, secondaryGlow};
        ink = ensureContrast(mix(seed, 0xff000000, .58), backgrounds, 8);
        body = ensureContrast(mix(seed, 0xff000000, .30), backgrounds, 5);
        accent = ensureContrast(seed, backgrounds, 4);
    }
    public static PlayerPalette fromPixels(int[] pixels) {
        // Hue and brightness buckets keep white borders from washing out an album's colors.
        double[][] sums = new double[100][5];
        for (int p : pixels) {
            if ((p >>> 24) < 128) continue;
            double r = (p >> 16 & 255) / 255., g = (p >> 8 & 255) / 255., b = (p & 255) / 255.;
            double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            double delta = max - min, saturation = max == 0 ? 0 : delta / max;
            double hue = 0;
            if (delta > .001) {
                if (max == r) hue = ((g - b) / delta + 6) % 6;
                else if (max == g) hue = (b - r) / delta + 2;
                else hue = (r - g) / delta + 4;
            }
            int bucket = saturation < .08 ? 96 + Math.min(3, (int)(max * 4)) : Math.min(23, (int)(hue * 4)) * 4 + Math.min(3, (int)(max * 4));
            double weight = (.15 + saturation * .85) * (max > .93 ? .25 : 1);
            double[] s = sums[bucket]; s[0] += r * weight; s[1] += g * weight; s[2] += b * weight; s[3] += weight; s[4]++;
        }
        int first = -1;
        double score = 0;
        for (int i = 0; i < sums.length; i++) {
            double candidate = sums[i][3] / Math.pow(Math.max(1, sums[i][4]), .2);
            if (candidate > score) {score = candidate; first = i;}
        }
        if (first < 0) return new PlayerPalette(0xff8b5f83, 0xff776d99);
        int second = first; score = 0;
        for (int i = 0; i < 96; i++) {
            int distance = Math.abs(i / 4 - first / 4);
            if (first >= 96 || Math.min(distance, 24 - distance) < 3) continue;
            double candidate = sums[i][3] / Math.pow(Math.max(1, sums[i][4]), .2);
            if (candidate > score) {score = candidate; second = i;}
        }
        return new PlayerPalette(average(sums[first]), average(sums[second]));
    }
    private static int average(double[] s) {
        return rgb((int)Math.round(s[0] / s[3] * 255), (int)Math.round(s[1] / s[3] * 255), (int)Math.round(s[2] / s[3] * 255));
    }
    private static int rgb(int r, int g, int b) {return 0xff000000 | r << 16 | g << 8 | b;}
    private static int mix(int a, int b, double amount) {
        return rgb((int)Math.round((a >> 16 & 255) * (1 - amount) + (b >> 16 & 255) * amount),
            (int)Math.round((a >> 8 & 255) * (1 - amount) + (b >> 8 & 255) * amount),
            (int)Math.round((a & 255) * (1 - amount) + (b & 255) * amount));
    }
    private static int ensureContrast(int foreground, int[] backgrounds, double minimum) {
        for (int step = 0; step <= 100; step++) {
            int candidate = mix(foreground, 0xff000000, step / 100.);
            boolean passes = true;
            for (int bg : backgrounds) if (contrast(candidate, bg) < minimum) {passes = false; break;}
            if (passes) return candidate;
        }
        return 0xff000000;
    }
    public static double luminance(int color) {
        return .2126 * linear(color >> 16 & 255) + .7152 * linear(color >> 8 & 255) + .0722 * linear(color & 255);
    }
    private static double linear(int value) {
        double x = value / 255.; return x <= .04045 ? x / 12.92 : Math.pow((x + .055) / 1.055, 2.4);
    }
    public static double contrast(int a, int b) {
        double x = luminance(a), y = luminance(b); return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }
}
