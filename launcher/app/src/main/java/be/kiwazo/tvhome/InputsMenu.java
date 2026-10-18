package be.kiwazo.tvhome;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HDMI ports, AV and Live TV from the TV input framework.
 *
 * Each row can carry a description ("PlayStation 5"), first match wins:
 *  1. files/inputs.txt on the TV, lines like "HDMI 1 = PlayStation 5"
 *  2. the name an HDMI-CEC device announces on that port
 *  3. a name you gave the port in the TV's input settings
 *     (TCL's default "Input Source N" labels are ignored)
 */
final class InputsMenu {

    static final class Item {
        final String id;
        final String name;
        final String detail;
        final int icon;
        final boolean connected;

        Item(String id, String name, String detail, int icon, boolean connected) {
            this.id = id;
            this.name = name;
            this.detail = detail;
            this.icon = icon;
            this.connected = connected;
        }
    }

    private static final String TAG = "TvHome";
    private static final Pattern TRAILING_NUMBER = Pattern.compile("(\\d+)$");
    private static final Pattern GENERIC_LABEL = Pattern.compile("(?i)^input source \\d+$");

    private InputsMenu() {}

    static List<Item> load(Context ctx) {
        List<Item> out = new ArrayList<>();
        TvInputManager tim = ctx.getSystemService(TvInputManager.class);
        if (tim == null) return out;
        Map<String, String> names = readNames(new File(ctx.getExternalFilesDir(null), "inputs.txt"));

        List<TvInputInfo> all = tim.getTvInputList();
        List<TvInputInfo> hdmi = new ArrayList<>();
        Map<String, String> cecNames = new HashMap<>();
        TvInputInfo av = null, tuner = null;
        for (TvInputInfo in : all) {
            Log.d(TAG, "input " + in.getId() + " type=" + in.getType() + " parent=" + in.getParentId()
                    + " label=" + in.loadLabel(ctx) + " custom=" + in.loadCustomLabel(ctx));
            if (in.getParentId() != null) {
                // An HDMI-CEC device behind a port: remember its name for the port.
                cecNames.put(in.getParentId(), String.valueOf(in.loadLabel(ctx)));
                continue;
            }
            if (in.isHidden(ctx)) continue;
            // AirPlay registers itself as a tuner-type input; it isn't one.
            if (in.getServiceInfo().packageName.toLowerCase(Locale.ROOT).contains("airplay")) continue;
            switch (in.getType()) {
                case TvInputInfo.TYPE_HDMI:
                    hdmi.add(in);
                    break;
                case TvInputInfo.TYPE_COMPOSITE:
                    if (av == null) av = in;
                    break;
                case TvInputInfo.TYPE_TUNER:
                    if (tuner == null || (!tuner.getId().contains("Tuner") && in.getId().contains("Tuner"))) tuner = in;
                    break;
                default:
                    break;
            }
        }
        // Hardware ids end in a number that follows the physical port order (HW15 = HDMI 1).
        hdmi.sort((a, b) -> Integer.compare(trailingNumber(a.getId()), trailingNumber(b.getId())));
        for (int i = 0; i < hdmi.size(); i++) {
            String name = ctx.getString(R.string.hdmi_n, i + 1);
            out.add(item(ctx, tim, hdmi.get(i), name, "HDMI" + (i + 1), names, cecNames, R.drawable.ic_hdmi));
        }
        if (av != null) {
            out.add(item(ctx, tim, av, ctx.getString(R.string.av), "AV", names, cecNames, R.drawable.ic_av));
        }
        if (tuner != null) {
            String detail = names.containsKey("LIVETV") ? names.get("LIVETV") : names.get("TV");
            out.add(new Item(tuner.getId(), ctx.getString(R.string.live_tv), detail, R.drawable.ic_live_tv, true));
        }
        return out;
    }

    static Intent intentFor(Item item) {
        return new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(item.id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static Item item(Context ctx, TvInputManager tim, TvInputInfo in, String name, String key,
                             Map<String, String> names, Map<String, String> cecNames, int icon) {
        boolean connected = tim.getInputState(in.getId()) != TvInputManager.INPUT_STATE_DISCONNECTED;
        String detail = names.get(key);
        if (detail == null) detail = cecNames.get(in.getId());
        if (detail == null) {
            CharSequence custom = in.loadCustomLabel(ctx);
            if (!TextUtils.isEmpty(custom) && !GENERIC_LABEL.matcher(custom).matches()
                    && !custom.toString().equalsIgnoreCase(name)) {
                detail = custom.toString();
            }
        }
        if (detail == null && !connected) detail = ctx.getString(R.string.not_connected);
        return new Item(in.getId(), name, detail, icon, connected);
    }

    /** "HDMI 1 = PlayStation 5" -> {"HDMI1": "PlayStation 5"}. */
    private static Map<String, String> readNames(File f) {
        Map<String, String> out = new HashMap<>();
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (line.trim().startsWith("#") || eq <= 0) continue;
                String key = line.substring(0, eq).replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
                String value = line.substring(eq + 1).trim();
                if (!value.isEmpty()) out.put(key, value);
            }
        } catch (IOException ignored) {
            // No descriptions then.
        }
        return out;
    }

    private static int trailingNumber(String id) {
        Matcher m = TRAILING_NUMBER.matcher(id);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }
}
