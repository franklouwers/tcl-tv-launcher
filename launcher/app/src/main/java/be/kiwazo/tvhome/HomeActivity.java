package be.kiwazo.tvhome;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple TV style home screen.
 *
 * Focus is not left to Android's focus search: every D-pad key is handled here
 * against a tiny model (section + index), so movement is deterministic:
 * Up/Down move exactly one row, Up from the dock goes to the top bar, the ends
 * of the top bar and of each row don't fall through, and the last focused tile
 * is remembered (also across restarts).
 */
public class HomeActivity extends Activity {

    private static final String TAG = "TvHome";
    private static final int TOP = 0, DOCK = 1, GRID = 2, MENU = 3;
    private static final int COLUMNS = 4;
    private static final int DOCK_SLOTS = 6;
    private static final int PILL_INPUTS = 0, PILL_WIFI = 1, PILL_SETTINGS = 2;
    private static final float TILE_SCALE = 1.08f;
    private static final float PILL_SCALE = 1.06f;
    private static final float PRESS_FACTOR = 0.95f;
    private static final int AGENDA_ROWS = 3;
    /** Sent by the scripts/set-*.sh helpers after they change a config file. */
    private static final String ACTION_RELOAD = "be.kiwazo.tvhome.RELOAD";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newSingleThreadExecutor();

    private ContentLayout content;
    private View header, dockBand;
    private LinearLayout dock, inputsMenu, agenda;
    private RecyclerView grid;
    private GridAdapter adapter;
    private GlassView glass;
    private GlowView glow;
    private TextView greeting, clock, date, heroTitle, wifiLabel;
    private ImageView wifiIcon, wallpaper;
    private View wallpaperScrim;
    private View[] topViews;

    private final List<AppEntry> dockApps = new ArrayList<>();
    private final List<AppEntry> gridApps = new ArrayList<>();
    private final List<View> dockViews = new ArrayList<>();
    private final List<View> menuRows = new ArrayList<>();
    private final List<InputsMenu.Item> menuItems = new ArrayList<>();

    private int section = DOCK, topIdx, dockIdx, gridIdx = -1, menuIdx;
    private View focused;
    private int focusedSection = -1;
    private AppEntry heroEntry;
    private SpringAnimation scroll;
    private boolean loaded, centerDown, backDown;
    private int screenW, screenH, dockCardW, dockCardH, gridCardW, gridCardH;
    private long wallpaperStamp = -1, overridesStamp;
    private SharedPreferences prefs;
    private ConnectivityManager.NetworkCallback netCallback;
    private List<CalendarFeed.Occurrence> agendaItems;
    private volatile boolean wasOffline;
    private final Runnable reloadRunnable = this::reloadApps;
    private final Runnable saveFocusRunnable = this::saveFocus;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        prefs = getSharedPreferences("home", MODE_PRIVATE);

        content = findViewById(R.id.content);
        header = findViewById(R.id.header);
        dockBand = findViewById(R.id.dock_band);
        dock = findViewById(R.id.dock);
        grid = findViewById(R.id.grid);
        glass = findViewById(R.id.glass);
        glow = findViewById(R.id.glow);
        greeting = findViewById(R.id.greeting);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        heroTitle = findViewById(R.id.hero_title);
        wifiLabel = findViewById(R.id.wifi_label);
        wifiIcon = findViewById(R.id.wifi_icon);
        wallpaper = findViewById(R.id.wallpaper);
        wallpaperScrim = findViewById(R.id.wallpaper_scrim);
        inputsMenu = findViewById(R.id.inputs_menu);
        agenda = findViewById(R.id.agenda);
        for (int i = 0; i < AGENDA_ROWS; i++) {
            agenda.addView(getLayoutInflater().inflate(R.layout.item_agenda, agenda, false));
        }
        topViews = new View[]{
                findViewById(R.id.pill_inputs), findViewById(R.id.pill_wifi), findViewById(R.id.pill_settings)};

        layoutMetrics();

        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setItemAnimator(null);
        grid.setNestedScrollingEnabled(false);
        adapter = new GridAdapter(getLayoutInflater(), gridCardW, gridCardH, dp(22));
        grid.setAdapter(adapter);

