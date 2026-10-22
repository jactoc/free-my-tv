package io.freemytv;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Updates over the home network (update_url in config.properties): `./build.sh --publish` leaves FreeMyTV.apk and
 * version.json in a folder a web server on the network shares. The home screen looks there every few hours, downloads
 * a newer build (checked against version.json's SHA-256) and installs it when nobody is using it; Android asks to
 * confirm, and only accepts a build signed with the same key. Runs in the :home process (home screen, settings and
 * UpdateReceiver).
 */
final class Updates {
    private Updates() {}

    static final int IDLE = 0, CHECKING = 1, CURRENT = 2, READY = 3, INSTALLING = 4, CHECK_FAILED = 5, INSTALL_FAILED = 6;
    private static final long CHECK_EVERY_MS = 6 * 3600_000L, RETRY_MS = 30 * 60_000L;

    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile int state = IDLE;
    private static volatile long nextCheckAt, readyCode;
    private static volatile File ready;

    static boolean enabled() {
        return !Config.UPDATE_URL.trim().isEmpty();
    }

    static int state() {
        return state;
    }

    /** The downloaded build's version code (0: none). */
    static long readyCode() {
        return readyCode;
    }

    /** Looks for a newer build if it's time (every few hours; half an hour after a failure). */
    static void checkIfStale(Context c, Runnable onReady) {
        if (nextCheckAt != 0 && SystemClock.elapsedRealtime() < nextCheckAt) return;
        check(c, onReady);
    }

    /** Looks for a newer build now and downloads it; `onReady` runs on the main thread when one is ready. */
    static void check(Context c, Runnable onReady) {
        if (!enabled() || state == CHECKING || state == INSTALLING) return;
        final Context app = c.getApplicationContext();
        state = CHECKING;
        io.execute(() -> {
            int result = CHECK_FAILED;
            try {
                String base = Config.UPDATE_URL.trim();
                if (!base.endsWith("/")) base += "/";
                JSONObject v = new JSONObject(new String(get(base + "version.json"), StandardCharsets.UTF_8));
                long code = v.getLong("versionCode");
                if (code <= installedCode(app)) {
                    removeDownloads(app, null);
                    ready = null;
                    readyCode = 0;
                    result = CURRENT;
                } else {
                    String sha = v.getString("sha256").toLowerCase(Locale.ROOT);
                    File f = new File(app.getCacheDir(), "update-" + code + ".apk");
                    if (!f.exists() || !sha.equals(sha256(f))) {
                        download(base + v.optString("apk", "FreeMyTV.apk"), f);
                        if (!sha.equals(sha256(f))) {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                            throw new IOException("the download doesn't match version.json");
                        }
                    }
                    removeDownloads(app, f);
                    ready = f;
                    readyCode = code;
                    result = READY;
                    AppLog.w("update " + v.optString("versionName") + " downloaded");
                }
            } catch (IOException | JSONException | RuntimeException e) {
                AppLog.w("update check: " + e.getMessage());
            } finally {
                nextCheckAt = SystemClock.elapsedRealtime() + (result == CHECK_FAILED ? RETRY_MS : CHECK_EVERY_MS);
                File waiting = ready;      // a check that failed doesn't forget the build already downloaded
                state = result == CHECK_FAILED && waiting != null && waiting.exists() ? READY : result;
                if (result == READY && onReady != null) main.post(onReady);
            }
        });
    }

    /** Installs the downloaded build. Call it with the home screen or the settings in front: Android may ask to confirm. */
    static void install(Context c) {
        final File f = ready;
        if (state != READY || f == null || !f.exists()) return;
        final Context app = c.getApplicationContext();
        state = INSTALLING;
        io.execute(() -> {
            try {
                PackageInstaller installer = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());
                params.setSize(f.length());
                // Android 12+ skips the confirmation when this app installed the version it replaces.
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                int id = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(id)) {
                    try (InputStream in = new FileInputStream(f); OutputStream out = session.openWrite("base.apk", 0, f.length())) {
                        byte[] buf = new byte[64 * 1024];
                        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                        session.fsync(out);
                    }
                    started(app, true);
                    Intent status = new Intent(app, UpdateReceiver.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    session.commit(PendingIntent.getBroadcast(app, id, status, flags).getIntentSender());
                }
            } catch (IOException | RuntimeException e) {
                finished(PackageInstaller.STATUS_FAILURE, e.getMessage());
            }
        });
    }

    /** How the install ended (UpdateReceiver). On success Android restarts the app on the new version instead. */
    static void finished(int status, String message) {
        if (status == PackageInstaller.STATUS_SUCCESS) return;
        AppLog.w("update install: " + status + " " + message);
        started(null, false);
        // Cancelled on the confirmation: still there to install from the settings.
        state = status == PackageInstaller.STATUS_FAILURE_ABORTED ? READY : INSTALL_FAILED;
    }

    private static volatile File marker;

    /** Marks (or clears) an install this app started, for HomeReceiver once the new version is in. */
    private static void started(Context c, boolean on) {
        if (c != null) marker = new File(c.getFilesDir(), "update-started");
        File f = marker;
        if (f == null) return;
        try {
            if (on) new FileOutputStream(f).close();
            //noinspection ResultOfMethodCallIgnored
            else f.delete();
        } catch (IOException e) {
            AppLog.w("update marker: " + e.getMessage());
        }
    }

    /** True once, right after an update this app installed by itself: the TV then goes back to the home screen. */
    static boolean justInstalled(Context c) {
        File f = new File(c.getFilesDir(), "update-started");
        boolean recent = f.exists() && Math.abs(System.currentTimeMillis() - f.lastModified()) < 10 * 60_000L;
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        return recent;
    }

    private static long installedCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return Long.MAX_VALUE;
        }
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(10000);
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() / 100 != 2) throw new IOException(url + " returned HTTP " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if (out.size() > 64 * 1024) throw new IOException("version.json is too big");
                }
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File to) throws IOException {
        File part = new File(to.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(20000);
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() / 100 != 2) throw new IOException(url + " returned HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                long total = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if ((total += n) > 64L * 1024 * 1024) throw new IOException("the APK is too big");
                }
            }
            if (!part.renameTo(to)) throw new IOException("couldn't save the download");
        } finally {
            c.disconnect();
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
    }

    private static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Older downloads (and, once up to date, all of them): an APK is a few MB of the TV's small storage. */
    private static void removeDownloads(Context c, File keep) {
        File[] files = c.getCacheDir().listFiles((d, name) -> name.startsWith("update-"));
        for (int i = 0; files != null && i < files.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (!files[i].equals(keep)) files[i].delete();
        }
    }
}
