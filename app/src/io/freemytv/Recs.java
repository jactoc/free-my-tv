package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Okko, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). Three per hour.
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, meta = "", app, pkg;
        String link;            // opens the title in its app (looked up with Watchmode), or null: the app's home
        boolean detailed;
        String plexKey, image;  // a title from your Plex server: its rating key, and the full address of its backdrop
        String summary = "", poster, cast = "", year = "";   // the detail screen: description, poster address, "A, B, C"
        float rating;           // out of 10, 0 = none
        int tmdb;               // a Plex title's TMDB number (for its trailer); TMDB picks use `id`
        String discoverId;      // the title's id on Plex's cloud catalog (what the watchlist uses)
        // Looked up when the detail screen opens (not saved): the YouTube trailer and whether it's on your Plex watchlist.
        volatile String trailer;
        volatile int watchlisted = -1;      // -1 not known yet, 0 no, 1 yes
        volatile boolean lookedUp, lookingUp;
        volatile boolean detailing;     // its details are being fetched (Browse lists load them as you scroll)
        String ratingLabel;             // "★ 8.4", made once by the home screen
        float progress;                 // Continue Watching: how far in you are (0 to 1), 0 = not started
        String showKey;                 // an episode's show (its Plex key), for the show's TMDB number
        String plexType;                // "season" for a season in New (its link plays the first episode not watched)
    }

    interface Listener { void onRecs(); }

    private static final String API = "https://api.themoviedb.org/3";
    /** The backdrops: on a 720p UI, TMDB's 1280-wide copy shows the same and is lighter to download and decode. */
    private static final String IMAGES = "https://image.tmdb.org/t/p/" + (Backdrop.W <= 1280 ? "w1280" : "original");
    /** TMDB's Animation genre (movies and shows: cartoons and anime), left out with hide_animation=true. */
    private static final int ANIMATION = 16;
    private static final long LIST_EVERY = 6 * 3600_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 7;
    private static final String PLEX_PKG = "com.plexapp.android";
    private static final int PLEX_MAX = 24;
    /** The carousel's length: every pick gets its details and image fetched in the background. */
    private static final int CAROUSEL_MAX = 40;
    private static final String POSTERS = "https://image.tmdb.org/t/p/w500";
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    static final String[] PERIOD_LABEL = {"PARA ESTA MANHÃ", "PARA ESTA TARDE", "PARA ESTA NOITE"};
    static final String[] PERIOD_LABEL_RU = {"ЭТИМ УТРОМ", "СЕГОДНЯ ДНЁМ", "СЕГОДНЯ ВЕЧЕРОМ"};
    static final String[] PERIOD_LABEL_EN = {"THIS MORNING", "THIS AFTERNOON", "TONIGHT"};
    static final String[] PERIOD_LABEL_ES = {"PARA ESTA MAÑANA", "PARA ESTA TARDE", "PARA ESTA NOCHE"};
    static final String[] PERIOD_LABEL_DE = {"HEUTE MORGEN", "HEUTE NACHMITTAG", "HEUTE ABEND"};
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"max", "max", "com.hbo.hbonow"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
        {"apple tv", "appletv", "com.apple.atve.sony.appletv"},   // Sony's own build
        {"hotstar", "hotstar", "in.startv.hotstar"},   // TMDB: "JioHotstar" (earlier "Disney Plus Hotstar")
        {"okko", "okko", "ru.more.play"},
        {"crunchyroll", "crunchyroll", "com.crunchyroll.crunchyroid"},
        {"oneplay", "oneplay", "net.cme.voyo.cz.tvapp"},    // Czechia: Voyo and O2 TV became Oneplay, same app
    };
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
        SERVICE_NAME.put("hotstar", "JioHotstar");
        SERVICE_NAME.put("okko", "Okko");
        SERVICE_NAME.put("crunchyroll", "Crunchyroll");
        SERVICE_NAME.put("oneplay", "Oneplay");
        SERVICE_NAME.put("plex", "Plex");
    }
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    private static final String REGION = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** The detail screen (poster, trailer, watchlist): its own thread, so it never waits behind the picks' images. */
    private final ExecutorService detailIo = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Backdrop> images = new LinkedHashMap<>();   // this hour's, built and ready to draw
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp, linkRetryAt;
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            listLanguage = cache.getString("language", cache.getBoolean("english", false) ? "en" : "pt");
            keyStamp = cache.getLong("keyStamp", 0);
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return !Config.TMDB_KEY.trim().isEmpty();
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    static String periodLabel() {
        return (Ui.ru ? PERIOD_LABEL_RU : Ui.es ? PERIOD_LABEL_ES : Ui.de ? PERIOD_LABEL_DE : Ui.en ? PERIOD_LABEL_EN : PERIOD_LABEL)
                [period(System.currentTimeMillis())];
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** This hour's picks (the first is the featured one; the arrows switch to the others). */
    List<Pick> now() {
        return now(null);
    }

    /** This hour's 3, movies only ("movie") or shows only ("tv"); null = both (the home screen tabs). */
    List<Pick> now(String kind) {
        List<Pick> list = new ArrayList<>();
        if (!hasKey(context)) return list;    // no TMDB key: no recommendations
        Map<String, Boolean> have = new HashMap<>();   // one look per app, not per title (this runs every 15 s)
        for (Pick p : picks) {
            if ((kind == null || kind.equals(p.kind))
                    && (!p.detailed || (p.pkg != null && have.computeIfAbsent(p.pkg, this::installed)))) list.add(p);   // app missing: skip
        }
        if (list.isEmpty()) return list;
        long hour = System.currentTimeMillis() / 3600_000L;
        // Three per hour, never two from the same franchise ("Law & Order" and "Law & Order: Special…").
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < list.size() && out.size() < 3; i++) {
            Pick p = list.get((int) ((hour * 3 + i) % list.size()));
            if (seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : SERVICE_NAME.get(p.app);
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    private File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), "recs"), p.backdrop.replace("/", ""));
    }

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    void refreshIfStale() {
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        long stamp = sourceStamp();   // a new key, country, animation choice or installed streaming-app set refetches
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean stale = newKey || p != period || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty()
                || !listLanguage.equals(Ui.language);
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !pick.detailed || !hasImage(pick) || needsLink(pick);
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (stale) fetchList(p);
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wanted()) {
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                        if (pick.pkg != null) download(pick);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                    findLink(pick);
                }
                save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
        }
    }

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        Map<String, Boolean> have = new HashMap<>();
        for (Pick p : picks) {
            if (p.detailed && p.pkg != null && have.computeIfAbsent(p.pkg, this::installed) && hasImage(p)
                    && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        List<Pick> todo = new ArrayList<>();
        for (Pick p : picks) if (!p.detailed || (p.pkg != null && !hasImage(p))) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    try {
                        if (!p.detailed) fetchDetails(p);
                        if (p.pkg != null) download(p);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /** The picks the carousel can show (its tabs filter by kind), whose details and images are fetched in the background. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        Map<String, Boolean> have = new HashMap<>();
        for (Pick p : picks) {
            if (p.detailed && (p.pkg == null || !have.computeIfAbsent(p.pkg, this::installed))) continue;
            if (seen.add(key(p))) out.add(p);
            if (out.size() >= CAROUSEL_MAX) break;
        }
        return out;
    }

    /** The carousel of a tab: everything ready to show, movies only ("movie"), shows only ("tv") or both (null). */
    List<Pick> all(String kind) {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        for (Pick p : wanted()) if (p.detailed && p.pkg != null && (kind == null || kind.equals(p.kind))) out.add(p);
        return out;
    }

    // MARK: - Browse: the genres, then a genre's titles (on your apps and in your Plex library)

    private static final String[] GENRE_NAMES = {"Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama",
            "Family", "Fantasy", "Horror", "Mystery", "Romance", "Science Fiction", "Thriller", "War", "Western"};
    /** TMDB's genre numbers for movies and for shows (0: none; shows have combined ones, like "Action & Adventure"). */
    private static final int[] GENRE_MOVIE = {28, 12, 16, 35, 80, 99, 18, 10751, 14, 27, 9648, 10749, 878, 53, 10752, 37};
    private static final int[] GENRE_TV = {10759, 10759, 16, 35, 80, 99, 18, 10751, 10765, 0, 9648, 0, 10765, 0, 10768, 37};
    /** What Plex calls each genre (a library may use any of these). */
    private static final String[][] GENRE_PLEX = {{"Action", "Action & Adventure"}, {"Adventure", "Action & Adventure"}, {"Animation"},
            {"Comedy"}, {"Crime"}, {"Documentary"}, {"Drama"}, {"Family"}, {"Fantasy", "Sci-Fi & Fantasy"}, {"Horror"}, {"Mystery"},
            {"Romance"}, {"Science Fiction", "Sci-Fi", "Sci-Fi & Fantasy"}, {"Thriller", "Suspense"}, {"War", "War & Politics"}, {"Western"}};
    private static final long BROWSE_LISTS_FOR = 30 * 60_000L;

    private final List<Pick> genreCards = new ArrayList<>();
    private long genresAt;
    private String genresLanguage = "";
    private volatile boolean genresLoading;
    private final Map<Integer, List<Pick>> genreLists = new HashMap<>();
    private final Map<Integer, Long> genreListsAt = new HashMap<>();
    private final Set<Integer> genreListsLoading = new HashSet<>();
    private String providerIds = "";
    private long providerIdsAt;

    /** The genre cards (each shows a popular title of that genre from your apps); empty until they have loaded. */
    List<Pick> genreCards() {
        synchronized (genreCards) { return new ArrayList<>(genreCards); }
    }

    /** Makes the genre cards (a request per genre, remembered for 6 hours); the listeners are told when they are ready. */
    void ensureGenres() {
        if (!hasKey(context) || genresLoading) return;
        synchronized (genreCards) {
            if (!genreCards.isEmpty() && System.currentTimeMillis() - genresAt < LIST_EVERY && genresLanguage.equals(Ui.language)) return;
        }
        genresLoading = true;
        detailIo.execute(() -> {
            try {
                fetchGenres();
            } catch (Exception e) {
                AppLog.w("genres: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                genresLoading = false;
                notifyListeners();
            }
        });
    }

    private void fetchGenres() throws Exception {
        String ids = providerFilter();
        Map<Integer, String> names = new HashMap<>();
        JSONArray list = get("/genre/movie/list?language=" + lang()).getJSONArray("genres");
        for (int i = 0; i < list.length(); i++) names.put(list.getJSONObject(i).getInt("id"), list.getJSONObject(i).getString("name"));
        List<Pick> out = new ArrayList<>();
        Set<String> used = new HashSet<>();     // each genre gets a photo of its own
        for (int i = 0; i < GENRE_NAMES.length; i++) {
            Pick p = new Pick();
            p.id = i;
            p.kind = "genre";
            p.title = names.containsKey(GENRE_MOVIE[i]) ? names.get(GENRE_MOVIE[i]) : GENRE_NAMES[i];
            p.meta = Ui.tr("Filmes e séries", "Movies and shows");
            p.detailed = true;
            String backdrop = null;
            try {
                backdrop = topBackdrop("movie", GENRE_MOVIE[i], ids, used);
                if (backdrop == null && GENRE_TV[i] > 0) backdrop = topBackdrop("tv", GENRE_TV[i], ids, used);
                if (backdrop != null) used.add(backdrop);
            } catch (Exception e) {
                AppLog.w("genre " + p.title + ": " + e.getMessage());
            }
            p.backdrop = backdrop;
            out.add(p);
        }
        synchronized (genreCards) {
            genreCards.clear();
            genreCards.addAll(out);
            genresAt = System.currentTimeMillis();
            genresLanguage = Ui.language;
        }
    }

    /** TMDB's number for each of your streaming services in your country, as "a|b|c" (empty: none known). */
    private String providerFilter() throws Exception {
        if (!providerIds.isEmpty() && System.currentTimeMillis() - providerIdsAt < LIST_EVERY) return providerIds;
        StringBuilder ids = new StringBuilder();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"));
            if (s != null && installed(s[2])) ids.append(ids.length() == 0 ? "" : "|").append(o.getInt("provider_id"));
        }
        providerIds = ids.toString();
        providerIdsAt = System.currentTimeMillis();
        return providerIds;
    }

    private String discoverQuery(String kind, int genre, String ids) throws Exception {
        return "/discover/" + kind + "?with_genres=" + genre + "&sort_by=popularity.desc&vote_count.gte=" + (kind.equals("movie") ? 100 : 50)
                + "&language=" + lang() + "&watch_region=" + REGION + "&include_adult=false"
                + (ids.isEmpty() ? "" : "&with_watch_monetization_types=flatrate&with_watch_providers=" + URLEncoder.encode(ids, "UTF-8"));
    }

    /** The photo of the most popular title of a genre that no earlier genre uses. */
    private String topBackdrop(String kind, int genre, String ids, Set<String> used) throws Exception {
        JSONArray results = get(discoverQuery(kind, genre, ids)).getJSONArray("results");
        for (int i = 0; i < results.length(); i++) {
            JSONObject o = results.getJSONObject(i);
            String b = o.optString("backdrop_path", "");
            if (!b.isEmpty() && !"null".equals(b) && !used.contains(b) && !(Config.HIDE_ANIMATION && animated(o))) return b;
        }
        return null;
    }

    /** A genre's list is kept per kind: all (null), "movie" or "tv". */
    private static int gkey(int genre, String kind) {
        return genre * 3 + (kind == null ? 0 : kind.equals("movie") ? 1 : 2);
    }

    /** The titles of a genre as the Browse grid shows them (those not on any of your apps drop out once their details are in). */
    List<Pick> genreList(int genre, String kind) {
        List<Pick> out = new ArrayList<>();
        synchronized (genreLists) {
            List<Pick> list = genreLists.get(gkey(genre, kind));
            if (list != null) for (Pick p : list) if (!p.detailed || p.pkg != null) out.add(p);
        }
        return out;
    }

    boolean genreLoading(int genre, String kind) {
        int key = gkey(genre, kind);
        synchronized (genreLists) { return genreListsLoading.contains(key) || !genreLists.containsKey(key); }
    }

    /**
     * Fetches a genre's titles (all, only movies or only shows): popular ones on your apps together with your unwatched
     * Plex titles of that genre, best rated first whatever they are on.
     */
    void loadGenre(int genre, String kind) {
        final int key = gkey(genre, kind);
        synchronized (genreLists) {
            Long at = genreListsAt.get(key);
            if (genreListsLoading.contains(key) || (at != null && System.currentTimeMillis() - at < BROWSE_LISTS_FOR)) return;
            genreListsLoading.add(key);
        }
        detailIo.execute(() -> {
            List<Pick> mixed = new ArrayList<>();
            try {
                String ids = providerFilter();
                boolean wantMovies = kind == null || kind.equals("movie"), wantShows = kind == null || kind.equals("tv");
                List<Pick> movies = wantMovies ? discoverGenre("movie", GENRE_MOVIE[genre], ids) : new ArrayList<>();
                List<Pick> shows = wantShows && GENRE_TV[genre] > 0 ? discoverGenre("tv", GENRE_TV[genre], ids) : new ArrayList<>();
                List<Pick> tmdb = new ArrayList<>();
                alternate(tmdb, new HashSet<>(), movies, shows, 40);
                List<Pick> plex = new ArrayList<>();
                if (plexOn() && installed(PLEX_PKG)) {
                    try {
                        plex = plexGenre(genre, kind);
                    } catch (Exception e) {
                        AppLog.w("plex genre: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                    }
                }
                rateByTmdb(plex);
                // A title you have on Plex and that is also on a streaming app shows once, as the Plex one (it is yours).
                Set<String> onPlex = new HashSet<>();
                for (Pick p : plex) if (p.tmdb > 0) onPlex.add(p.kind + ":" + p.tmdb);
                mixed.addAll(plex);
                for (Pick p : tmdb) if (!onPlex.contains(p.kind + ":" + p.id)) mixed.add(p);
                mixed.sort((a, b) -> Float.compare(b.rating, a.rating));     // best rated first, whatever it is on
                synchronized (genreLists) {
                    genreLists.put(key, mixed);
                    genreListsAt.put(key, System.currentTimeMillis());
                }
                notifyListeners();
                // The first ones get their details now (which app they are on, the description...); the rest as you scroll.
                for (int i = 0; i < Math.min(12, mixed.size()); i++) {
                    Pick p = mixed.get(i);
                    if (!p.detailed) {
                        try {
                            fetchDetails(p);
                        } catch (Exception ignored) {
                        }
                    }
                    if (i % 3 == 2) notifyListeners();
                }
            } catch (Exception e) {
                AppLog.w("genre: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                synchronized (genreLists) { genreLists.put(key, mixed); }
            } finally {
                synchronized (genreLists) { genreListsLoading.remove(key); }
                notifyListeners();
            }
        });
    }

    private final Map<String, Float> tmdbRatings = new HashMap<>();     // "movie:123" -> TMDB's score, for the session

    /**
     * Plex's own ratings come from different places (Rotten Tomatoes' audience for movies, TMDB for shows) and run
     * higher than TMDB's, which rates the streaming titles. Browse orders everything by one scale: TMDB's.
     */
    private void rateByTmdb(List<Pick> picks) {
        List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
        for (Pick p : picks) {
            if (p.tmdb <= 0) continue;
            jobs.add(tileIo.submit(() -> {
                try {
                    float r = tmdbRating(p.kind, p.tmdb);
                    if (r > 0) p.rating = r;
                } catch (Exception ignored) {
                }
            }));
        }
        for (java.util.concurrent.Future<?> job : jobs) {
            try {
                job.get(12, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
    }

    private float tmdbRating(String kind, int id) throws Exception {
        String key = kind + ":" + id;
        synchronized (tmdbRatings) {
            Float known = tmdbRatings.get(key);
            if (known != null) return known;
        }
        float score = (float) get("/" + kind + "/" + id + "?language=" + lang()).optDouble("vote_average", 0);
        synchronized (tmdbRatings) { tmdbRatings.put(key, score); }
        return score;
    }

    private List<Pick> discoverGenre(String kind, int genre, String ids) throws Exception {
        JSONArray results = get(discoverQuery(kind, genre, ids)).getJSONArray("results");
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < results.length() && out.size() < 20; i++) add(out, results.getJSONObject(i), kind);
        return out;
    }

    /** Your unwatched Plex titles of a genre (newest first), under whatever names the libraries give it. */
    private List<Pick> plexGenre(int genre, String kind) throws Exception {
        String machine = plexGet("/identity").getString("machineIdentifier");
        List<Pick> movies = new ArrayList<>(), shows = new ArrayList<>();
        JSONArray sections = plexGet("/library/sections").optJSONArray("Directory");
        for (int i = 0; sections != null && i < sections.length(); i++) {
            JSONObject section = sections.getJSONObject(i);
            String type = section.optString("type");
            if (!type.equals("movie") && !type.equals("show")) continue;
            boolean movie = type.equals("movie");
            if (kind != null && !kind.equals(movie ? "movie" : "tv")) continue;
            JSONArray tags = plexGet("/library/sections/" + section.getString("key") + "/genre").optJSONArray("Directory");
            StringBuilder keys = new StringBuilder();
            for (int t = 0; tags != null && t < tags.length(); t++) {
                for (String alias : GENRE_PLEX[genre]) {
                    if (tags.getJSONObject(t).optString("title").equalsIgnoreCase(alias)) {
                        keys.append(keys.length() == 0 ? "" : ",").append(tags.getJSONObject(t).getString("key"));
                    }
                }
            }
            if (keys.length() == 0) continue;
            JSONArray items = plexGet("/library/sections/" + section.getString("key") + "/all?type=" + (movie ? 1 : 2) + "&genre=" + keys
                    + "&unwatched=1&includeGuids=1&sort=addedAt:desc&X-Plex-Container-Start=0&X-Plex-Container-Size=20").optJSONArray("Metadata");
            for (int j = 0; items != null && j < items.length(); j++) {
                Pick p = plexPick(items.getJSONObject(j), movie ? "movie" : "tv", machine);
                if (p != null) (movie ? movies : shows).add(p);
            }
        }
        List<Pick> out = new ArrayList<>();
        alternate(out, new HashSet<>(), movies, shows, 20);
        return out;
    }

    /** Fetches a pick's details in the background (which app it is on, description, cast); the listeners are told. */
    void details(Pick p) {
        if (p == null || p.detailed || p.detailing) return;
        p.detailing = true;
        detailIo.execute(() -> {
            try {
                fetchDetails(p);
            } catch (Exception e) {
                AppLog.w("details: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                p.detailing = false;
                notifyListeners();
            }
        });
    }

    // MARK: - Continue watching (what you have started on your Plex server: your account's, not the server's)

    private final List<Pick> continuePicks = new ArrayList<>();
    private long continueAt;
    private volatile boolean continueLoading;

    /** The titles in progress, most recent first (empty without Plex, or when nothing is started). */
    List<Pick> continueList() {
        synchronized (continuePicks) { return new ArrayList<>(continuePicks); }
    }

    void refreshContinueNow() {
        continueAt = 0;
        refreshContinue();
    }

    /** Asks Plex again (not more than every 45 seconds); the listeners are told if something changed. */
    void refreshContinue() {
        if (!plexOn() || !installed(PLEX_PKG) || continueLoading) return;
        if (System.currentTimeMillis() - continueAt < 45_000L) return;
        continueLoading = true;
        detailIo.execute(() -> {
            try {
                String machine = plexGet("/identity").getString("machineIdentifier");
                JSONArray hubs = plexGet("/hubs/continueWatching?count=30&includeGuids=1").optJSONArray("Hub");
                List<Pick> fresh = new ArrayList<>();
                for (int h = 0; hubs != null && h < hubs.length(); h++) {
                    JSONArray items = hubs.getJSONObject(h).optJSONArray("Metadata");
                    for (int i = 0; items != null && i < items.length(); i++) {
                        Pick p = continuePick(items.getJSONObject(i), machine);
                        if (p != null) fresh.add(p);
                    }
                }
                boolean changed;
                synchronized (continuePicks) {
                    changed = fresh.size() != continuePicks.size();
                    List<Pick> merged = new ArrayList<>();
                    for (int i = 0; i < fresh.size(); i++) {
                        Pick f = fresh.get(i), old = null;
                        for (Pick o : continuePicks) if (key(o).equals(key(f))) old = o;
                        if (old == null) {
                            merged.add(f);
                            changed = true;
                        } else {        // the same title: its card stays, with the new progress
                            if (old.progress != f.progress || !old.meta.equals(f.meta)) changed = true;
                            old.progress = f.progress;
                            old.meta = f.meta;
                            merged.add(old);
                        }
                    }
                    for (int i = 0; i < merged.size() && !changed; i++) changed = !key(merged.get(i)).equals(key(continuePicks.get(i)));
                    continuePicks.clear();
                    continuePicks.addAll(merged);
                }
                continueAt = System.currentTimeMillis();
                plexState = 1;
                if (changed) notifyListeners();
            } catch (Exception e) {
                continueAt = System.currentTimeMillis();
                plexState = -1;
                AppLog.w("continue: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                continueLoading = false;
            }
        });
    }

    /** A movie or an episode in progress as a pick: the show's art and name, the episode in the details. */
    private static Pick continuePick(JSONObject o, String machine) throws Exception {
        String type = o.optString("type");
        boolean episode = type.equals("episode");
        if (!episode && !type.equals("movie")) return null;
        String rating = o.optString("ratingKey");
        String art = episode ? o.optString("grandparentArt", o.optString("art")) : o.optString("art");
        if (rating.isEmpty() || art.isEmpty()) return null;
        Pick p = new Pick();
        p.id = o.optInt("ratingKey");
        p.kind = episode ? "tv" : "movie";
        p.title = episode ? o.optString("grandparentTitle") : o.optString("title");
        p.plexKey = rating;
        p.backdrop = "plex" + rating + "-" + Integer.toHexString(art.hashCode()) + ".jpg";
        p.image = plexBase() + "/photo/:/transcode?width=" + Backdrop.W + "&height=" + Backdrop.H + "&minSize=1&upscale=1&url="
                + URLEncoder.encode(art, "UTF-8");
        String thumb = episode ? o.optString("grandparentThumb") : o.optString("thumb");
        if (!thumb.isEmpty()) {
            p.poster = plexBase() + "/photo/:/transcode?width=480&height=720&minSize=1&upscale=1&url=" + URLEncoder.encode(thumb, "UTF-8");
        }
        p.link = plexPlayLink(machine, rating);
        p.app = "plex";
        p.pkg = PLEX_PKG;
        p.detailed = true;
        p.summary = o.optString("summary");
        p.year = o.optString("year");
        String guid = episode ? o.optString("grandparentGuid") : o.optString("guid");
        if (guid.startsWith("plex://")) p.discoverId = guid.substring(guid.lastIndexOf('/') + 1);
        if (episode) p.showKey = o.optString("grandparentRatingKey");
        JSONArray guids = o.optJSONArray("Guid");
        for (int i = 0; !episode && guids != null && i < guids.length(); i++) {
            String id = guids.getJSONObject(i).optString("id");
            if (id.startsWith("tmdb://")) p.tmdb = Integer.parseInt(id.substring(7));
        }
        long duration = o.optLong("duration"), offset = o.optLong("viewOffset");
        p.progress = duration > 0 ? Math.max(0, Math.min(1, offset / (float) duration)) : 0;
        int minutes = duration > 0 ? (int) Math.max(1, (duration - offset) / 60_000L) : 0;
        List<String> parts = new ArrayList<>();
        if (episode) {
            parts.add(Ui.tr("T", "S") + o.optInt("parentIndex") + " · " + Ui.tr("E", "E") + o.optInt("index"));
            String name = o.optString("title");
            if (!name.isEmpty()) parts.add(name);
        }
        if (minutes > 0) parts.add(Ui.tr(minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min restantes" : minutes + " min restantes",
                minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m left" : minutes + " min left"));
        p.meta = String.join(" · ", parts);
        return p;
    }

    // MARK: - New (what arrived on your Plex server lately: movies, and shows by season)

    private static final int NEW_MAX = 30;
    private final List<Pick> newPicks = new ArrayList<>();
    private volatile long newAt;
    private volatile boolean newLoading;

    /** What arrived on Plex lately and isn't watched yet, newest first: movies, and shows as whole seasons. */
    List<Pick> newList() {
        synchronized (newPicks) { return new ArrayList<>(newPicks); }
    }

    /** Whether the list has been asked for at least once (until then the tab says "Loading…"). */
    boolean newLoaded() {
        return newAt != 0;
    }

    /** Asks Plex again (not more than every 10 minutes; a minute after a failure); the listeners are told of changes. */
    void refreshNew() {
        if (!plexOn() || !installed(PLEX_PKG) || newLoading) return;
        if (newAt != 0 && System.currentTimeMillis() - newAt < 10 * 60_000L) return;
        newLoading = true;
        detailIo.execute(() -> {
            try {
                List<Pick> fresh = fetchNew();
                boolean changed;
                synchronized (newPicks) {
                    changed = fresh.size() != newPicks.size();
                    for (int i = 0; i < fresh.size() && !changed; i++) {
                        changed = !key(fresh.get(i)).equals(key(newPicks.get(i))) || !fresh.get(i).meta.equals(newPicks.get(i).meta);
                    }
                    if (changed) {
                        newPicks.clear();
                        newPicks.addAll(fresh);
                    }
                }
                newAt = System.currentTimeMillis();
                plexState = 1;
                if (changed) notifyListeners();
            } catch (Exception e) {
                newAt = System.currentTimeMillis() - 9 * 60_000L;
                plexState = -1;
                AppLog.w("new: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                notifyListeners();      // "Loading…" gives way to the empty note
            } finally {
                newLoading = false;
            }
        });
    }

    /**
     * The newest unwatched movies and the seasons with episodes added lately, merged by date. Plex adds a show's
     * episodes one by one: they're grouped into their season, which counts from its newest episode and shows once
     * (with how many are left to watch), so a whole season waits there to be caught up on.
     */
    private List<Pick> fetchNew() throws Exception {
        String machine = plexGet("/identity").getString("machineIdentifier");
        List<Pick> found = new ArrayList<>();
        Map<Pick, Long> added = new HashMap<>();
        Map<String, Long> seasons = new LinkedHashMap<>();     // season key → its newest episode's date, newest first
        JSONArray sections = plexGet("/library/sections").optJSONArray("Directory");
        for (int i = 0; sections != null && i < sections.length(); i++) {
            JSONObject section = sections.getJSONObject(i);
            String type = section.optString("type");
            if (!type.equals("movie") && !type.equals("show")) continue;
            boolean movies = type.equals("movie");
            JSONArray items = plexGet("/library/sections/" + section.getString("key") + "/recentlyAdded?includeGuids=1"
                    + "&X-Plex-Container-Start=0&X-Plex-Container-Size=" + (movies ? 40 : 150)).optJSONArray("Metadata");
            for (int j = 0; items != null && j < items.length(); j++) {
                JSONObject o = items.getJSONObject(j);
                String t = o.optString("type");
                long at = o.optLong("addedAt");
                if (t.equals("movie")) {
                    if (o.optInt("viewCount") > 0) continue;        // seen already: not new to you
                    Pick p = plexPick(o, "movie", machine);
                    if (p == null) continue;
                    if (p.meta.endsWith(" · Plex")) p.meta = p.meta.substring(0, p.meta.length() - 7);   // everything here is
                    found.add(p);
                    added.put(p, at);
                } else if (t.equals("episode") || t.equals("season")) {
                    String season = t.equals("season") ? o.optString("ratingKey") : o.optString("parentRatingKey");
                    Long seen = seasons.get(season);
                    if (!season.isEmpty() && (seen == null || seen < at)) seasons.put(season, at);
                }
            }
        }
        List<String> keys = new ArrayList<>(seasons.keySet());
        if (keys.size() > NEW_MAX) keys = keys.subList(0, NEW_MAX);
        if (!keys.isEmpty()) {
            // The seasons, then their shows (summary, genre, cast, TMDB number), each in one request.
            JSONArray meta = plexGet("/library/metadata/" + String.join(",", keys)).optJSONArray("Metadata");
            List<JSONObject> left = new ArrayList<>();
            List<String> shows = new ArrayList<>();
            for (int i = 0; meta != null && i < meta.length(); i++) {
                JSONObject s = meta.getJSONObject(i);
                int total = s.optInt("leafCount"), watched = s.optInt("viewedLeafCount");
                if (total > 0 && watched >= total) continue;       // watched it all
                left.add(s);
                String show = s.optString("parentRatingKey");
                if (!show.isEmpty() && !shows.contains(show)) shows.add(show);
            }
            Map<String, JSONObject> showInfo = new HashMap<>();
            if (!shows.isEmpty()) {
                JSONArray info = plexGet("/library/metadata/" + String.join(",", shows) + "?includeGuids=1").optJSONArray("Metadata");
                for (int i = 0; info != null && i < info.length(); i++) showInfo.put(info.getJSONObject(i).optString("ratingKey"), info.getJSONObject(i));
            }
            for (JSONObject s : left) {
                Pick p = seasonPick(s, showInfo.get(s.optString("parentRatingKey")));
                if (p == null) continue;
                found.add(p);
                added.put(p, seasons.get(s.optString("ratingKey")));
            }
        }
        java.util.Collections.sort(found, (a, b) -> Long.compare(added.get(b), added.get(a)));
        List<Pick> out = new ArrayList<>(found.subList(0, Math.min(NEW_MAX, found.size())));
        // Watch plays the season's first episode not watched yet: asked only for the seasons that made the list.
        for (Pick p : out) {
            if (!"season".equals(p.plexType)) continue;
            JSONArray episodes = plexGet("/library/metadata/" + p.plexKey + "/children").optJSONArray("Metadata");
            String first = "";
            for (int i = 0; episodes != null && i < episodes.length() && first.isEmpty(); i++) {
                JSONObject e = episodes.getJSONObject(i);
                if (e.optInt("viewCount") == 0) first = e.optString("ratingKey");
            }
            if (!first.isEmpty()) p.link = plexPlayLink(machine, first);
        }
        return out;
    }

    /** A season as a card: the show's art and name, the season and its episodes (and how many are left) in the details. */
    private static Pick seasonPick(JSONObject s, JSONObject show) throws Exception {
        String key = s.optString("ratingKey"), art = s.optString("art", s.optString("parentArt"));
        if (key.isEmpty() || art.isEmpty()) return null;
        Pick p = new Pick();
        p.id = s.optInt("ratingKey");
        p.kind = "tv";
        p.plexType = "season";
        p.title = s.optString("parentTitle");
        p.plexKey = key;
        p.showKey = s.optString("parentRatingKey");
        p.backdrop = "plex" + key + "-" + Integer.toHexString(art.hashCode()) + ".jpg";
        p.image = plexBase() + "/photo/:/transcode?width=" + Backdrop.W + "&height=" + Backdrop.H + "&minSize=1&upscale=1&url="
                + URLEncoder.encode(art, "UTF-8");
        String thumb = s.optString("thumb", s.optString("parentThumb"));
        if (!thumb.isEmpty()) {
            p.poster = plexBase() + "/photo/:/transcode?width=480&height=720&minSize=1&upscale=1&url=" + URLEncoder.encode(thumb, "UTF-8");
        }
        p.app = "plex";
        p.pkg = PLEX_PKG;
        p.detailed = true;
        String guid = s.optString("parentGuid");
        if (guid.startsWith("plex://")) p.discoverId = guid.substring(guid.lastIndexOf('/') + 1);
        p.summary = s.optString("summary");
        if (show != null) {
            if (p.summary.isEmpty()) p.summary = show.optString("summary");
            p.year = show.optString("year");
            p.rating = (float) show.optDouble("audienceRating", show.optDouble("rating", 0));
            JSONArray roles = show.optJSONArray("Role");
            List<String> names = new ArrayList<>();
            for (int i = 0; roles != null && i < roles.length() && names.size() < 3; i++) names.add(roles.getJSONObject(i).optString("tag"));
            p.cast = String.join(", ", names);
            JSONArray guids = show.optJSONArray("Guid");
            for (int i = 0; guids != null && i < guids.length(); i++) {
                String id = guids.getJSONObject(i).optString("id");
                if (id.startsWith("tmdb://")) p.tmdb = Integer.parseInt(id.substring(7));
            }
        }
        int total = s.optInt("leafCount"), watched = s.optInt("viewedLeafCount");
        List<String> parts = new ArrayList<>();
        parts.add(Ui.tr("Temporada ", "Season ") + s.optInt("index"));
        if (total > 0) parts.add(total + " " + (total == 1 ? Ui.tr("episódio", "episode") : Ui.tr("episódios", "episodes")));
        if (watched > 0) parts.add((total - watched) + " " + Ui.tr("por ver", "to watch"));
        p.meta = String.join(" · ", parts);
        return p;
    }

    /** The TMDB number of a show on your server (for its trailer), from its Plex key. */
    private int plexTmdb(String key) throws Exception {
        JSONArray meta = plexGet("/library/metadata/" + key + "?includeGuids=1").optJSONArray("Metadata");
        JSONArray guids = meta == null || meta.length() == 0 ? null : meta.getJSONObject(0).optJSONArray("Guid");
        for (int i = 0; guids != null && i < guids.length(); i++) {
            String id = guids.getJSONObject(i).optString("id");
            if (id.startsWith("tmdb://")) return Integer.parseInt(id.substring(7));
        }
        return 0;
    }

    // MARK: - Trailer and Plex watchlist (looked up when the detail screen opens)

    private static final String PLEX_DISCOVER = "https://discover.provider.plex.tv", PLEX_METADATA = "https://metadata.provider.plex.tv";

    /** Finds the trailer's YouTube id, the title's id in Plex's catalog (if it isn't from your server) and its watchlist state. */
    void lookup(Pick p) {
        if (p == null || p.lookedUp || p.lookingUp) return;
        p.lookingUp = true;
        detailIo.execute(() -> {
            try {
                if (p.plexKey != null && p.tmdb == 0 && p.showKey != null && !p.showKey.isEmpty()) p.tmdb = plexTmdb(p.showKey);
                int tmdbId = p.plexKey != null ? p.tmdb : p.id;
                if (tmdbId > 0) p.trailer = trailer(p.kind, tmdbId);
                if (plexOn()) {
                    if (p.discoverId == null) p.discoverId = resolveDiscover(p);
                    if (p.discoverId != null) p.watchlisted = watchlistState(p.discoverId) ? 1 : 0;
                }
                p.lookedUp = true;
            } catch (Exception e) {
                AppLog.w("lookup: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                p.lookingUp = false;
                notifyListeners();
            }
        });
    }

    /** The best YouTube trailer TMDB knows: an official "Trailer", else a "Teaser"; null if none. */
    private String trailer(String kind, int id) throws Exception {
        JSONArray videos = get("/" + kind + "/" + id + "/videos?language=" + lang() + "&include_video_language=" + lang() + ",en,null").getJSONArray("results");
        String best = null;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < videos.length(); i++) {
            JSONObject v = videos.getJSONObject(i);
            if (!v.optString("site").equals("YouTube")) continue;
            int rank = (v.optString("type").equals("Trailer") ? 0 : v.optString("type").equals("Teaser") ? 4 : 99) + (v.optBoolean("official") ? 0 : 2);
            if (rank < bestRank) {
                bestRank = rank;
                best = v.optString("key");
            }
        }
        return bestRank < 99 ? best : null;
    }

    /** The title's id on Plex's catalog, searched by name, kind and year (a title from your server already has it). */
    private String resolveDiscover(Pick p) throws Exception {
        String url = PLEX_DISCOVER + "/library/search?query=" + URLEncoder.encode(p.title, "UTF-8")
                + "&limit=8&searchTypes=movies,tv&searchProviders=discover&includeMetadata=1";
        JSONArray groups = plexCloud("GET", url).getJSONObject("MediaContainer").optJSONArray("SearchResults");
        String type = p.kind.equals("movie") ? "movie" : "show";
        for (int g = 0; groups != null && g < groups.length(); g++) {
            JSONArray results = groups.getJSONObject(g).optJSONArray("SearchResult");
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject m = results.getJSONObject(i).optJSONObject("Metadata");
                if (m == null || !type.equals(m.optString("type")) || !m.optString("title").equalsIgnoreCase(p.title)) continue;
                if (p.year.isEmpty() || p.year.equals(m.optString("year"))) return m.optString("ratingKey");
            }
        }
        return null;
    }

    private boolean watchlistState(String id) throws Exception {
        JSONObject state = plexCloud("GET", PLEX_METADATA + "/library/metadata/" + id + "/userState").getJSONObject("MediaContainer").optJSONObject("UserState");
        return state != null && state.has("watchlistedAt");
    }

    /** Adds or removes the title on your Plex watchlist (your plex.tv account's, shared by all your Plex apps). */
    void watchlist(Pick p, boolean add, Consumer<Boolean> done) {
        detailIo.execute(() -> {
            boolean ok = false;
            try {
                plexCloud("PUT", PLEX_DISCOVER + "/actions/" + (add ? "addToWatchlist" : "removeFromWatchlist") + "?ratingKey=" + p.discoverId);
                p.watchlisted = add ? 1 : 0;
                ok = true;
            } catch (Exception e) {
                AppLog.w("watchlist: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
            final boolean result = ok;
            main.post(() -> done.accept(result));
            notifyListeners();
        });
    }

    /** A call to plex.tv's cloud (always https) with your Plex token; returns the JSON, or an empty object for an empty answer. */
    private static JSONObject plexCloud(String method, String url) throws IOException, org.json.JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-Plex-Token", Config.PLEX_TOKEN.trim());
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("Plex rejected the token");
            if (code / 100 != 2) throw new IOException("Plex cloud returned HTTP " + code);
            String body = read(c.getInputStream()).trim();
            return body.isEmpty() ? new JSONObject() : new JSONObject(body);
        } finally {
            c.disconnect();
        }
    }

    // MARK: - Tiles (the Browse grids: small images, many at once, from the same files as the posters and photos)

    private final Map<String, Bitmap> tiles = new LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) { return size() > 72; }
    };
    private final Set<String> tilePending = new HashSet<>(), tileFailed = new HashSet<>();
    private final ExecutorService tileIo = Executors.newFixedThreadPool(3);

    /**
     * A small image for a Browse tile: a genre's photo or a title's poster, decoded at about the size it's drawn. Null
     * until it has loaded (in the background; the listeners are told).
     */
    Bitmap tile(Pick p) {
        if (p == null) return null;
        final boolean genre = "genre".equals(p.kind);
        final String url = genre ? (p.backdrop == null ? null : IMAGES + p.backdrop) : p.poster;
        if (url == null) return null;
        final String key = (genre ? "g:" : "p:") + key(p);
        synchronized (tiles) {
            Bitmap b = tiles.get(key);
            if (b != null) return b;
            if (tileFailed.contains(key) || !tilePending.add(key)) return null;
        }
        tileIo.execute(() -> {
            boolean ok = false;
            try {
                File dir = new File(context.getCacheDir(), "recs");
                File f = genre ? imageFile(p) : new File(dir, "poster-" + key(p).replace(":", "-") + ".jpg");
                if (!f.exists()) fetch(url, f);
                // The focused tile's size (1.07×): a genre strip of about 452×173, a poster of about 231×347.
                Bitmap b = genre ? decodeCover(f, Backdrop.ui(460), Backdrop.ui(180)) : decodeCover(f, Backdrop.ui(240), Backdrop.ui(360));
                synchronized (tiles) { tiles.put(key, b); }
                ok = true;
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                AppLog.w("tile: " + e.getMessage());
            } finally {
                synchronized (tiles) {
                    tilePending.remove(key);
                    if (!ok) tileFailed.add(key);
                }
                notifyListeners();
            }
        });
        return null;
    }

    /**
     * The image at the smallest size that still covers w×h: a power-of-two subsample, then a filtered scale for the
     * rest. A full-size poster is 3 to 6 MB in memory; at its drawn size, a tenth of that.
     */
    private static Bitmap decodeCover(File f, int w, int h) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("unreadable image");
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= w && bounds.outHeight / (opts.inSampleSize * 2) >= h) opts.inSampleSize *= 2;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
        if (b == null) throw new IOException("unreadable image");
        float scale = Math.max((float) w / b.getWidth(), (float) h / b.getHeight());
        if (scale > 0.87f) return b;        // close enough: not worth a second copy
        Bitmap small = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scale), Math.round(b.getHeight() * scale), true);
        if (small != b) b.recycle();
        return small;
    }

    /** Browse's tiles and the detail screen's posters, when the home screen is hidden: they come back from the disk. */
    void dropImages() {
        synchronized (tiles) {
            tiles.clear();
            tileFailed.clear();         // a network blip doesn't leave a tile gray for good
        }
        synchronized (posters) {
            posters.clear();
            posterFailed.clear();
        }
    }

    // MARK: - Posters (the detail screen: one at a time, from TMDB or your Plex server)

    private final Map<String, Bitmap> posters = new LinkedHashMap<>();
    private final Set<String> posterPending = new HashSet<>(), posterFailed = new HashSet<>();

    /** The pick's poster, or null: it is loaded in the background and the listeners are told when it's ready. */
    Bitmap poster(Pick p) {
        if (p == null || p.poster == null) return null;
        final String key = key(p);
        synchronized (posters) {
            Bitmap b = posters.get(key);
            if (b != null) return b;
            if (posterFailed.contains(key) || !posterPending.add(key)) return null;
        }
        detailIo.execute(() -> {
            boolean ok = false;
            try {
                File dir = new File(context.getCacheDir(), "recs");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File f = new File(dir, "poster-" + key.replace(":", "-") + ".jpg");
                if (!f.exists()) {
                    fetch(p.poster, f);
                    prune(dir);
                }
                Bitmap b = decodeCover(f, Backdrop.ui(480), Backdrop.ui(720));     // drawn at 480×720
                synchronized (posters) {
                    posters.put(key, b);
                    while (posters.size() > 3) posters.remove(posters.keySet().iterator().next());
                }
                ok = true;
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                AppLog.w("poster: " + e.getMessage());
            } finally {
                synchronized (posters) {
                    posterPending.remove(key);
                    if (!ok) posterFailed.add(key);
                }
                notifyListeners();
            }
        });
        return null;
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return p.plexKey != null ? "plex:" + p.plexKey : p.kind + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB

    private void fetchList(int p) throws Exception {
        // The services the TV has, by their TMDB ids in your region.
        Map<Integer, String[]> services = new HashMap<>();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"));
            if (s != null && installed(s[2])) services.put(o.getInt("provider_id"), s);
        }
        if (services.isEmpty()) throw new IOException("none of the TV's streaming services are on TMDB");
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        String common = "&language=" + lang() + "&watch_region=" + REGION + "&with_watch_monetization_types=flatrate&include_adult=false"
                + "&sort_by=popularity.desc&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8")
                + (Config.HIDE_ANIMATION ? "&without_genres=" + ANIMATION : "");
        // 1. Trending this week (TMDB goes by what people are watching and searching for right now), only what is
        //    on one of the TV's services: the details say where (and are then ready).
        List<Pick> hotMovies = new ArrayList<>(), hotShows = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            JSONArray hot = get("/trending/" + kind + "/week?language=" + lang()).getJSONArray("results");
            List<Pick> candidates = new ArrayList<>();
            for (int i = 0; i < hot.length() && i < 20; i++) add(candidates, hot.getJSONObject(i), kind);
            for (Pick pick : candidates) {
                try {
                    fetchDetails(pick);
                } catch (Exception e) {
                    continue;
                }
                if (pick.pkg != null) (kind.equals("movie") ? hotMovies : hotShows).add(pick);
            }
        }
        // 2. Recent releases on the TV's services: movies from the past year and shows with a new episode in the
        //    past 3 months.
        JSONArray movies = get("/discover/movie?vote_count.gte=30&primary_release_date.gte=" + day(-365)
                + "&primary_release_date.lte=" + day(0) + common).getJSONArray("results");
        JSONArray shows = get("/discover/tv?vote_count.gte=20&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0)
                + common).getJSONArray("results");
        List<Pick> newMovies = new ArrayList<>(), newShows = new ArrayList<>();
        for (int i = 0; i < movies.length(); i++) add(newMovies, movies.getJSONObject(i), "movie");
        for (int i = 0; i < shows.length(); i++) add(newShows, shows.getJSONObject(i), "tv");
        // Trending first, then the new releases; movies and shows alternating, without repeats, up to 24.
        List<Pick> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        alternate(list, seen, hotMovies, hotShows, 24);
        alternate(list, seen, newMovies, newShows, 24);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        // 3. Unwatched titles from your own Plex server, one in between each of the above. Plex being down or its token
        //    refused only means no Plex titles: the rest of the list is unaffected.
        if (plexOn() && installed(PLEX_PKG)) {
            try {
                List<Pick> plex = fetchPlex();
                List<Pick> mixed = new ArrayList<>();
                for (int i = 0; i < Math.max(list.size(), plex.size()); i++) {
                    if (i < list.size()) mixed.add(list.get(i));
                    if (i < plex.size()) mixed.add(plex.get(i));
                }
                list = mixed;
                plexState = 1;
            } catch (Exception e) {
                plexState = -1;
                AppLog.w("plex: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        }
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
    }

    // MARK: - Plex (your own server: its newest unwatched movies and shows; the token only travels as a header)

    private volatile int plexState;      // the last answer from the server: 1 it answered, -1 it didn't, 0 not asked yet

    /** For the settings: whether the Plex server answered the last time it was asked (1), didn't (-1), or unknown (0). */
    int plexState() { return plexState; }

    static boolean plexOn() {
        return !Config.PLEX_URL.trim().isEmpty() && !Config.PLEX_TOKEN.trim().isEmpty();
    }

    private static String plexBase() {
        String base = Config.PLEX_URL.trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static JSONObject plexGet(String path) throws IOException, org.json.JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(plexBase() + path).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-Plex-Token", Config.PLEX_TOKEN.trim());
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("Plex rejected the token");
            if (code != 200) throw new IOException("Plex returned HTTP " + code);
            return new JSONObject(read(c.getInputStream())).getJSONObject("MediaContainer");
        } finally {
            c.disconnect();
        }
    }

    /** The newest unwatched movies and shows of the server's libraries, alternating, up to PLEX_MAX. */
    private List<Pick> fetchPlex() throws Exception {
        String machine = plexGet("/identity").getString("machineIdentifier");
        List<Pick> movies = new ArrayList<>(), shows = new ArrayList<>();
        JSONArray sections = plexGet("/library/sections").optJSONArray("Directory");
        for (int i = 0; sections != null && i < sections.length(); i++) {
            JSONObject section = sections.getJSONObject(i);
            String type = section.optString("type");
            if (!type.equals("movie") && !type.equals("show")) continue;
            boolean movie = type.equals("movie");
            JSONArray items = plexGet("/library/sections/" + section.getString("key") + "/all?type=" + (movie ? 1 : 2)
                    + "&unwatched=1&includeGuids=1&sort=addedAt:desc&X-Plex-Container-Start=0&X-Plex-Container-Size=20").optJSONArray("Metadata");
            for (int j = 0; items != null && j < items.length(); j++) {
                Pick p = plexPick(items.getJSONObject(j), movie ? "movie" : "tv", machine);
                if (p != null) (movie ? movies : shows).add(p);
            }
        }
        List<Pick> out = new ArrayList<>();
        alternate(out, new HashSet<>(), movies, shows, PLEX_MAX);
        return out;
    }

    /** The link that makes the Plex TV app play an item of your server. */
    static String plexPlayLink(String machine, String ratingKey) throws Exception {
        return "plex://watch/video?uri=" + URLEncoder.encode("server://" + machine + "/com.plexapp.plugins.library/library/metadata/" + ratingKey, "UTF-8");
    }

    private static Pick plexPick(JSONObject o, String kind, String machine) throws Exception {
        String art = o.optString("art", ""), rating = o.optString("ratingKey", "");
        if (art.isEmpty() || rating.isEmpty()) return null;
        JSONArray genres = o.optJSONArray("Genre");
        String genre = genres != null && genres.length() > 0 ? genres.getJSONObject(0).optString("tag") : "";
        if (Config.HIDE_ANIMATION && genre.equals("Animation")) return null;
        Pick p = new Pick();
        p.id = o.optInt("ratingKey");
        p.kind = kind;
        p.title = o.optString("title");
        p.plexKey = rating;
        p.backdrop = "plex" + rating + "-" + Integer.toHexString(art.hashCode()) + ".jpg";
        p.image = plexBase() + "/photo/:/transcode?width=" + Backdrop.W + "&height=" + Backdrop.H + "&minSize=1&upscale=1&url="
                + URLEncoder.encode(art, "UTF-8");
        // The Plex TV app plays the item (a show: its next episode) for this link: found in the app itself, the older
        // "plex://preplay/..." and "server://..." forms are ignored by it.
        p.link = plexPlayLink(machine, rating);
        p.app = "plex";
        p.pkg = PLEX_PKG;
        p.summary = o.optString("summary");
        String guid = o.optString("guid");      // "plex://movie/6328b12f...": the last part is the cloud catalog's id
        if (guid.startsWith("plex://")) p.discoverId = guid.substring(guid.lastIndexOf('/') + 1);
        JSONArray guids = o.optJSONArray("Guid");
        for (int i = 0; guids != null && i < guids.length(); i++) {
            String id = guids.getJSONObject(i).optString("id");
            if (id.startsWith("tmdb://")) p.tmdb = Integer.parseInt(id.substring(7));
        }
        String thumb = o.optString("thumb", "");
        if (!thumb.isEmpty()) {
            p.poster = plexBase() + "/photo/:/transcode?width=480&height=720&minSize=1&upscale=1&url=" + URLEncoder.encode(thumb, "UTF-8");
        }
        p.year = o.optString("year");
        p.rating = (float) o.optDouble("audienceRating", o.optDouble("rating", 0));
        JSONArray roles = o.optJSONArray("Role");
        List<String> names = new ArrayList<>();
        for (int i = 0; roles != null && i < roles.length() && names.size() < 3; i++) names.add(roles.getJSONObject(i).optString("tag"));
        p.cast = String.join(", ", names);
        List<String> parts = new ArrayList<>();
        if (kind.equals("tv")) parts.add(Ui.tr("Série", "Series"));
        if (!genre.isEmpty()) parts.add(genre);
        if (kind.equals("movie")) {
            int m = o.optInt("duration") / 60_000;
            if (m > 0) parts.add(Ui.tr(m >= 60 ? (m / 60) + " h " + (m % 60) + " min" : m + " min",
                    m >= 60 ? (m / 60) + "h " + (m % 60) + "m" : m + "m"));
        } else {
            int n = o.optInt("childCount");
            if (n > 0) parts.add(Ui.tr(n == 1 ? "1 temporada" : n + " temporadas", n == 1 ? "1 season" : n + " seasons"));
        }
        parts.add("Plex");
        p.meta = String.join(" · ", parts);
        p.detailed = true;
        return p;
    }

    /** Language of titles and genres; the services come from the region. */
    private static String lang() {
        if (Ui.ru) return "ru-RU";
        if (Ui.es) return "ES".equals(java.util.Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        if (Ui.de) return "de-DE";   // TMDB's de-AT and de-CH have few translations of their own
        return Ui.en ? "en-US" : "pt-BR";
    }

    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        if (Config.HIDE_ANIMATION && animated(o)) return;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        String poster = o.optString("poster_path", "");
        if (!poster.isEmpty() && !"null".equals(poster)) p.poster = POSTERS + poster;
        p.rating = (float) o.optDouble("vote_average", 0);
        list.add(p);
    }

    private static boolean animated(JSONObject o) {
        JSONArray genres = o.optJSONArray("genre_ids");
        for (int i = 0; genres != null && i < genres.length(); i++) if (genres.optInt(i) == ANIMATION) return true;
        return false;
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws Exception {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images,credits&include_image_language=null," + Ui.language + ",en");
        p.title = title(p, o);
        p.summary = o.optString("overview");
        String poster = o.optString("poster_path", "");
        p.poster = poster.isEmpty() || "null".equals(poster) ? null : POSTERS + poster;
        String date = o.optString(p.kind.equals("movie") ? "release_date" : "first_air_date");
        p.year = date.length() >= 4 ? date.substring(0, 4) : "";
        p.rating = (float) o.optDouble("vote_average", 0);
        JSONObject credits = o.optJSONObject("credits");
        JSONArray people = credits == null ? null : credits.optJSONArray("cast");
        List<String> names = new ArrayList<>();
        for (int i = 0; people != null && i < people.length() && names.size() < 3; i++) names.add(people.getJSONObject(i).optString("name"));
        p.cast = String.join(", ", names);
        String best = bestBackdrop(o.optJSONObject("images"));
        if (best != null) p.backdrop = best;
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        if (p.kind.equals("tv")) parts.add(Ui.tr("Série", "Series"));
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(Ui.en ? name : genre(name));
        }
        if (p.kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            if (minutes > 0 && Ui.ru) parts.add(minutes >= 60 ? (minutes / 60) + " ч " + (minutes % 60) + " мин" : minutes + " мин");
            else if (minutes > 0 && Ui.en) parts.add(minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m");
            else if (minutes > 0) parts.add(minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min");
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0 && Ui.ru) parts.add(Russian.count(seasons, "сезон", "сезона", "сезонов"));
            else if (seasons > 0 && Ui.en) parts.add(seasons == 1 ? "1 season" : seasons + " seasons");
            else if (seasons > 0 && Ui.de) parts.add(seasons == 1 ? "1 Staffel" : seasons + " Staffeln");
            else if (seasons > 0) parts.add(seasons == 1 ? "1 temporada" : seasons + " temporadas");
        }
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(REGION);
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length() && p.app == null; i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"));
            if (s != null && installed(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        if (p.app != null) parts.add(SERVICE_NAME.get(p.app));
        p.meta = String.join(" · ", parts);
        p.detailed = true;
    }

    /**
     * Without a title in the TV's language TMDB answers with the original one, so a Korean series on a German TV reads
     * "닥터X: 하얀 마피아의 시대". Then the English title, when TMDB has one (one more request, only for those).
     */
    private String title(Pick p, JSONObject o) {
        String field = p.kind.equals("movie") ? "title" : "name";
        String own = o.optString(field, p.title);
        String original = o.optString("original_language");
        if (Ui.en || own.isEmpty() || !own.equals(o.optString("original_" + field))
                || Ui.language.equals(original) || "en".equals(original)) return own;
        try {
            String english = get("/" + p.kind + "/" + p.id + "?language=en-US").optString(field);
            return english.isEmpty() ? own : english;
        } catch (Exception e) {
            return own;
        }
    }

    /** TV genres that TMDB returns in English even in pt-BR. */
    private static final String[][] GENRES_PT = {
        {"Action & Adventure", "Ação e Aventura"}, {"Sci-Fi & Fantasy", "Ficção Científica e Fantasia"},
        {"War & Politics", "Guerra e Política"}, {"Kids", "Infantil"}, {"News", "Notícias"}, {"Soap", "Novela"},
        {"Talk", "Entrevistas"},
    };
    private static final String[][] GENRES_ES = {
        {"Action & Adventure", "Acción y Aventura"}, {"Sci-Fi & Fantasy", "Ciencia ficción y Fantasía"},
        {"War & Politics", "Guerra y Política"}, {"Kids", "Infantil"}, {"News", "Noticias"},
        {"Soap", "Telenovela"}, {"Talk", "Entrevistas"},
    };
    private static final String[][] GENRES_DE = {
        {"Action & Adventure", "Action & Abenteuer"}, {"War & Politics", "Krieg & Politik"}, {"Kids", "Kinder"},
        {"News", "Nachrichten"}, {"Soap", "Seifenoper"}, {"Talk", "Talkshow"},
    };

    private static String genre(String name) {
        if (Ui.ru) return Russian.text(name);
        for (String[] g : Ui.es ? GENRES_ES : Ui.de ? GENRES_DE : GENRES_PT) if (g[0].equalsIgnoreCase(name)) return g[1];
        return name;
    }

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), the closest to 1920 wide
     * (larger only costs download, disk and decoding) and, on a tie, the highest rated.
     */
    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 1920) * 100 - Math.max(0, width - 1920) + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    /** The SERVICES row for a TMDB name: the installed one first, when a service has more than one package. */
    private String[] service(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        String[] first = null;
        for (String[] s : SERVICES) {
            if (!(s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(s[0]))) continue;
            if (installed(s[2])) return s;
            if (first == null) first = s;
        }
        return first;
    }

    private boolean installed(String pkg) {
        return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
    }

    private long sourceStamp() {
        long stamp = (Config.TMDB_KEY.hashCode() * 31L + REGION.hashCode()) * 31L + (Config.HIDE_ANIMATION ? 1 : 0);
        stamp = stamp * 31L + (plexOn() ? (Config.PLEX_URL + Config.PLEX_TOKEN).hashCode() : 0);
        for (String[] service : SERVICES) stamp = stamp * 31L + (installed(service[2]) ? service[2].hashCode() : 0);
        return stamp;
    }

    // MARK: - Watchmode (the title's own page in its app; the free plan's web addresses are what the apps open)

    private static String linkKey(Pick p) {
        return "wm:" + p.kind + p.id + p.app;
    }

    private boolean needsLink(Pick p) {
        return linksOn() && p.pkg != null && p.link == null
                && cache.getString(linkKey(p), null) == null && System.currentTimeMillis() >= linkRetryAt;
    }

    /** A key is set and Watchmode hasn't refused it (a refused key isn't tried again until it changes). */
    private boolean linksOn() {
        String key = Config.WATCHMODE_KEY.trim();
        return !key.isEmpty() && cache.getInt("wm:refused", 0) != key.hashCode();
    }

    /** Sets p.link from what was looked up before, or looks it up (2 requests of the free plan's 2,500 a month). */
    private void findLink(Pick p) {
        if (!linksOn() || p.pkg == null || p.link != null) return;
        String saved = cache.getString(linkKey(p), null);
        if (saved != null) {
            p.link = saved.isEmpty() ? null : saved;
            return;
        }
        if (System.currentTimeMillis() < linkRetryAt) return;
        try {
            String link = "";
            JSONArray found = new JSONObject(watchmode("/search/?search_field=tmdb_" + p.kind + "_id&search_value=" + p.id))
                    .optJSONArray("title_results");
            if (found != null && found.length() > 0) {
                JSONArray sources = new JSONArray(watchmode("/title/" + found.getJSONObject(0).getInt("id") + "/sources/?regions=" + REGION));
                String[] service = null;
                for (String[] s : SERVICES) if (s[1].equals(p.app)) service = s;
                for (int i = 0; service != null && i < sources.length() && link.isEmpty(); i++) {
                    JSONObject s = sources.getJSONObject(i);
                    String name = s.optString("name").toLowerCase(Locale.ROOT).replace(" ", "");
                    String want = service[0].replace(" ", "");
                    boolean same = want.equals("max") ? name.equals("max") : name.contains(want);
                    if (same && s.optString("type").equals("sub") && s.optString("web_url").startsWith("https://")) link = s.optString("web_url");
                }
            }
            cache.edit().putString(linkKey(p), link).apply();
            p.link = link.isEmpty() ? null : link;
        } catch (Exception e) {
            if ("Watchmode returned HTTP 401".equals(e.getMessage())) {
                cache.edit().putInt("wm:refused", Config.WATCHMODE_KEY.trim().hashCode()).apply();
            }
            linkRetryAt = System.currentTimeMillis() + 10 * 60_000L;   // quota or network: not again right away
            AppLog.w("watchmode: " + e.getMessage());
        }
    }

    private static String watchmode(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.watchmode.com/v1" + path + (path.contains("?") ? "&" : "?")
                + "apiKey=" + URLEncoder.encode(Config.WATCHMODE_KEY.trim(), "UTF-8")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Watchmode returned HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        String key = readKey();
        if (key == null) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private String readKey() {
        String key = Config.TMDB_KEY.trim();
        return key.isEmpty() ? null : key;
    }

    // MARK: - Images (TMDB originals on disk, up to 24 so the whole list fits; this hour's kept ready in memory)

    private void download(Pick p) throws IOException {
        File f = imageFile(p);
        if (f.exists()) return;
        fetch(p.image != null ? p.image : IMAGES + p.backdrop, f);
        prune(f.getParentFile());
    }

    /** Saves an image to `f` (through a .part file, so a cut-off download never looks finished). */
    private void fetch(String url, File f) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        if (plexOn() && url.startsWith(plexBase())) c.setRequestProperty("X-Plex-Token", Config.PLEX_TOKEN.trim());
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /** Reads the photo (≥ the UI's width) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight + " → " + Backdrop.W + "×" + Backdrop.H);
            boolean forLight = light;
            Backdrop backdrop = Backdrop.make(b, forLight);
            if (forLight != light) return;   // the design changed midway: this one is stale
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                while (images.size() > (forLight ? 14 : 4)) images.remove(images.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    void trim() {
        // Explore's picks stay: the home screen opens on that tab, so its cards are never gray when you come back.
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Pick p : now(null)) if (p.backdrop != null) keep.add(p.backdrop);
        synchronized (images) {
            images.keySet().retainAll(keep);
        }
    }

    private static void prune(File dir) {
        // A download cut off when the system stopped the app leaves its .part behind.
        long stale = System.currentTimeMillis() - 10 * 60_000L;
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".part"));
        for (int i = 0; parts != null && i < parts.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (parts[i].lastModified() < stale) parts[i].delete();
        }
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        if (files == null || files.length <= 220) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - 220; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) {
                a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop).put("link", p.link == null ? "" : p.link)
                        .put("meta", p.meta).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                        .put("detailed", p.detailed).put("plexKey", p.plexKey == null ? "" : p.plexKey)
                        .put("image", p.image == null ? "" : p.image).put("summary", p.summary).put("poster", p.poster == null ? "" : p.poster)
                        .put("cast", p.cast).put("year", p.year).put("rating", p.rating)
                        .put("tmdb", p.tmdb).put("discoverId", p.discoverId == null ? "" : p.discoverId));
            }
            SharedPreferences.Editor e = cache.edit().putString("picks", a.toString()).putInt("period", period).putLong("listAt", listAt)
                    .putString("language", listLanguage)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION);
            // Watchmode links of titles no longer in the list go (the refused-key mark stays).
            Set<String> keep = new HashSet<>();
            for (Pick p : picks) keep.add(linkKey(p));
            for (String k : cache.getAll().keySet()) if (k.startsWith("wm:") && !k.equals("wm:refused") && !keep.contains(k)) e.remove(k);
            e.apply();
        } catch (Exception ignored) {
        }
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.link = o.optString("link").isEmpty() ? null : o.optString("link");
            p.meta = o.optString("meta");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.detailed = o.optBoolean("detailed");
            p.plexKey = o.optString("plexKey").isEmpty() ? null : o.optString("plexKey");
            p.image = o.optString("image").isEmpty() ? null : o.optString("image");
            p.summary = o.optString("summary");
            p.poster = o.optString("poster").isEmpty() ? null : o.optString("poster");
            p.cast = o.optString("cast");
            p.year = o.optString("year");
            p.rating = (float) o.optDouble("rating", 0);
            p.tmdb = o.optInt("tmdb");
            p.discoverId = o.optString("discoverId").isEmpty() ? null : o.optString("discoverId");
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
