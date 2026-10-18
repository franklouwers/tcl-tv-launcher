# TCL 55C845: debloat + Apple TV style home screen

My take on [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack). I forked it and leaned on it heavily, then reworked it for my own TV and setup. Got a smart TV? Go check out his repo and do this yourself. I can't recommend it enough!

What's on the TV now:

- **38 packages disabled** (not uninstalled): TCL telemetry, TCL content/ad services, background services and preloaded apps you didn't want. Every one can be switched back on.
- **TV Home** (`be.kiwazo.tvhome`): a small native launcher replacing Google TV's home screen. It has the dock, the app grid, a clock, your calendar and an Inputs dropdown.
- **AirPlay, Chromecast, the remote's ⚙ key, Wi-Fi and HDMI switching all work** (each one tested on the TV).

Before and after: [`00-before-home.png`](shots/00-before-home.png) and [`50-grid-final.png`](shots/50-grid-final.png).

## Quick reference

| I want to… | Run |
|---|---|
| Undo everything | `scripts/restore.sh` |
| Re-enable one app | `scripts/restore.sh com.tcl.gallery` |
| Change the dock | `scripts/set-dock.sh be.base.tv com.netflix.ninja …` |
| Set or remove a wallpaper | `scripts/set-wallpaper.sh ~/Pictures/photo.jpg` / `--none` |
| Hide apps from the grid | `scripts/hide-apps.sh com.tcl.suspension …` / `--none` |
| Name what's on an input | `scripts/set-inputs.sh "HDMI 3 = Switch" "HDMI 1 = PlayStation"` |
| Change or remove the calendar | `scripts/set-calendar.sh 'webcal://…'` / `--none` |
| Change what a remote app button opens | `scripts/set-buttons.sh "WEB = com.spotify.tv.android"` / `--default` |
| Rebuild and reinstall the launcher | `scripts/build-and-install.sh` |
| Check AirPlay is still up | `scripts/check-airplay.sh` |
| Send a key / take a screenshot | `scripts/tv key DOWN OK` / `scripts/tv shot out.png` |

Run everything from this folder. The `set-*` scripts change a small file on the TV and tell the launcher to reload, so nothing gets rebuilt.

## Connecting to the TV

The TV is at `192.168.160.128`, with network debugging on port 5555. If its address changes, put `TV_IP=…` in front of any command.

