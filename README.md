<div align="center">

<img src="docs/assets/icon-512.png" width="96" alt="">

# Home TV

**A fast, ad-free home screen for Google TV, LG and Samsung TVs.**<br>
<sub>A personal fork of [Free My TV](https://github.com/simbasjungles/free-my-tv).</sub>

Trending picks from your own apps, the weather at a glance, and a look that follows the sunset.<br>
Open source. Built with Claude Code. One button to undo.

[**freemytv.io**](https://freemytv.io) · [Install](#install) · [LG TVs](#lg-tvs-webos) · [Samsung TVs](#samsung-tvs-tizen) · [Any TV](#any-tv) · [Make it yours](#make-it-yours)

<img src=".github/preview.webp" width="880" alt="The Free My TV home screen: a glass shelf of apps, trending picks, and a drawer with the weather and crypto prices">

</div>

## What you get

- **No ads.** No sponsored rows, banners or autoplaying promos. Not one.
- **Your apps, front and center.** The streaming apps you actually use, on a glass shelf. Pick them and their order right on the TV.
- **Trending, not sponsored.** What's trending this week and the latest releases, only from apps you already have, in a scrolling carousel. In this fork there are four tabs, **Home · Discover · Browse · Apps**, and [your Plex library](#this-fork) is part of them.
- **Weather and markets, one press away.** The hour-by-hour forecast, sunrise and sunset, and live crypto prices (the weather is off by default in this fork).
- **Light by day, dark by night.** It follows your city's real sunset, the way your phone does.
- **A screensaver to match.** Full-screen photos of the picks with a big glass clock.
- **Fast.** Drawn by hand on one canvas, with no libraries. The original's figures (60 fps, 96 MB instead of 242 MB, measured on a 98″ TCL Google TV running Android 14) are for its three-card layout; the carousel in this fork scrolls smoothly on a Chromecast with Google TV, whose GPU is the limit.
- **Set up on the TV.** Language, your city, temperature, theme, the weather and prices cards, which coins, and the apps on the shelf, in Home TV's own settings.
- **Safe.** No root. Nothing is uninstalled. One button brings back the factory home screen.

## This fork

This is a personal fork of [simbasjungles/free-my-tv](https://github.com/simbasjungles/free-my-tv) (MIT), built on Google TV (a Chromecast with Google TV). What it changes, on top of the original:

- **Four tabs, one job each: Home · Discover · Browse · Apps.** **Home** (the first screen) is what you'd do right now: **Up Next**, what you've started on your Plex server, as big landscape cards with the episode's own still, a progress bar and the time left, and your apps on the shelf in full view below it (Down goes from one to the other). With nothing in progress the row is never empty: it becomes **Start something new** (what's new on Plex), or, with nothing there either, **Trending on your apps**. **Discover** (called Explore in the original) is the big carousel, with an **All · Movies · Shows** control under the tab bar; Down scrolls the page to a second carousel, **New on Plex** (its heading and the top of its cards peek from the bottom edge until then): what arrived on your server lately and you haven't watched, newest first, movies and shows as **whole seasons** rather than single episodes (Plex adds a show's episodes one by one; they're grouped into their season, which moves to the front when a new episode lands and says how many episodes it has and how many are left; what's already in Up Next isn't repeated). **Watch on Plex** plays a season's first episode you haven't seen. The All / Movies / Shows control applies to both carousels. **Browse** is the full-screen genre grid below. **Apps** is every app in a grid, Launcher Settings and TV Settings first, then the shelf's apps, then the rest, each drawn as on the shelf. The app cards carry their brand colors (Netflix red, Spotify green, YouTube deep red); apps drawn from their own TV banner (Plex, RaiPlay…) keep it. The tabs and the control remember their choices.
- **A carousel, not three cards.** Discover's two carousels scroll through up to 40 titles each. The focused card is wide and the others tall; moving focus slides the row and trades the widths. The background follows the focused card once you stop on it.
- **A detail screen.** OK on a card opens the poster, title, year, genre, length, rating, description and cast, with three buttons: **Watch on …** (opens the app), **Trailer** and **Watchlist**.
- **A Browse tab that takes the whole screen.** No app shelf and no carousel: a 4 × 4 grid of the genres (Action, Adventure, Animation, Comedy, Crime, Documentary, Drama, Family, Fantasy, Horror, Mystery, Romance, Science Fiction, Thriller, War, Western), each on a photo of a popular title of that genre from your apps. OK on a genre opens a poster grid (7 wide, scrolling by rows) of its titles: those on your apps together with your unwatched Plex titles of that genre, **best rated first, whatever they are on** (one scale for everything: TMDB's; a title you have on Plex and that is also on a streaming app shows once, as the Plex one). Every poster says where to watch it (Netflix, Prime Video, Plex…) and its rating, and the focused title's name and details sit at the bottom. A small **All · Movies · Shows** control under the tabs filters what a genre shows (Up from the first row reaches it; Left and Right change it; it is remembered). OK opens the detail screen; Back goes up a level (titles → genres → the tabs). Titles you can't watch on your apps or in Plex don't appear.
- **Your Plex library in the picks.** The newest *unwatched* movies and shows on your Plex server are mixed in, one for each TMDB pick, with a "Plex" badge, Plex's own artwork and a button that opens the Plex app. Set `plex_url` and `plex_token` in `config.properties` (see [Settings](#settings)). "Watch on Plex" plays the title (a show: its next episode) in the Plex TV app, through the link that app accepts (`plex://watch/video?uri=server://…`, found by reading the app; the older `plex://preplay` form is ignored by it). **Plex's links can't resume part-way**: the button says "Play on Plex" for a title you've started, and it starts from the beginning (the progress bar and "19 min left" still show where you were).
- **Trailers inside the app.** The trailer plays in YouTube's embedded player in a screen of its own (OK pauses, ◀ ▶ skip 10 s, **Back returns to the title's page**). The YouTube app keeps its own history and sends Back to its home, so it isn't used, except for the few trailers a studio has blocked from embedding: OK then opens them in YouTube. The trailer comes from TMDB.
- **Plex watchlist.** The **Watchlist** button adds the title to (or removes it from) your Plex watchlist, the one on your plex.tv account. For titles that aren't on your server it looks them up in Plex's catalog by name, kind and year.
- **A calmer screensaver.** The big glass clock is replaced by a plain white clock with a soft shadow, over the same photos of the picks.
- **A greeting instead of "For You":** "Good morning / afternoon / evening", with your name if you set `name=`.
- **Weather off by default** (it can still be switched on in *Launcher Settings*; sunrise and sunset still drive the day and night look).
- **The system's own UI sounds** for moving and opening, the same ones Settings uses, following the TV's *sound effects* setting.
- **Called Home TV.** On Google TV the app, its settings and its screensaver are named *Home TV* (the package is still `io.freemytv`, so a new build updates the installed app in place). The LG and Samsung builds keep the original name for now.
- **Updates over your home network.** `./build.sh --publish` puts a new build in a folder on your network, and the TVs install it by themselves (Android asks to confirm): no adb after the first install. See [Updates over your home network](#updates-over-your-home-network-optional).
- **A 24-hour clock**, in English too (the original shows 9:47 in English).
- **A simpler settings page.** Plain black, one list (Home Screen, Screensaver, Shelf, then the preferences and Update) instead of a page inside a page, without the rows that do nothing on the TV (Remote Buttons where its remote has no app keys; the crypto rows when prices are off; TV Settings, which the home screen already has). The left side shows the update state and this build's date, the picks and whether Plex answers; only the newest notice, from the last three days, is shown.
- **The top-right Settings button opens the TV's settings.** The launcher's own settings are *Launcher Settings* (formerly *Home Settings*) in the shortcuts drawer.

## Install

About ten minutes. You need a Google TV or Android TV (Android 9 or newer) and a computer on the same Wi-Fi.

### The easy way: an AI assistant

1. **On the TV**, turn on developer mode: Settings → System → About → press *Android TV OS build* 7 times. Then Settings → System → Developer options → turn on **USB debugging**. Write down the TV's IP address (Settings → Network & Internet).
2. **On your computer**, open an AI coding assistant ([Claude Code](https://claude.com/claude-code), Codex or Antigravity) and say:
   > Install Free My TV (github.com/simbasjungles/free-my-tv) on my TV at 192.168.1.50. I live in Lisbon.

   It downloads the code, installs the tools it needs, builds the app and puts it on your TV, and asks before anything important. Swap in your TV's IP and your city.
3. **On the TV**, open *Home TV* → *Home Screen* → *Home TV*, and accept the debugging prompt (tick *Always allow*).

### The manual way

1. **On the TV**, do step 1 above.
2. **Tools:** a JDK (17+), the Android SDK command-line tools (build-tools 34, platform android-34) and adb. On a Mac:
   ```bash
   brew install --cask temurin android-commandlinetools android-platform-tools
   sdkmanager "build-tools;34.0.0" "platforms;android-34"
   ```
   On Linux, get OpenJDK 17, Android's command-line tools and adb from your package manager, then run the same `sdkmanager` line. On Windows, use Git Bash (which Claude Code uses there) or WSL.
3. **Build:**
   ```bash
   ./build.sh
   ```
   The first run creates `config.properties`. Set your city and your [TMDB key](#the-tmdb-key) there, then run it again.
4. **Install:**
   ```bash
   adb connect 192.168.1.50:5555
   adb install -r FreeMyTV.apk
   ```
   On TCL TVs, also let it run in the background (TCL blocks it by default, and the switch never starts):
   ```bash
   adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"
   ```
5. **On the TV**, open *Home TV* → *Home Screen* → *Home TV*.

## LG TVs (webOS)

The same home screen as an LG app: the picks, the glass shelf with your apps, the drawer with inputs, weather and prices, day and night, Home Settings and the shelf editor. It installs through LG's own Developer Mode, with nothing rooted or changed on the TV. LG's home screen stays where it is, and Free My TV is one press of a number key away.

You need an LG TV (webOS 3 or newer) and a computer on the same Wi-Fi, with Python 3.

1. **On the TV**, open the LG Content Store and install **Developer Mode**. Sign in with a free [LG developer account](https://webostv.developer.lge.com), turn on **Dev Mode Status** (the TV restarts), then turn on **Key Server**. Write down the IP address and the passphrase it shows.
2. **On your computer**, get the code and build. `config.properties` works as for Google TV (your city, your [TMDB key](#the-tmdb-key)):
   ```bash
   git clone https://github.com/simbasjungles/free-my-tv && cd free-my-tv
   python3 webos/build.py
   ```
3. **Install.** The first time, it asks for the TV's passphrase; updates don't:
   ```bash
   python3 webos/install.py 192.168.1.50
   ```
4. **On the TV**, while Free My TV is open, hold a number key on the remote. From then on, that key opens it from anywhere.

With an AI assistant, say *"Install Free My TV (github.com/simbasjungles/free-my-tv) on my LG TV at 192.168.1.50. I live in Lisbon."* It builds the app and hands you the install command, so you type the passphrase yourself.

Developer Mode lasts about 40 days: open the Developer Mode app now and then and press **EXTEND**. To remove Free My TV, run `python3 webos/install.py 192.168.1.50 --uninstall`.

## Samsung TVs (Tizen)

The same home screen as a Samsung app, with everything the LG one has: the picks, the shelf, the drawer with the inputs, the weather and the prices, day and night, and its own settings. It installs through Samsung's own Developer Mode, with nothing rooted or changed on the TV, and Samsung's home screen stays where it is.

You need a Samsung TV from 2017 or newer (Tizen 3+) and a computer on the same Wi-Fi.

1. **On the TV**, open **Apps** and type `1 2 3 4 5` on the remote. Turn **Developer mode** on, type your computer's IP address in **Host PC IP**, and restart the TV.
2. **On your computer**, download [TizenBrew Installer](https://github.com/reisxd/TizenBrewInstaller/releases/latest), the community's installer for Samsung TVs, open it and connect to your TV.
3. **Install from GitHub:** type `simbasjungles/free-my-tv`. On 2023 and newer TVs it asks you to sign in to your Samsung account once, to sign the app for your TV.
4. **Straight to it:** in the TV's settings, under Start Screen Options, turn on **Autorun Last App**: the TV turns on into Free My TV.

This build takes your city from the TV's time zone. For your exact city and the trending picks, build it with your own `config.properties` and [TMDB key](#the-tmdb-key): open the folder in Claude Code and say *"Install Free My TV on my Samsung TV at 192.168.1.50. I live in Lisbon."* It sets up Samsung's tools, and on 2023 and newer TVs you sign in to your Samsung account in the browser to make the certificate. Your signed build installs with `python3 tizen/install.py 192.168.1.50`, which needs only Python: no Tizen Studio, and no Rosetta on Apple Silicon Macs.

## Settings

The first values come from `config.properties`, created by the first build (Google TV, LG and Samsung use the same file). Most of them can also be changed on the TV, in Home TV's settings (its icon, or *Launcher Settings* on the home screen), and the TV's choice wins:

| Key | What it does | Default |
|---|---|---|
| `tmdb_key` | Your free TMDB key, for the picks | empty |
| `tmdb_region` | Your country (two letters): which titles are on which app | `US` |
| `watchmode_key` | Optional, free [Watchmode](https://api.watchmode.com/) key: a pick opens the title's page in its app | empty |
| `plex_url`, `plex_token` | Optional, your Plex server (e.g. `http://192.168.1.10:32400`) and your [X-Plex-Token](#your-plex-server-optional): its unwatched titles join the picks, and the Watchlist button works | empty |
| `name` | Your first name, for the greeting ("Good evening, Alex") | empty |
| `latitude`, `longitude` | Your city, for the weather and the sunset | New York |
| `city` | The name on the weather card | `Weather` |
| `hide_animation` | Leave cartoons and anime out of the picks | `false` |
| `language` | `auto` (follows the TV), `en`, `es`, `pt`, `ru` or `de` | `auto` |
| `show_crypto` | Show Binance crypto prices on home and screensaver (off by default in this fork) | `false` |
| `currency` | Crypto prices in `USD`, `EUR`, `BRL`… | `USD` |
| `temperature` | `C`, `F` or `auto` | `auto` |
| `disable_assistant` | Also turn off Google Assistant | `false` |
| `update_url`, `update_dir` | Optional: [updates over your home network](#updates-over-your-home-network-optional). The folder's address the TVs check, and the folder `./build.sh --publish` writes to | empty |

Changed something? Run `./build.sh` and install again. Keep `freemytv.keystore`: updates are signed with it.

### Updates over your home network (optional)

Once a TV has a build with `update_url`, new versions reach it without a computer or adb. Share a folder on your network over plain `http` (any web server will do: a small `nginx` container, a NAS), set `update_dir` to that folder and `update_url` to its address (e.g. `http://192.168.1.10:8099/`), and publish with:

```bash
./build.sh --publish
```

It builds as usual, then writes `FreeMyTV.apk` and `version.json` (the version and the APK's SHA-256) into `update_dir`. Each TV looks there every six hours, downloads a newer build and checks it against the SHA-256. Once the home screen has been left alone for a few seconds, it starts the install, and Android asks to confirm (*Launcher Settings → Update* shows where it stands, and OK there checks or installs). Android accepts only a build signed with the same `freemytv.keystore`. The first time, let the app install updates: `adb shell appops set io.freemytv REQUEST_INSTALL_PACKAGES allow` (or allow *Home TV* under *Install unknown apps* in the TV's settings). The APK carries your keys, so share that folder on your home network only, never on the internet.

### The TMDB key

Free, and it takes two minutes: create an account at [themoviedb.org](https://www.themoviedb.org/signup), then open [Settings → API](https://www.themoviedb.org/settings/api) and request a developer key. Paste the *API Read Access Token* (or the *API Key*) after `tmdb_key=`. It's your key: `config.properties` stays out of git.

### Your Plex server (optional)

Set `plex_url` (your server's address, with the port) and `plex_token` in `config.properties`. To find the token, open any item in Plex on the web, then **⋯ → Get Info → View XML**, and copy `X-Plex-Token` from the address. It is the token of your plex.tv account, so it also lets the **Watchlist** button work. Treat it like a password: `config.properties` stays out of git, and the token is sent only to your server and to plex.tv, as a header.

Your server speaks plain `http` on the local network, so the build allows plain `http` for the host of `plex_url` and for nothing else (it writes that rule at build time, so no address is stored in the repository). Everything else stays on `https`. The TV must be on the same network as the server.

### Opening a title, not just its app (optional)

With a free [Watchmode](https://api.watchmode.com/) key (`watchmode_key=` in `config.properties`), a pick opens its own page in the streaming app instead of the app's home. The free plan (2,500 credits a month) is enough: each title costs two requests the first time and is remembered after that. It works where the app takes the title's web address (tested: Prime Video and JioHotstar); where it doesn't, the pick just opens the app, as without a key.

## Going back

On the TV: *Home TV* → *Home Screen* → *Original*. The factory home screen, screensaver and every app come back exactly as they were. If the new home screen ever crashes three times in five minutes, it switches back by itself.

To remove it for good, switch to *Original* first, then uninstall *Home TV* (Settings → Apps). Uninstalled it while it was on? Install it again and pick *Original*: the list of what it turned off is kept in the TV's settings, so everything comes back.

## What it changes

- It becomes the home screen and the screensaver.
- It turns off, without uninstalling:
  - the stock home screen and screensaver;
  - Google TV's recovery home, Play Games and YouTube Music;
  - on TCL TVs, the TCL Channel, messages, store demo, hotel menu, manuals, mirroring and logging apps;
  - on Philco TVs, AI PONT (promo channels and app downloads) and the manual;
  - on Multilaser TVs, Anoki's screen-content recognition (ACR), the store demo and the manual;
  - on KTC-made TVs (NextStar), Anoki's screen-content recognition (ACR), the store demo and the manual;
  - Google Assistant, only with `disable_assistant=true`.
- On TCL TVs, the remote's Netflix, YouTube and Prime buttons keep working through a small accessibility service that only reads those buttons. Pick what each one opens in *Home TV* → *Remote Buttons*; *Original* turns the service off. On Vestel-made TVs (F&U and other store brands) and Xiaomi (Formovie) projectors the same goes for the Netflix button, and on KTC-made TVs (NextStar) for the Tabii button. On Sony TVs, the BRAVIA CORE, Netflix, Disney+, Prime Video, YouTube and Crunchyroll buttons can each open the app you pick, or keep Sony's default.
- Picture, sound, inputs, remote pairing, Google services and the Play Store are on a protected list, checked before every change.
- Apps that saved a Wi-Fi network are never turned off, since Android would erase the network. If the TV's first setup saved yours, the switch asks you to forget the network and connect again in Settings first (saved networks out of range don't show there; Claude Code can remove them).

## Any TV

Free My TV runs on Google TV and Android TV: TCL, Sony, Hisense, Philips, Xiaomi, Vestel-made TVs (F&U), KTC-made TVs (NextStar) and more, plus streamers like Chromecast with Google TV, the Google TV Streamer, onn 4K and NVIDIA Shield. And on [LG](#lg-tvs-webos) and [Samsung](#samsung-tvs-tizen) TVs, as an app.

**Any other TV:** plug a Google TV streamer into HDMI and free that one. Same steps, same home screen.

**A different brand or model:** ask Claude. Free My TV is made to be adapted by AI. Open the folder in Claude Code and say *"set it up for my Sony"*: it reads your TV, finds its home screen and its promo apps, updates the lists below and walks you through a test. [CLAUDE.md](CLAUDE.md) tells it how.

| What | Where |
|---|---|
| Apps to turn off | `DEBLOAT` in [`HomeMode.java`](app/src/io/freemytv/HomeMode.java) |
| Apps never to touch | `PROTECTED` in the same file |
| Apps on the shelf | `SHELF` in [`HomeApps.java`](app/src/io/freemytv/HomeApps.java) |
| Your brand's live TV app | `LIVE_TV_APPS` in the same file |

## Make it yours

It's your code, and Claude Code finds its way around it. Try:

- *"Add Crunchyroll to the shelf."*
- *"Show the prices in euros."*
- *"Add Spanish."*
- *"Switch to the night look an hour after sunset."*

## How it works

- **A plain Android app, no Gradle, no libraries.** [`build.sh`](build.sh) runs aapt2 → javac → d8 → zipalign → apksigner and makes a 2 MB APK.
- **It uses the TV's own ADB, from inside the TV.** The app connects to `127.0.0.1:5555` with its own key (that's the *Allow USB debugging?* prompt) and runs the same commands you would: `cmd package set-home-activity`, `settings put secure screensaver_components`, `pm disable-user`. Nothing listens to the network.
- **Drawn by hand on one Canvas**, with Android 13 shaders for the frosted glass and the sky (plain fills on Android 9 to 12, with lighter shadows and no GPU layers on 9 to 11 for their older GPUs).
- **A safety net.** A watchdog in a separate process brings back the stock home screen after three crashes in five minutes, and a boot check keeps your choice after TV updates.
- **On LG and Samsung, the same design in plain HTML, CSS and JavaScript** ([`web/`](web)), written for the Chromium of older TVs, with no frameworks. [`webos/build.py`](webos/build.py) packs it with `config.properties` into an `.ipk` without LG's tools, and [`webos/install.py`](webos/install.py) installs it through Developer Mode; on LG it lists and opens apps through the TV's own service bus (Luna), as LG's launcher does. [`tizen/build.py`](tizen/build.py) packs the same app into a Samsung `.wgt`, which uses Tizen's web APIs for the apps and the inputs. To preview it on a computer: `python3 webos/build.py --preview`, then open `out/webos/index.html` (a stand-in lists sample apps).
- **Data** from TMDB (trending titles, new releases and which app streams what in your country), Open-Meteo (forecast, sunrise and sunset), and optionally Binance (24-hour prices when `show_crypto=true`).

| File | What it is |
|---|---|
| [`HomeView.java`](app/src/io/freemytv/HomeView.java) | The home screen |
| [`HomeMode.java`](app/src/io/freemytv/HomeMode.java) | Switching between Free My TV and Original |
| [`Adb.java`](app/src/io/freemytv/Adb.java) | The TV's own ADB, from inside the TV |
| [`Recs.java`](app/src/io/freemytv/Recs.java) | The picks (TMDB) |
| [`HomeData.java`](app/src/io/freemytv/HomeData.java) | Weather and prices |
| [`HomeApps.java`](app/src/io/freemytv/HomeApps.java) | The shelf, inputs and All Apps |
| [`DreamView.java`](app/src/io/freemytv/DreamView.java) | The screensaver |
| [`SettingsActivity.java`](app/src/io/freemytv/SettingsActivity.java) | The settings screen (the app's icon) |
| [`Prefs.java`](app/src/io/freemytv/Prefs.java) | Settings changed on the TV |
| [`RemoteKeys.java`](app/src/io/freemytv/RemoteKeys.java) | The TCL remote's app buttons |
| [`Watchdog.java`](app/src/io/freemytv/Watchdog.java) | The safety net |
| [`web/home.js`](web/home.js) | The home screen on LG TVs |
| [`web/tv.js`](web/tv.js) | The TV's apps and inputs, on LG and Samsung |
| [`webos/build.py`](webos/build.py), [`webos/install.py`](webos/install.py) | The LG app package and its install |
| [`tizen/build.py`](tizen/build.py) | The Samsung app package |

The website ([freemytv.io](https://freemytv.io)) is in [`docs/`](docs): plain HTML, CSS and JS on GitHub Pages.

## Privacy

No accounts, no analytics, no tracking. The app talks to TMDB, Open-Meteo (with the coordinates you set, or your time zone's main city), and Binance only when `show_crypto=true`, and Watchmode only when you set a `watchmode_key`. With `plex_url` and `plex_token` set it also talks to your own Plex server, and to plex.tv for the watchlist and for finding titles in Plex's catalog. With `update_url` set, it checks that folder on your network for new versions. A trailer is played by YouTube's embedded player in a WebView that can load only YouTube (without your YouTube account, so it may show ads); the title's trailer is looked up on TMDB. Your settings stay on your computer and your TV.

## Credits

- Built with [Claude Code](https://claude.com/claude-code). This fork adds the carousel, the detail screen, Plex and trailers on top of [simbasjungles/free-my-tv](https://github.com/simbasjungles/free-my-tv) by Simba and its contributors.
- Plex is a trademark of Plex, Inc.; YouTube is a trademark of Google. Not affiliated with either.
- The LG version builds on what [@danielthejoker18](https://github.com/danielthejoker18) found in [FreeMyTvWebOS](https://github.com/danielthejoker18/FreeMyTvWebOS) (MIT): installing through Developer Mode and opening apps through the TV's service bus.
- On Samsung TVs it installs with [TizenBrew Installer](https://github.com/reisxd/TizenBrewInstaller) by [@reisxd](https://github.com/reisxd).
- This product uses the TMDB API but is not endorsed or certified by [TMDB](https://www.themoviedb.org/).
- Links to the titles in the streaming apps by [Watchmode](https://api.watchmode.com/).
- Weather data by [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0). Prices from the [Binance](https://www.binance.com/) public API.
- Icons: [Material Symbols](https://fonts.google.com/icons) (Apache 2.0). Font: [Inter](https://rsms.me/inter/) (SIL OFL 1.1). Coin logos: [cryptocurrency-icons](https://github.com/spothq/cryptocurrency-icons) (CC0). Samsung app logos: [Simple Icons](https://simpleicons.org) (CC0).
- Not affiliated with Google, TCL, LG, Samsung or any streaming service. App names and logos belong to their owners.

## License

[MIT](LICENSE). Fork it, change it, ship it.