        scroll = new SpringAnimation(content, DynamicAnimation.TRANSLATION_Y, 0f);
        scroll.setSpring(new SpringForce(0f).setStiffness(240f).setDampingRatio(0.88f));

        // Pills shift when the Wi-Fi name changes width; keep glass and menu attached.
        findViewById(R.id.pills).addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (l == ol && r == or) return;
            if (section == TOP && focused != null) moveGlass(focused, TOP, true);
            if (section == MENU) positionMenu();
        });

        topIdx = prefs.getInt("top", PILL_INPUTS);
        updateClock();
        overridesStamp = AppRepo.overridesStamp(this);
        reloadApps();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter time = new IntentFilter();
        time.addAction(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, time, Context.RECEIVER_NOT_EXPORTED);

        IntentFilter pkgs = new IntentFilter();
        pkgs.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgs.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgs.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgs.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pkgs.addDataScheme("package");
        registerReceiver(packageReceiver, pkgs, Context.RECEIVER_NOT_EXPORTED);
        // Exported so `am broadcast` from ADB reaches it; it only re-reads config files.
        registerReceiver(reloadReceiver, new IntentFilter(ACTION_RELOAD), Context.RECEIVER_EXPORTED);

        registerNetwork();
        updateClock();
        refreshWallpaper();
        refreshCalendar();
        ensureKeyService();
        long stamp = AppRepo.overridesStamp(this);
        if (stamp != overridesStamp) {
            overridesStamp = stamp;
            reloadApps();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        KeyService.noteForeground(getPackageName());
    }

    @Override
    protected void onPause() {
        centerDown = backDown = false;
        saveFocus();
        super.onPause();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(timeReceiver);
        unregisterReceiver(packageReceiver);
        unregisterReceiver(reloadReceiver);
        if (netCallback != null) {
            getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
            netCallback = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        net.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // HOME pressed while already on the home screen: back to the dock.
        // Coming back from an app also lands here, but then our window doesn't
        // have focus yet and we keep the remembered tile.
        if (hasWindowFocus() && loaded) {
            closeMenu(false);
            goHome(true);
        }
    }

    // ------------------------------------------------------------------ layout

    private void layoutMetrics() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

        int bandInner = screenW - 2 * dimen(R.dimen.band_margin) - 2 * dimen(R.dimen.band_padding);
        int dockGap = dimen(R.dimen.dock_gap);
        dockCardW = (bandInner - (DOCK_SLOTS - 1) * dockGap) / DOCK_SLOTS;
        dockCardH = Math.round(dockCardW * 9f / 16f);

        int span = (screenW - 2 * dimen(R.dimen.grid_padding)) / COLUMNS;
        gridCardW = span - dimen(R.dimen.grid_gap);
        gridCardH = Math.round(gridCardW * 9f / 16f);

        // Band = top padding + card + name label + bottom room; header fills the rest.
        int bandH = dp(16) + dockCardH + dp(28) + dp(12);
        int headerH = screenH - bandH - dp(26);
        dockBand.getLayoutParams().height = bandH;
        header.getLayoutParams().height = headerH;

        int glowTop = headerH - dp(300);
        ((FrameLayout.LayoutParams) glow.getLayoutParams()).topMargin = glowTop;
        glow.setGeometry(dimen(R.dimen.edge) + dp(220), dp(300) - dp(34), dp(460), dp(175));
    }

    private void buildDock() {
        dock.removeAllViews();
        dockViews.clear();
        for (int i = 0; i < dockApps.size(); i++) {
            View t = TileViews.create(getLayoutInflater(), dock, dockCardW, dockCardH);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) t.getLayoutParams();
            if (i > 0) lp.setMarginStart(dimen(R.dimen.dock_gap));
            t.setLayoutParams(lp);
            TileViews.bind(t, dockApps.get(i));
            dock.addView(t);
            dockViews.add(t);
        }
        dockBand.setVisibility(dockApps.isEmpty() ? View.INVISIBLE : View.VISIBLE);
    }

    // -------------------------------------------------------------------- apps

    private void reloadApps() {
        io.execute(() -> {
            AppRepo.Snapshot s = AppRepo.load(getApplicationContext(), DOCK_SLOTS);
            ui.post(() -> applySnapshot(s));
        });
    }

    private void applySnapshot(AppRepo.Snapshot s) {
        int keepSection;
        String keepPkg;
        String keepDockPkg;
        if (loaded) {
            keepSection = section == MENU ? TOP : section;
            keepPkg = currentPkg();
            keepDockPkg = dockApps.isEmpty() ? null : dockApps.get(dockIdx).pkg;
        } else {
            keepSection = prefs.getInt("section", DOCK);
            keepPkg = prefs.getString("pkg", null);
            keepDockPkg = prefs.getString("dock_pkg", null);
        }
        closeMenu(false);
        dockApps.clear();
        dockApps.addAll(s.dock);
        gridApps.clear();
        gridApps.addAll(s.grid);
        buildDock();
        adapter.submit(gridApps);

        focused = null;
        focusedSection = -1;
        heroEntry = null;
        int di = indexOf(dockApps, keepDockPkg);
        dockIdx = Math.max(0, di);
        boolean first = !loaded;
        loaded = true;
        afterLayout(content, () -> restoreFocus(keepSection, keepPkg, !first));
    }

    private void restoreFocus(int sec, String pkg, boolean animate) {
        if (sec == TOP) {
            focus(TOP, clamp(topIdx, 0, topViews.length - 1), animate);
            return;
        }
        int i = indexOf(dockApps, pkg);
        if (i >= 0) {
            focus(DOCK, i, animate);
            return;
        }
        i = indexOf(gridApps, pkg);
        if (i >= 0) {
            focus(GRID, i, animate);
        } else {
            goHome(animate);
        }
    }

    private void goHome(boolean animate) {
        if (!dockApps.isEmpty()) focus(DOCK, Math.min(dockIdx, dockApps.size() - 1), animate);
        else if (!gridApps.isEmpty()) focus(GRID, 0, animate);
        else focus(TOP, PILL_INPUTS, animate);
    }

    private void launch(AppEntry e, View tile) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        View card = tile.findViewById(R.id.card);
        Bundle opts = ActivityOptions.makeScaleUpAnimation(card, 0, 0, card.getWidth(), card.getHeight()).toBundle();
        try {
            startActivity(i, opts);
        } catch (RuntimeException ex) {
            Log.w(TAG, "launch failed: " + e.pkg, ex);
            Toast.makeText(this, getString(R.string.launch_failed, e.label), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------- input

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean canceled = (e.getFlags() & KeyEvent.FLAG_CANCELED) != 0;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down && loaded) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && loaded) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down && loaded) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && loaded) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (down) {
                    if (e.getRepeatCount() == 0 && loaded) {
                        centerDown = true;
                        press(true);
                    }
                } else if (centerDown) {
                    // Only act on a press that started here; an app that closed on
                    // key-down would otherwise hand us a stray key-up.
                    centerDown = false;
                    press(false);
                    if (!canceled) activate();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else if (backDown) {
                    backDown = false;
                    if (!canceled) back();
                }
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void move(int dx, int dy) {
        switch (section) {
            case TOP:
                if (dx != 0) {
                    int n = topIdx + dx;
                    if (n >= 0 && n < topViews.length) focus(TOP, n, true);
                    else glass.bump(dx, 0);              // ends don't fall through
                } else if (dy > 0) {
                    if (!dockApps.isEmpty()) focus(DOCK, dockIdx, true);
                    else if (!gridApps.isEmpty()) focus(GRID, 0, true);
                } else {
                    glass.bump(0, dy);
                }
                break;

            case DOCK:
                if (dx != 0) {
                    int n = dockIdx + dx;
                    if (n >= 0 && n < dockViews.size()) focus(DOCK, n, true);
                    else glass.bump(dx, 0);
                } else if (dy < 0) {
                    focus(TOP, topIdx, true);
                } else if (!gridApps.isEmpty()) {
                    focus(GRID, gridEntryColumn(), true); // exactly one row down
                } else {
                    glass.bump(0, dy);
                }
                break;

            case GRID: {
                int n = gridApps.size();
                int row = gridIdx / COLUMNS, col = gridIdx % COLUMNS;
                if (dx != 0) {
                    int nc = col + dx;
                    int ni = row * COLUMNS + nc;
                    if (nc >= 0 && nc < COLUMNS && ni < n) focus(GRID, ni, true);
                    else glass.bump(dx, 0);
                } else if (dy < 0) {
                    if (row > 0) focus(GRID, (row - 1) * COLUMNS + col, true);
                    else if (!dockApps.isEmpty()) focus(DOCK, dockIdx, true);
                    else focus(TOP, topIdx, true);
                } else {
                    int lastRow = (n - 1) / COLUMNS;
                    if (row < lastRow) focus(GRID, Math.min((row + 1) * COLUMNS + col, n - 1), true);
                    else glass.bump(0, dy);
                }
                break;
            }

            case MENU:
                if (dy != 0) {
                    int n = menuIdx + dy;
                    if (n < 0) closeMenu(true);
                    else if (n < menuRows.size()) focus(MENU, n, true);
                    else glass.bump(0, dy);
                } else {
                    glass.bump(dx, 0);
                }
                break;

            default:
                break;
        }
    }

    /** Column to land on in grid row 0: the last grid column used, else the one under the dock tile. */
    private int gridEntryColumn() {
        int n = Math.min(COLUMNS, gridApps.size());
        if (gridIdx >= 0) return Math.min(gridIdx % COLUMNS, n - 1);
        View d = dockViews.get(dockIdx);
        float cx = rectIn(content, d).centerX();
        int best = 0;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            View g = viewFor(GRID, i);
            if (g == null) continue;
            float dist = Math.abs(rectIn(content, g).centerX() - cx);
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    private void press(boolean pressed) {
        if (focused == null || focusedSection == MENU) return;
        float base = focusedSection == TOP ? PILL_SCALE : TILE_SCALE;
        Springs.scale(focused, pressed ? base * PRESS_FACTOR : base, true);
    }

    private void activate() {
        switch (section) {
            case TOP:
                if (topIdx == PILL_INPUTS) openMenu();
                else if (topIdx == PILL_WIFI) openSettings(Settings.ACTION_WIFI_SETTINGS);
                else openSettings(Settings.ACTION_SETTINGS);
                break;
            case DOCK:
                if (dockIdx < dockApps.size()) {
                    AppEntry e = dockApps.get(dockIdx);
                    View tile = dockViews.get(dockIdx);
                    ui.postDelayed(() -> launch(e, tile), 70);
                }
                break;
            case GRID:
                if (gridIdx >= 0 && gridIdx < gridApps.size() && focused != null) {
                    AppEntry e = gridApps.get(gridIdx);
                    View tile = focused;
                    ui.postDelayed(() -> launch(e, tile), 70);
                }
                break;
            case MENU:
                if (menuIdx < menuItems.size()) switchInput(menuItems.get(menuIdx));
                break;
            default:
                break;
        }
    }

    private void back() {
        if (section == MENU) closeMenu(true);
        else if (section == GRID || section == TOP) goHome(true);
    }

    // ------------------------------------------------------------------- focus

    private void focus(int sec, int idx, boolean animate) {
        View target = viewFor(sec, idx);
        if (target == null) return;
        if (focused != null && focused != target) highlight(focused, focusedSection, false, animate);

        section = sec;
        switch (sec) {
            case TOP: topIdx = idx; break;
            case DOCK: dockIdx = idx; break;
            case GRID: gridIdx = idx; break;
            default: menuIdx = idx; break;
        }
        focused = target;
        focusedSection = sec;
        ui.removeCallbacks(saveFocusRunnable);
        ui.postDelayed(saveFocusRunnable, 1500);
        highlight(target, sec, true, animate);
        scrollFor(sec, idx, animate);
        moveGlass(target, sec, animate);
        if (!dockApps.isEmpty() && (sec == DOCK || heroEntry == null)) {
            showHero(dockApps.get(Math.min(dockIdx, dockApps.size() - 1)), animate && heroEntry != null);
        }
        float headerAlpha = sec == GRID ? 0f : 1f;
        if (header.getAlpha() != headerAlpha) {
            if (animate) {
                header.animate().alpha(headerAlpha).setDuration(sec == GRID ? 160 : 280).withLayer().start();
                glow.animate().alpha(sec == GRID ? 0.35f : 1f).setDuration(320).start();
            } else {
                header.setAlpha(headerAlpha);
                glow.setAlpha(sec == GRID ? 0.35f : 1f);
            }
        }
        if (wallpaper.getVisibility() == View.VISIBLE) {
            wallpaper.animate().alpha(sec == GRID ? 0.35f : 1f).setDuration(350).start();
        }
    }

    private View viewFor(int sec, int idx) {
        switch (sec) {
            case TOP:
                return idx >= 0 && idx < topViews.length ? topViews[idx] : null;
            case DOCK:
                return idx >= 0 && idx < dockViews.size() ? dockViews.get(idx) : null;
            case GRID:
                RecyclerView.LayoutManager lm = grid.getLayoutManager();
                return lm == null ? null : lm.findViewByPosition(idx);
            case MENU:
                return idx >= 0 && idx < menuRows.size() ? menuRows.get(idx) : null;
            default:
                return null;
        }
    }

    private void highlight(View v, int sec, boolean on, boolean animate) {
        if (sec == DOCK || sec == GRID) {
            View card = v.findViewById(R.id.card);
            v.setPivotX(card.getLeft() + card.getWidth() / 2f);
            v.setPivotY(card.getTop() + card.getHeight() / 2f);
            Springs.scale(v, on ? TILE_SCALE : 1f, animate);
            View label = v.findViewById(R.id.label);
            label.animate().cancel();
            if (animate) label.animate().alpha(on ? 1f : 0f).setDuration(on ? 220 : 120).start();
            else label.setAlpha(on ? 1f : 0f);
        } else if (sec == TOP) {
            v.setPivotX(v.getWidth() / 2f);
            v.setPivotY(v.getHeight() / 2f);
            Springs.scale(v, on ? PILL_SCALE : 1f, animate);
        } else if (sec == MENU) {
            Object base = v.getTag(R.id.tag_base_alpha);
            float off = base instanceof Float ? (Float) base : 0.8f;
            v.animate().alpha(on ? 1f : off).setDuration(150).start();
        }
    }

    private void moveGlass(View target, int sec, boolean animate) {
        View box = (sec == DOCK || sec == GRID) ? target.findViewById(R.id.card) : target;
        float scale = sec == TOP ? PILL_SCALE : sec == MENU ? 1f : TILE_SCALE;
        RectF r = rectIn(content, box);
        float w = r.width() * scale, h = r.height() * scale;
        float cx = r.centerX(), cy = r.centerY();
        RectF g = new RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        float radius = sec == TOP ? h / 2f : sec == MENU ? dp(11) : dimen(R.dimen.card_radius) * scale;
        glass.moveTo(g, radius, animate);
    }

    /**
     * Top bar and dock: page at the top. Grid: row 0 sits right under the dock
     * (dock band pinned near the top edge); deeper rows take row 0's place.
     */
    private void scrollFor(int sec, int idx, boolean animate) {
        float target = 0f;
        if (sec == GRID && !gridApps.isEmpty()) {
            View first = viewFor(GRID, 0);
            View rowStart = viewFor(GRID, (idx / COLUMNS) * COLUMNS);
            if (first != null && rowStart != null) {
                float base = rectIn(content, dockBand).top - dp(28);
                target = Math.max(0f, base + rectIn(content, rowStart).top - rectIn(content, first).top);
            }
        }
        if (animate) {
            scroll.animateToFinalPosition(-target);
        } else {
            scroll.cancel();
            scroll.getSpring().setFinalPosition(-target);
            content.setTranslationY(-target);
        }
    }

    private void showHero(AppEntry entry, boolean animate) {
        if (entry == heroEntry) return;
        heroEntry = entry;
        heroTitle.animate().cancel();
        if (!animate) {
            heroTitle.setText(entry.label);
            heroTitle.setAlpha(1f);
            heroTitle.setTranslationX(0f);
            glow.setColor(entry.color);
            return;
        }
        heroTitle.animate().alpha(0f).translationX(-dp(8)).setDuration(90)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    heroTitle.setText(entry.label);
                    heroTitle.setTranslationX(dp(14));
                    heroTitle.animate().alpha(1f).translationX(0f).setDuration(320)
                            .setInterpolator(new DecelerateInterpolator(2.2f)).start();
                }).start();
        glow.animateTo(entry.color);
    }

    /**
     * TCL's boot manager refuses to bind the ⚙-key accessibility service while
     * this app isn't running yet, and Android may not retry. We're running now,
     * so if the service is switched on but not bound, rewrite the setting to make
     * Android bind it. Needs WRITE_SECURE_SETTINGS, granted by build-and-install.sh.
     */
    private void ensureKeyService() {
        AccessibilityManager am = getSystemService(AccessibilityManager.class);
        if (am == null) return;
        for (AccessibilityServiceInfo info : am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            ServiceInfo si = info.getResolveInfo().serviceInfo;
            if (getPackageName().equals(si.packageName) && KeyService.class.getName().equals(si.name)) return;
        }
        ContentResolver cr = getContentResolver();
        String list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String me = new ComponentName(this, KeyService.class).flattenToString();
        if (list == null || !list.contains(me)) return; // switched off on purpose: leave it
        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return;
        String others = list.replace(me, "").replaceAll("::+", ":").replaceAll("^:|:$", "");
        Log.i(TAG, "rebinding " + me);
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others);
        ui.postDelayed(() -> Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list), 800);
    }

    private void saveFocus() {
        prefs.edit()
                .putInt("section", section == MENU ? TOP : section)
                .putString("pkg", currentPkg())
                .putString("dock_pkg", dockApps.isEmpty() ? null : dockApps.get(Math.min(dockIdx, dockApps.size() - 1)).pkg)
                .putInt("top", topIdx)
                .apply();
    }

    private String currentPkg() {
        if (section == DOCK && dockIdx < dockApps.size()) return dockApps.get(dockIdx).pkg;
        if (section == GRID && gridIdx >= 0 && gridIdx < gridApps.size()) return gridApps.get(gridIdx).pkg;
        return null;
    }

    // ------------------------------------------------------------- inputs menu

    private void openMenu() {
        menuItems.clear();
        menuItems.addAll(InputsMenu.load(this));
        if (menuItems.isEmpty()) {
            Toast.makeText(this, R.string.inputs_empty, Toast.LENGTH_LONG).show();
            return;
        }
        inputsMenu.removeAllViews();
        menuRows.clear();
        for (InputsMenu.Item it : menuItems) {
            View row = getLayoutInflater().inflate(R.layout.item_input, inputsMenu, false);
            ((ImageView) row.findViewById(R.id.input_icon)).setImageResource(it.icon);
            ((TextView) row.findViewById(R.id.input_name)).setText(it.name);
            TextView detail = row.findViewById(R.id.input_detail);
            detail.setText(it.detail == null ? "" : it.detail);
            float base = it.connected ? 0.82f : 0.45f;
            row.setTag(R.id.tag_base_alpha, base);
            row.setAlpha(base);
            inputsMenu.addView(row);
            menuRows.add(row);
        }
        positionMenu();
        inputsMenu.setVisibility(View.VISIBLE);
        inputsMenu.setAlpha(0f);
        inputsMenu.setTranslationY(-dp(8));
        afterLayout(inputsMenu, () -> {
            inputsMenu.animate().alpha(1f).translationY(0f).setDuration(200)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
            focus(MENU, 0, true);
        });
    }

    private void positionMenu() {
        RectF pill = rectIn(content, topViews[PILL_INPUTS]);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) inputsMenu.getLayoutParams();
        lp.leftMargin = Math.round(pill.left);
        lp.topMargin = Math.round(pill.bottom + dp(10));
        inputsMenu.setLayoutParams(lp);
    }

    private void closeMenu(boolean animate) {
        if (inputsMenu.getVisibility() != View.VISIBLE) return;
        inputsMenu.animate().cancel();
        if (animate) {
            inputsMenu.animate().alpha(0f).translationY(-dp(6)).setDuration(140)
                    .withEndAction(() -> inputsMenu.setVisibility(View.GONE)).start();
        } else {
            inputsMenu.setVisibility(View.GONE);
        }
        if (section == MENU) focus(TOP, PILL_INPUTS, animate);
    }

    private void switchInput(InputsMenu.Item item) {
        closeMenu(false);
        try {
            startActivity(InputsMenu.intentFor(item));
        } catch (ActivityNotFoundException ex) {
            Intent tv = getPackageManager().getLeanbackLaunchIntentForPackage("com.tcl.tv");
            if (tv != null) {
                startActivity(tv.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                Toast.makeText(this, getString(R.string.input_failed, item.name), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openSettings(String action) {
        try {
            startActivity(settingsIntent(action));
        } catch (ActivityNotFoundException ex) {
            startActivity(settingsIntent(Settings.ACTION_SETTINGS));
        }
    }

    /** Shared with KeyService so the pill and the remote's ⚙ open the very same screen. */
    static Intent settingsIntent(String action) {
        return new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }

    // ------------------------------------------------------ clock, wifi, paper

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClock();
            renderAgenda();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ui.removeCallbacks(reloadRunnable);
            ui.postDelayed(reloadRunnable, 500);
        }
    };

    private final BroadcastReceiver reloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            overridesStamp = AppRepo.overridesStamp(HomeActivity.this);
            wallpaperStamp = -1;
            agendaItems = null;
            reloadApps();
            refreshWallpaper();
            refreshCalendar();
        }
    };

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        clock.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm", now));
        String pattern = DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM");
        String d = new SimpleDateFormat(pattern, locale).format(now.getTime());
        date.setText(d.isEmpty() ? d : d.substring(0, 1).toUpperCase(locale) + d.substring(1));
        int h = now.get(Calendar.HOUR_OF_DAY);
        int g = h >= 5 && h < 12 ? R.string.greeting_morning
                : h >= 12 && h < 18 ? R.string.greeting_afternoon
                : h >= 18 && h < 23 ? R.string.greeting_evening
                : R.string.greeting_night;
        greeting.setText(g);
    }

    /** Calendar: network only when the cached feed is older than 30 minutes. */
    private void refreshCalendar() {
        Context app = getApplicationContext();
        if (agendaItems != null && !CalendarFeed.isStale(app)) return;
        net.execute(() -> {
            List<CalendarFeed.Occurrence> list = CalendarFeed.load(app, true);
            ui.post(() -> {
                agendaItems = list;
                renderAgenda();
            });
        });
    }

    private void renderAgenda() {
        int shown = 0;
        if (agendaItems != null) {
            ZoneId zone = ZoneId.systemDefault();
            Instant now = Instant.now();
            LocalDate today = LocalDate.now(zone);
            for (CalendarFeed.Occurrence o : agendaItems) {
                if (shown == AGENDA_ROWS) break;
                if (o.isOver(now, today)) continue;
                View row = agenda.getChildAt(shown++);
                ((TextView) row.findViewById(R.id.agenda_when)).setText(whenText(o, now, today, zone));
                ((TextView) row.findViewById(R.id.agenda_title)).setText(o.title);
                row.setVisibility(View.VISIBLE);
            }
        }
        for (int i = shown; i < agenda.getChildCount(); i++) agenda.getChildAt(i).setVisibility(View.GONE);
        agenda.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
    }

    private String whenText(CalendarFeed.Occurrence o, Instant now, LocalDate today, ZoneId zone) {
        if (o.allDay) return o.startDate.isAfter(today) ? dayLabel(o.startDate, today) : getString(R.string.today);
        if (!o.start.isAfter(now)) return getString(R.string.now);
        ZonedDateTime s = o.start.atZone(zone);
        String time = DateFormat.format(DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm a",
                o.start.toEpochMilli()).toString();
        return dayLabel(s.toLocalDate(), today) + " " + time;
    }

    /** Vandaag / Morgen / weekday this week / "3 nov." further out. */
    private String dayLabel(LocalDate d, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, d);
        if (days <= 0) return getString(R.string.today);
        if (days == 1) return getString(R.string.tomorrow);
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        String pattern = DateFormat.getBestDateTimePattern(locale, days < 7 ? "EEEE" : "dMMM");
        String s = d.format(DateTimeFormatter.ofPattern(pattern, locale));
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(locale) + s.substring(1);
    }

    /** Event driven (no polling): the default-network callback reports SSID changes. */
    private void registerNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        netCallback = new ConnectivityManager.NetworkCallback(
                ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                boolean wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                String name;
                if (wifi) {
                    name = getString(R.string.wifi_generic);
                    if (caps.getTransportInfo() instanceof WifiInfo) {
                        String ssid = ((WifiInfo) caps.getTransportInfo()).getSSID();
                        if (ssid != null && !ssid.equals(WifiManager.UNKNOWN_SSID)) {
                            name = ssid.replaceAll("^\"|\"$", "");
                        }
                    }
                } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    name = getString(R.string.wifi_ethernet);
                } else {
                    name = getString(R.string.wifi_generic);
                }
                String label = name;
                ui.post(() -> setNetwork(label, wifi ? R.drawable.ic_wifi : R.drawable.ic_ethernet));
                if (wasOffline && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    wasOffline = false;
                    ui.post(HomeActivity.this::refreshCalendar);
                }
            }

            @Override
            public void onLost(Network network) {
                wasOffline = true;
                ui.post(() -> setNetwork(getString(R.string.wifi_offline), R.drawable.ic_wifi));
            }
        };
        cm.registerDefaultNetworkCallback(netCallback);
    }

    private void setNetwork(String label, int icon) {
        if (!label.contentEquals(wifiLabel.getText())) wifiLabel.setText(label);
        wifiIcon.setImageResource(icon);
    }

    private void refreshWallpaper() {
        File dir = getExternalFilesDir(null);
        File found = null;
        for (String n : new String[]{"wallpaper.jpg", "wallpaper.png", "wallpaper.webp"}) {
            File c = new File(dir, n);
            if (c.isFile()) {
                found = c;
                break;
            }
        }
        long stamp = found == null ? 0 : found.lastModified() ^ found.length();
        if (stamp == wallpaperStamp) return;
        wallpaperStamp = stamp;
        if (found == null) {
            wallpaper.setImageDrawable(null);
            wallpaper.setVisibility(View.GONE);
            wallpaperScrim.setVisibility(View.GONE);
            return;
        }
        File file = found;
        io.execute(() -> {
            Bitmap bm = decodeSampled(file, screenW, screenH);
            ui.post(() -> {
                if (bm == null) return;
                wallpaper.setImageBitmap(bm);
                wallpaper.setVisibility(View.VISIBLE);
                wallpaperScrim.setVisibility(View.VISIBLE);
                wallpaper.setAlpha(0f);
                wallpaper.animate().alpha(section == GRID ? 0.35f : 1f).setDuration(400).start();
            });
        });
    }

    private static Bitmap decodeSampled(File f, int reqW, int reqH) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565; // half the memory; fine for a dimmed backdrop
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    // ----------------------------------------------------------------- helpers

    /** Bounds of {@code v} in {@code ancestor}'s coordinates, ignoring transforms. */
    private static RectF rectIn(View ancestor, View v) {
        float x = 0f, y = 0f;
        View cur = v;
        while (cur != null && cur != ancestor) {
            x += cur.getLeft();
            y += cur.getTop();
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) break;
            cur = (View) p;
            if (cur != ancestor) {
                x -= cur.getScrollX();
                y -= cur.getScrollY();
            }
        }
        return new RectF(x, y, x + v.getWidth(), y + v.getHeight());
    }

    private static void afterLayout(View v, Runnable r) {
        v.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                v.getViewTreeObserver().removeOnPreDrawListener(this);
                r.run();
                return true;
            }
        });
        v.invalidate();
    }

    private static int indexOf(List<AppEntry> list, String pkg) {
        if (pkg == null) return -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).pkg.equals(pkg)) return i;
        return -1;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int dimen(int res) {
        return getResources().getDimensionPixelSize(res);
    }
}