macOS Local Network privacy can make the stock `adb` fail with "No route to host", so these scripts use the pure-Python [`adb-shell`](https://pypi.org/project/adb-shell/) client instead ([`scripts/tvadb.py`](scripts/tvadb.py), wrapped by [`scripts/tv`](scripts/tv)). Files over 512 KB, like the APK, travel as base64 chunks through the shell and are checked with md5.

If the `.venv` folder is missing (it's excluded from Dropbox sync), recreate it:

```bash
python3 -m venv .venv && .venv/bin/pip install adb-shell
```

The ADB key lives in `~/.android/adbkey`. The TV already trusts it ("Always allow from this computer").

## Re-enabling an app

```bash
scripts/restore.sh com.google.android.katniss
```

Each package in the table below can be restored that way. To bring back **everything** and make the Google TV home screen the default again:

```bash
scripts/restore.sh
```

Add `--uninstall-launcher` to also remove TV Home. Restore re-enables the packages in [`backup/disabled-by-us.txt`](backup/disabled-by-us.txt). `com.google.android.youtube.tvmusic` was already disabled before we started, so it's left alone.

You can also do it on the TV itself: **Instellingen → Apps → Alle apps bekijken → Systeem-apps tonen → (app) → Inschakelen**.

| Category | Disabled | What you lose |
|---|---|---|
| Telemetry / analytics | `com.tcl.logkit`, `com.tcl.ttvs` (TCL analytics, Samba TV content recognition, Alexa core), `com.google.android.feedback`, `com.android.adservices.api`, `com.android.ondevicepersonalization.services`, `com.android.federatedcompute.services` | Nothing visible |
| TCL content & ads | `com.tcl.waterfall.overseas` + `com.tcl.tv.tclhome_passive` (TCL tab), `com.tcl.channelplus` (TCL Channel), `com.tcl.partnercustomizer` (home rows, T key), `com.tcl.messagebox` (promos), `com.tcl.interactive` (overlay popups), `com.tcl.esticker` + `com.tcl.exhibit` (shop demo mode), `com.tcl.usercenter` (TCL account), `android.autoinstalls.config.google.gtvpai` (partner app auto-installs) | The Quick panel asks you to sign in to a TCL account; you can ignore that |
| Background services | `com.tcl.guard` (cleaner), `tv.mopa.ginga` (interactive-TV middleware), `com.tcl.gamebar` | No Game Bar overlay (game mode itself still works) |
| Preloaded apps | `com.tcl.ocean.instructions` (e-manual), `com.tcl.repairguide`, `com.tcl.eva` (USB camera), `com.tcl.t_solo` (BT listen-alone), `com.tcl.ui_mediaCenter` + `com.tcl.gallery` (USB media) | No playing files from a USB stick |
| Your choices | `com.google.android.katniss` (Google Assistant), `com.tcl.smartalexa` (Alexa), `com.tcl.miracast` + `com.tcl.magiconnectfree` (Miracast/T-Cast), `com.tapptic.rtl.tvi` (RTL Play), `tv.wuaki.apptv` (Rakuten TV), `nl.uitzendinggemist` (NPO Start), `com.google.android.videos` (Google TV app), `com.google.android.play.games`, `com.iqiyi.i18n.tv`, `fr.tf1.mytf1` | Mic button / voice search |
| Stock home | `com.google.android.tungsten.setupwraith`, `com.google.android.apps.tv.launcherx` | Google TV home screen and its quick-settings panel |

**Kept on purpose:** AirPlay (`com.mediatek.AirplayAPK`, `com.mediatek.airplaydaemon`, `com.tcl.airplay2`, mDNS offload), Chromecast, inputs/Live TV (`com.tcl.tvinput`, `com.tcl.tv`, `com.tcl.suspension`), remote pairing, the TCL keyboard (your active keyboard), Quick panel, peripheral updater, ambient screensaver, Dolby, and Google Play services.

### ⚠️ One ordering rule

Google TV's setup wizard (`setupwraith`) also claims the home screen, at a higher priority than any app you can install. If the stock launcher is disabled while the wizard is still enabled, its RecoveryActivity restarts in a tight loop, and that crashed Android's system process once during setup. Wi-Fi didn't reconnect afterwards. So:

- **Disable** `setupwraith` *before* `launcherx`.
- **Re-enable** `launcherx` *before or together with* `setupwraith`.

`disable.sh` and `restore.sh` enforce this order automatically. Doing it by hand in the TV's settings, follow the same order.

## The launcher

Source: [`launcher/`](launcher/), Java with AndroidX RecyclerView, minSdk 34, debug build. Output goes to `~/.cache/tvhome-build`, out of Dropbox.

**Remote:**

- **Left/Right** move within a row and stop at the ends. **Up/Down** move exactly one row.
- **Up from the dock** goes to the pills (Ingangen · Wi-Fi · ⚙).
- **Down from the dock** goes to the grid, landing in the column under the dock tile the first time.
- **Back** returns to the dock. **Home** on the home screen also returns to the dock.
- The last focused tile is remembered, including across restarts.
- **⚙ on the remote** opens Instellingen, the same screen as the ⚙ pill, inside apps too. Press it again to go back to where you were.
- **App buttons on the remote:**
  - Netflix opens Netflix.
  - Prime opens Prime Video.
  - YouTube opens YouTube.
  - TCL Channel, TCL Home and Web all open BASE TV.

**Look:** pitch black, the dock in a lighter rounded band, one glass highlight that springs between tiles, the focused app's name above the dock with a glow sampled from its banner, and Outfit type ([OFL](launcher/OUTFIT-OFL.txt)). There's no blur, no video and no polling. It uses about 44 MB of RAM, and free RAM went from 519 MB to 790 MB.

**Language:** follows the TV (Dutch). The English strings are the fallback.

### Changing the dock

```bash
scripts/set-dock.sh be.base.tv com.netflix.ninja com.amazon.amazonvideo.livingroom com.disney.disneyplus be.rtbf.auvio be.vrt.vrtnu
```

You can use up to 6 package names, left to right. List the installed apps with `scripts/tv shell "pm list packages -3"`. `scripts/set-dock.sh --default` goes back to the built-in order. To change that permanently, edit [`dock.xml`](launcher/app/src/main/res/values/dock.xml) and rebuild.

### Wallpaper

```bash
scripts/set-wallpaper.sh ~/Pictures/photo.jpg   # .jpg, .png or .webp; 1920×1080 is plenty
scripts/set-wallpaper.sh --none                 # back to pitch black
```

The photo is darkened toward the bottom so the dock stays readable, and dimmed further while you're in the grid.

### Calendar

The next 3 events of your iCloud "Family" calendar show under the date:

- All-day events show as "Vandaag", "Morgen" or a weekday.
- A running event shows "Nu".
- Recurring events, exceptions and time zones are handled. I checked them against a reference iCal library.

The feed is downloaded only when the home screen appears and the cached copy is older than 30 minutes; nothing is fetched in the background. The `webcal://` link is stored **only on the TV**, because anyone who has it can read the calendar. To change it:

```bash
scripts/set-calendar.sh 'webcal://…'   # iCloud: Calendar → share → Public Calendar → copy link
scripts/set-calendar.sh --none
```

### Inputs

The Ingangen pill lists HDMI 1–4, AV and Live TV, and switches through Android's TV input framework.

- **Descriptions** come from `set-inputs.sh` first, then names that devices announce over HDMI-CEC, then names you set in the TV's input settings. TCL's default "Input Source N" labels are ignored.
- **Live TV** opens TCL's TV app on the antenna tuner. There are no channels scanned at the moment.

### The ⚙ key

On Google TV that key (`KEYCODE_NOTIFICATION` on this remote) opens the stock launcher's panel, and Android only lets system apps take that over. TV Home therefore includes a tiny accessibility service (`KeyService`) that reads **only that key**.

- **First press:** opens Instellingen with exactly the same intent as the ⚙ pill.
- **Second press:** brings back the app that was in front, or the home screen.

The service only checks which app owns the focused window at the moment of a press; nothing runs in between. TCL's Quick panel (picture/sound presets) is hidden from the grid. If you want it back there, run `scripts/hide-apps.sh com.tcl.suspension`. Picture and sound are also under **Instellingen → Weergave en geluid**.

`build-and-install.sh` sets it up:

- It enables the service.
- It allows TCL's `AUTO_START` app-op, because TCL's "TclAppBoot" manager otherwise blocks it.
- It grants `WRITE_SECURE_SETTINGS`.

TCL resets that app-op at every boot and blocks the first bind. Android normally retries once the launcher starts as home, and the `WRITE_SECURE_SETTINGS` grant lets the launcher re-trigger the bind itself if Android doesn't. Two test reboots confirmed it comes back.

If ⚙ stops working (for example after a **force stop** of TV Home, which makes Android switch the service off), run `scripts/build-and-install.sh --no-build`.

### Remote app buttons

On Google TV these buttons went to the stock launcher (YouTube) or to TCL apps that are now disabled (Netflix, Prime, TCL Channel, TCL Home, Web). The same `KeyService` picks them up and opens an app directly:

| Button | Keycode | Opens (default) |
|---|---|---|
| Netflix | 4062 (TCL) | Netflix, through its own remote-button entry (`NETFLIX_KEY_START`) |
| Prime | 4099 (TCL) | Prime Video, through `AMAZON_BUTTON` |
| YouTube | `KEYCODE_BUTTON_3` | YouTube; the same code from a game controller is ignored |
| TCL Channel | 4020 (TCL) | BASE TV |
| TCL Home | 4137 (TCL) | BASE TV |
| Web | 4159 (TCL) | BASE TV |

Change one without rebuilding:

```bash
scripts/set-buttons.sh "WEB = com.spotify.tv.android" "TCL_HOME = be.vrt.vrtnu"
scripts/set-buttons.sh "TCL_CHANNEL = none"   # leave that button to the TV itself
scripts/set-buttons.sh --default
```

### Building

Needs JDK 21 (`brew install openjdk@21`) and the Android SDK (platform 34, build-tools 34) in `~/Library/Android/sdk`.

```bash
scripts/build-and-install.sh                  # build, install, set as home
scripts/build-and-install.sh --disable-stock  # first install: also disables setupwraith + launcherx (in that order)
scripts/build-and-install.sh --no-build       # reinstall the last build
```

## Backup

[`backup/`](backup/) holds the state from before any change. Only the package logs are in git; the full snapshot (package lists, `getprop`, all settings, original HOME activity) stays local because it contains device identifiers.

`disabled-by-us.txt` is the log that `restore.sh` reads.

## Troubleshooting

- **Wi-Fi gone after the TV hiccups:** go to the Wi-Fi pill and press OK, then reconnect to your network.
- **A TV software update re-enabled bloat:** run `scripts/disable.sh $(cat backup/disabled-by-us.txt)`. It's safe to repeat and keeps the setup-wizard order.
- **Home shows Google TV again:** run `scripts/build-and-install.sh --no-build --disable-stock`.
- **Screenshots of HDMI or Live TV come out empty:** expected; TCL marks those screens as secure.
