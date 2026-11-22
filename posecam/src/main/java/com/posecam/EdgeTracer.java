package com.posecam;

import android.graphics.Bitmap;

/** Turns a photo into a line drawing (white edges on transparent) so the pose is easy to see over the camera. */
final class EdgeTracer {

    private static final int MAX_SIDE = 720;

    private EdgeTracer() {}

    static Bitmap trace(Bitmap src) {
        float s = Math.min(1f, MAX_SIDE / (float) Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(3, Math.round(src.getWidth() * s));
        int h = Math.max(3, Math.round(src.getHeight() * s));
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);

        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        if (small != src) small.recycle();

        // Grayscale
        float[] g = new float[w * h];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            g[i] = 0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF);
        }

        // 3x3 blur to drop noise and skin texture
        float[] b = new float[w * h];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                b[i] = (g[i - w - 1] + 2 * g[i - w] + g[i - w + 1]
                        + 2 * g[i - 1] + 4 * g[i] + 2 * g[i + 1]
                        + g[i + w - 1] + 2 * g[i + w] + g[i + w + 1]) / 16f;
            }
        }

        // Sobel magnitude
        float[] m = new float[w * h];
        int[] hist = new int[256];
        for (int y = 2; y < h - 2; y++) {
            for (int x = 2; x < w - 2; x++) {
                int i = y * w + x;
                float gx = -b[i - w - 1] - 2 * b[i - 1] - b[i + w - 1] + b[i - w + 1] + 2 * b[i + 1] + b[i + w + 1];
                float gy = -b[i - w - 1] - 2 * b[i - w] - b[i - w + 1] + b[i + w - 1] + 2 * b[i + w] + b[i + w + 1];
                float v = (float) Math.sqrt(gx * gx + gy * gy);
                m[i] = v;
                hist[Math.min(255, (int) (v / 4f))]++;
            }
        }

        // Keep roughly the strongest 12% of edges; fade in over a soft band so lines aren't jagged.
        int total = (w - 4) * (h - 4), acc = 0, bin = 255;
        for (; bin > 0; bin--) {
            acc += hist[bin];
            if (acc > total * 0.12f) break;
        }
        float lo = Math.max(24f, bin * 4f);
        float hi = lo * 2.2f;

        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            float t = (m[i] - lo) / (hi - lo);
            if (t <= 0) continue;
            int a = t >= 1 ? 255 : (int) (t * 255);
            out[i] = (a << 24) | 0xFFFFFF;
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
    }
}
