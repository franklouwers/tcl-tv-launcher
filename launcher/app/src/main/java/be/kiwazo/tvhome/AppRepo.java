package be.kiwazo.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads the TV apps (LEANBACK_LAUNCHER activities) and splits them into the
 * dock and the grid. Dock order and hidden apps come from res/values/dock.xml,
 * or from dock.txt / hidden.txt in the app's external files dir if present:
 *   /sdcard/Android/data/be.kiwazo.tvhome/files/dock.txt
 */
final class AppRepo {

    static final class Snapshot {
        final List<AppEntry> dock;
        final List<AppEntry> grid;

        Snapshot(List<AppEntry> dock, List<AppEntry> grid) {
            this.dock = dock;
            this.grid = grid;
        }
    }

    private AppRepo() {}

    static Snapshot load(Context ctx, int dockSlots) {
        PackageManager pm = ctx.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, AppEntry> byPkg = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            String pkg = ai.packageName;
            if (pkg.equals(ctx.getPackageName()) || byPkg.containsKey(pkg)) continue;
            Drawable banner = ai.loadBanner(pm);
            Drawable icon = banner == null ? ri.loadIcon(pm) : null;
            String label = String.valueOf(ri.loadLabel(pm)).trim();
            int color = ColorSampler.vivid(banner != null ? banner : icon);
            byPkg.put(pkg, new AppEntry(pkg, new ComponentName(pkg, ai.name), label, banner, icon, color));
        }

        List<AppEntry> dock = new ArrayList<>();
        for (String p : readList(ctx, "dock.txt", R.array.dock_packages)) {
            AppEntry e = byPkg.get(p);
            if (e != null && !dock.contains(e) && dock.size() < dockSlots) dock.add(e);
        }

        Set<String> hidden = new HashSet<>(readList(ctx, "hidden.txt", R.array.hidden_packages));
        List<AppEntry> grid = new ArrayList<>();
        for (AppEntry e : byPkg.values()) {
            if (!dock.contains(e) && !hidden.contains(e.pkg)) grid.add(e);
        }
        Collator collator = Collator.getInstance();
        grid.sort((a, b) -> collator.compare(a.label, b.label));
        return new Snapshot(dock, grid);
    }

    /** Signature of the override files, so we can reload when they change. */
    static long overridesStamp(Context ctx) {
        long s = 17;
        for (String name : new String[]{"dock.txt", "hidden.txt"}) {
            File f = new File(ctx.getExternalFilesDir(null), name);
            s = s * 31 + (f.isFile() ? f.lastModified() ^ f.length() : 0);
        }
        return s;
    }

    private static List<String> readList(Context ctx, String fileName, int fallbackRes) {
        File f = new File(ctx.getExternalFilesDir(null), fileName);
        if (f.isFile()) {
            List<String> out = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) out.add(line);
                }
                return out;
            } catch (IOException ignored) {
                // Fall through to the built-in list.
            }
        }
        return Arrays.asList(ctx.getResources().getStringArray(fallbackRes));
    }
}
