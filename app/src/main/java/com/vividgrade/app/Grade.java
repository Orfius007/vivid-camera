package com.vividgrade.app;

import java.util.stream.IntStream;

/** Colour grade applied to ARGB pixel arrays. Same maths as the web prototype. */
final class Grade {
    static final String[] NAMES = {"Original", "Natural", "Vivid", "Vivid+", "Warm"};
    // exposure, contrast, highlights, shadows, saturation, vibrance, cool, skin, warmth, tint, vignette
    static final int[][] PRESETS = {
        {0, 0, 0, 0, 0, 0, 0, 60, 0, 0, 0},
        {0, 8, -6, 8, 8, 12, 10, 60, 2, 0, 0},
        {8, 18, -14, 16, 20, 26, 34, 65, 3, 0, 12},
        {10, 26, -18, 20, 32, 36, 50, 70, 3, 0, 18},
        {6, 12, -10, 14, 12, 18, 6, 85, 16, 2, 20}
    };

    private final float ex, cc, sh, hi, wm, tn, sat, vib, cool, skin, vg;
    private final boolean identity;

    Grade(int preset, float strength) {
        int[] v = PRESETS[preset];
        float[] s = new float[v.length];
        boolean any = false;
        for (int i = 0; i < v.length; i++) {
            s[i] = (i == 7) ? v[i] : v[i] * strength;
            if (i != 7 && v[i] != 0 && strength != 0) any = true;
        }
        identity = !any;
        ex = (float) Math.pow(2, s[0] / 100f);
        cc = 1f + (s[1] / 100f) * 0.9f;
        hi = s[2] / 100f;
        sh = s[3] / 100f;
        sat = s[4] / 100f;
        vib = s[5] / 100f;
        cool = s[6] / 100f;
        skin = s[7] / 100f;
        wm = s[8] / 100f * 0.35f;
        tn = s[9] / 100f * 0.3f;
        vg = s[10] / 100f;
    }

    void apply(int[] px, int w, int h) {
        if (identity) return;
        IntStream.range(0, h).parallel().forEach(y -> row(px, w, h, y));
    }

    private static float cl(float x) {
        return x < 0f ? 0f : (x > 1f ? 1f : x);
    }

    private void row(int[] px, int w, int h, int y) {
        float dy = ((float) y / h - 0.5f) * 2f;
        for (int x = 0; x < w; x++) {
            int i = y * w + x;
            int c = px[i];
            float r = ((c >> 16) & 255) / 255f;
            float g = ((c >> 8) & 255) / 255f;
            float b = (c & 255) / 255f;

            r *= ex; g *= ex; b *= ex;
            float l = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            float lc = cl(l);
            float add = sh * 0.22f * (1f - lc) * (1f - lc) + hi * 0.22f * lc * lc;
            r += add; g += add; b += add;
            r = (r - 0.5f) * cc + 0.5f;
            g = (g - 0.5f) * cc + 0.5f;
            b = (b - 0.5f) * cc + 0.5f;
            r *= 1f + wm; b *= 1f - wm; g *= 1f - tn;
            r = cl(r); g = cl(g); b = cl(b);

            float mx = Math.max(r, Math.max(g, b));
            float mn = Math.min(r, Math.min(g, b));
            float dl = mx - mn;
            float sp = mx > 0f ? dl / mx : 0f;
            float skinW = 0f, bg = 0f;
            if (dl > 0.0001f) {
                float hue;
                if (mx == r) hue = 60f * (((g - b) / dl) % 6f);
                else if (mx == g) hue = 60f * ((b - r) / dl + 2f);
                else hue = 60f * ((r - g) / dl + 4f);
                if (hue < 0f) hue += 360f;
                skinW = Math.max(0f, 1f - Math.abs(hue - 28f) / 26f);
                if (hue > 85f && hue < 265f) {
                    float edge = Math.min(hue - 85f, 265f - hue) / 40f;
                    bg = edge > 1f ? 1f : edge;
                }
            }
            float gain = 1f + sat + vib * (1f - sp) + cool * 0.55f * bg;
            if (gain > 1f) gain = 1f + (gain - 1f) * (1f - skin * skinW * 0.75f);
            float l2 = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            r = l2 + (r - l2) * gain;
            g = l2 + (g - l2) * gain;
            b = l2 + (b - l2) * gain;

            if (vg > 0f) {
                float dx = ((float) x / w - 0.5f) * 2f;
                float d2 = (dx * dx + dy * dy) / 2f;
                float v = 1f - vg * 0.55f * d2 * (float) Math.sqrt(d2);
                r *= v; g *= v; b *= v;
            }
            int ri = (int) (cl(r) * 255f + 0.5f);
            int gi = (int) (cl(g) * 255f + 0.5f);
            int bi = (int) (cl(b) * 255f + 0.5f);
            px[i] = 0xFF000000 | (ri << 16) | (gi << 8) | bi;
        }
    }
}
