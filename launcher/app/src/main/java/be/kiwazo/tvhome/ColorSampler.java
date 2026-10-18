package be.kiwazo.tvhome;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Picks the most prominent vivid hue from a banner, for the ambient glow. */
final class ColorSampler {

    /** Warm amber, used when a banner is all black/white/grey. */
    static final int FALLBACK = 0xFFFFB547;

    private static final int W = 32, H = 18;

    private ColorSampler() {}

    static int vivid(Drawable d) {
        if (d == null) return FALLBACK;
        Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Rect old = d.copyBounds();
        d.setBounds(0, 0, W, H);
        d.draw(new Canvas(bm));
        d.setBounds(old);
        int[] px = new int[W * H];
        bm.getPixels(px, 0, W, 0, 0, W, H);
        bm.recycle();

        // Bucket pixels into 12 hue bins, weighted towards saturated, bright pixels.
        float[] hsv = new float[3];
        double[] weight = new double[12];
        double[][] rgb = new double[12][3];
        for (int p : px) {
            if (Color.alpha(p) < 128) continue;
            Color.colorToHSV(p, hsv);
            if (hsv[2] < 0.2f || hsv[1] < 0.25f) continue;
            double w = hsv[1] * hsv[2] * hsv[2];
            int bin = ((int) (hsv[0] / 30f)) % 12;
            weight[bin] += w;
            rgb[bin][0] += Color.red(p) * w;
            rgb[bin][1] += Color.green(p) * w;
            rgb[bin][2] += Color.blue(p) * w;
        }
        int best = 0;
        for (int i = 1; i < 12; i++) if (weight[i] > weight[best]) best = i;
        if (weight[best] < 4) return FALLBACK;

        int c = Color.rgb((int) (rgb[best][0] / weight[best]),
                (int) (rgb[best][1] / weight[best]),
                (int) (rgb[best][2] / weight[best]));
        Color.colorToHSV(c, hsv);
        hsv[1] = Math.max(hsv[1], 0.6f);
        hsv[2] = Math.max(hsv[2], 0.9f);
        return Color.HSVToColor(hsv);
    }
}
