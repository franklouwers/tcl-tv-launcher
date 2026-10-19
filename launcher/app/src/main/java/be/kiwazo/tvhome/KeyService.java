package be.kiwazo.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gives the remote's special keys a job again. On Google TV they are routed to
 * the stock launcher (or to TCL apps we disabled), and Android only lets system
 * apps take over those routes. An accessibility service may filter keys, so this
 * one watches for those keys only:
 *
 *  - ⚙ toggles Settings: the same screen as the ⚙ pill on the home screen; a
 *    second press returns to the app you were in.
 *  - The app buttons (Netflix, Prime, YouTube, TCL Channel, TCL Home, Web) open
 *    an app. Defaults below; override with files/buttons.txt ("WEB = be.base.tv").
 */
public class KeyService extends AccessibilityService {

    private static final String SETTINGS_PKG = "com.android.tv.settings";
    private static final String NETFLIX = "com.netflix.ninja";
    private static final String PRIME = "com.amazon.amazonvideo.livingroom";
    private static final String YOUTUBE = "com.google.android.youtube.tv";
    private static final String BASE_TV = "be.base.tv";

    /** TCL remote app buttons: keycode -> name used in buttons.txt. */
    private static final Map<Integer, String> BUTTONS = new HashMap<>();
    private static final Map<String, String> DEFAULT_TARGETS = new HashMap<>();

    static {
        BUTTONS.put(4062, "NETFLIX");
        BUTTONS.put(4099, "PRIME");
        BUTTONS.put(KeyEvent.KEYCODE_BUTTON_3, "YOUTUBE");
        BUTTONS.put(4020, "TCL_CHANNEL");
        BUTTONS.put(4137, "TCL_HOME");
        BUTTONS.put(4159, "WEB");
        DEFAULT_TARGETS.put("NETFLIX", NETFLIX);
        DEFAULT_TARGETS.put("PRIME", PRIME);
        DEFAULT_TARGETS.put("YOUTUBE", YOUTUBE);
        DEFAULT_TARGETS.put("TCL_CHANNEL", BASE_TV);
        DEFAULT_TARGETS.put("TCL_HOME", BASE_TV);
        DEFAULT_TARGETS.put("WEB", BASE_TV);
    }

    /**
     * Fallback when usage access is missing: HomeActivity reports itself when it
     * comes to the front.
     */
    private static volatile String foregroundPkg;
    private String returnPkg;

    static void noteForeground(String pkg) {
        foregroundPkg = pkg;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean up = event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled();

        if (code == KeyEvent.KEYCODE_NOTIFICATION || code == KeyEvent.KEYCODE_SETTINGS) {
            if (up) {
                String current = currentPackage();
                if (SETTINGS_PKG.equals(current)) closeSettings();
                else openSettings(current);
            }
            return true; // consume both down and up so the system doesn't act on it
        }

        String button = BUTTONS.get(code);
        if (button == null) return false;
        // BUTTON_3 doubles as a gamepad button; only the remote's YouTube key counts.
        if (code == KeyEvent.KEYCODE_BUTTON_3 && event.isFromSource(InputDevice.SOURCE_GAMEPAD)) return false;
        String target = buttonTarget(button);
        if (target == null) return false; // "none": leave the key to the system
        if (up) launchApp(target);
        return true;
    }

    // ---------------------------------------------------------------- ⚙ key

    /**
     * The app in front right now, from the usage-stats log (last activity resumed).
     * Deliberately not via window content: a service that can read other apps'
     * windows makes web-based apps such as BASE TV build an accessibility tree on
     * every update, and on this TV that cost them D-pad presses.
     * Needs usage access (GET_USAGE_STATS app-op), granted by build-and-install.sh.
     */
    private String currentPackage() {
        UsageStatsManager usm = getSystemService(UsageStatsManager.class);
        if (usm != null) {
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - 24L * 60 * 60 * 1000, now);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = null;
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) last = e.getPackageName();
            }
            if (last != null) return last;
        }
        return foregroundPkg;
    }

    private void openSettings(String current) {
        returnPkg = current;
        try {
            // Same intent as the home screen pill; fresh each time, not on the last sub-page.
            startActivity(HomeActivity.settingsIntent(android.provider.Settings.ACTION_SETTINGS));
        } catch (ActivityNotFoundException ignored) {
            // No settings app: nothing sensible to open.
        }
    }

    private void closeSettings() {
        Intent back = null;
        if (returnPkg != null && !returnPkg.equals(getPackageName()) && !returnPkg.equals(SETTINGS_PKG)) {
            back = launchIntent(returnPkg);
        }
        if (back == null) {
            performGlobalAction(GLOBAL_ACTION_HOME);
            return;
        }
        try {
            // Brings the app's existing task back as it was.
            startActivity(back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    }

    // ---------------------------------------------------------- app buttons

    /** Package for a button: files/buttons.txt first, then the defaults. Null = "none". */
    private String buttonTarget(String button) {
        File f = new File(getExternalFilesDir(null), "buttons.txt");
        if (f.isFile()) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int eq = line.indexOf('=');
                    if (line.trim().startsWith("#") || eq <= 0) continue;
                    String key = line.substring(0, eq).trim().replaceAll("[\\s-]+", "_").toUpperCase(Locale.ROOT);
                    if (!key.equals(button)) continue;
                    String value = line.substring(eq + 1).trim();
                    return value.isEmpty() || value.equalsIgnoreCase("none") ? null : value;
                }
            } catch (IOException ignored) {
                // Fall back to the defaults.
            }
        }
        return DEFAULT_TARGETS.get(button);
    }

    private void launchApp(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = null;
        // Netflix and Prime Video have their own "remote button" entry points.
        if (NETFLIX.equals(pkg)) {
            i = new Intent("com.netflix.action.NETFLIX_KEY_START").setPackage(pkg).putExtra("power_on", false);
        } else if (PRIME.equals(pkg)) {
            i = new Intent("com.amazon.amazonvideo.livingroom.AMAZON_BUTTON").setPackage(pkg);
        }
        if (i != null) {
            // Address the activity directly: Amazon's button filter lacks the DEFAULT
            // category, so an implicit intent would never resolve.
            List<ResolveInfo> hits = pm.queryIntentActivities(i, 0);
            if (hits.isEmpty()) i = null;
            else i.setClassName(pkg, hits.get(0).activityInfo.name);
        }
        if (i == null) i = launchIntent(pkg);
        if (i == null) return; // not installed or disabled
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // App vanished between lookup and launch.
        }
    }

    private Intent launchIntent(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No UI events subscribed: this service only filters keys.
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt.
    }
}
