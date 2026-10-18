package be.kiwazo.tvhome;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;

/** One launchable TV app. {@code banner} is null for icon-only apps. */
final class AppEntry {
    final String pkg;
    final ComponentName component;
    final String label;
    final Drawable banner;
    final Drawable icon;
    final int color;

    AppEntry(String pkg, ComponentName component, String label, Drawable banner, Drawable icon, int color) {
        this.pkg = pkg;
        this.component = component;
        this.label = label;
        this.banner = banner;
        this.icon = icon;
        this.color = color;
    }
}
