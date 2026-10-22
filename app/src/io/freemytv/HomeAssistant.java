package io.freemytv;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home Assistant for the Spaces tab: its areas (rooms), what can be switched in each (lights, light switches, covers,
 * scripts and scenes) and each room's temperature, humidity and open doors and windows. All of it comes from HA itself:
 * the rooms are HA's areas, so nothing about the house is written here. States are read while the tab shows
 * (`watch`), every few seconds; actions go out at once and show at once (the next reading confirms them).
 * `ha_url` and `ha_token` come from config.properties.
 */
final class HomeAssistant {
    interface Listener { void onHome(); }

    static final int LIGHT = 0, SWITCH = 1, COVER = 2, SCRIPT = 3, SCENE = 4;

    static final class Thing {
        String id, name;
        int kind;
        boolean on, available;
        int brightness = -1;        // 0-100 while on; -1: not dimmable
        boolean dimmable;
        int position = -1;          // a cover's 0 (closed) to 100 (open); -1: unknown
        String state = "";
        long holdUntil;             // an action of ours just went out: readings don't undo it before this
    }

    static final class Room {
        String id, name;
        final List<Thing> shortcuts = new ArrayList<>(), lights = new ArrayList<>(), covers = new ArrayList<>();
        final List<String> sensors = new ArrayList<>(), openings = new ArrayList<>();
        String temperature, humidity;
        final List<String> open = new ArrayList<>();    // the doors and windows open now, by name

        boolean empty() { return shortcuts.isEmpty() && lights.isEmpty() && covers.isEmpty(); }
    }

    private static HomeAssistant instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();
    private volatile List<Room> rooms = new ArrayList<>();
    private final Map<String, Thing> things = new HashMap<>();
    private volatile String error;
    private volatile boolean loaded, loading;
    private long structureAt;
    private boolean watching;
    private static final long STRUCTURE_FOR = 10 * 60_000L, POLL_MS = 3000;

    static boolean on() {
        return !Config.HA_URL.trim().isEmpty() && !Config.HA_TOKEN.trim().isEmpty();
    }

    static synchronized HomeAssistant get() {
        if (instance == null) instance = new HomeAssistant();
        return instance;
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** The rooms with something to switch, in HA's order (main thread). */
    List<Room> rooms() { return rooms; }
    boolean loaded() { return loaded; }
    String error() { return error; }

    /** Reading on (the tab shows) or off. */
    void watch(boolean on) {
        if (on == watching) return;
        watching = on;
        main.removeCallbacks(poll);
        if (on) poll.run();
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!watching) return;
            refresh();
            main.postDelayed(this, POLL_MS);
        }
    };

    private void refresh() {
        if (loading || !on()) return;
        loading = true;
        io.execute(() -> {
            try {
                long now = SystemClock.elapsedRealtime();
                if (rooms.isEmpty() || structureAt == 0 || now - structureAt > STRUCTURE_FOR) {
                    loadStructure();
                    structureAt = now;
                }
                readStates();
                error = null;
                loaded = true;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                AppLog.w("home assistant: " + error);
                structureAt = 0;
            } finally {
                loading = false;
                notifyListeners();
            }
        });
    }

    // MARK: - Actions

    /** OK on a thing: a light or switch toggles, a script or scene runs. A cover goes by `setPosition`. */
    void press(Thing t) {
        switch (t.kind) {
            case LIGHT:
            case SWITCH:
                hold(t);
                t.on = !t.on;
                if (t.dimmable) t.brightness = t.on ? Math.max(t.brightness, 1) : 0;
                call(t.kind == LIGHT ? "light" : "switch", t.on ? "turn_on" : "turn_off", t.id, null);
                break;
            case SCRIPT:
                call("script", "turn_on", t.id, null);
                break;
            case SCENE:
                call("scene", "turn_on", t.id, null);
                break;
            default:
                break;
        }
        notifyListeners();
    }

    /** A dimmable light's brightness, 0 (off) to 100. */
    void setBrightness(Thing t, int pct) {
        pct = Math.max(0, Math.min(100, pct));
        hold(t);
        t.brightness = pct;
        t.on = pct > 0;
        JSONObject data = new JSONObject();
        try {
            if (pct > 0) data.put("brightness_pct", pct);
        } catch (Exception ignored) {
        }
        call("light", pct > 0 ? "turn_on" : "turn_off", t.id, pct > 0 ? data : null);
        notifyListeners();
    }

    /** A cover's position, 0 (closed) to 100 (open). */
    void setPosition(Thing t, int pct) {
        pct = Math.max(0, Math.min(100, pct));
        hold(t);
        t.position = pct;
        JSONObject data = new JSONObject();
        try {
            data.put("position", pct);
        } catch (Exception ignored) {
        }
        call("cover", "set_cover_position", t.id, data);
        notifyListeners();
    }

    private static void hold(Thing t) {
        t.holdUntil = SystemClock.elapsedRealtime() + 5000;     // a Shelly cover takes a few seconds to report
    }

    private void call(String domain, String service, String entity, JSONObject data) {
        io.execute(() -> {
            try {
                JSONObject body = data == null ? new JSONObject() : data;
                body.put("entity_id", entity);
                post("/api/services/" + domain + "/" + service, body.toString());
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                AppLog.w("home assistant " + domain + "." + service + ": " + error);
                notifyListeners();
            }
        });
    }

    // MARK: - Reading HA

    /** HA's areas and what's in them, without its settings switches (Sonos's crossfade, "auto update"...). */
    private void loadStructure() throws Exception {
        Set<String> skip = settingsEntities();
        String template = "[{% for a in areas() %}{\"a\":{{ a|tojson }},\"n\":{{ area_name(a)|tojson }},\"e\":"
                + "{{ area_entities(a)|select('match','(light|switch|cover|script|scene|sensor|binary_sensor)\\\\.')|list|tojson }}}"
                + "{{ ',' if not loop.last }}{% endfor %}]";
        JSONArray areas = new JSONArray(template(template));
        List<Room> out = new ArrayList<>();
        Set<String> wanted = new HashSet<>();
        for (int i = 0; i < areas.length(); i++) {
            JSONObject a = areas.getJSONObject(i);
            Room r = new Room();
            r.id = a.getString("a");
            r.name = a.optString("n", r.id);
            JSONArray ids = a.optJSONArray("e");
            for (int j = 0; ids != null && j < ids.length(); j++) {
                String id = ids.getString(j);
                if (skip.contains(id)) continue;
                String domain = id.substring(0, id.indexOf('.'));
                if (domain.equals("sensor")) r.sensors.add(id);
                else if (domain.equals("binary_sensor")) r.openings.add(id);
                else {
                    Thing t;
                    synchronized (things) {
                        t = things.get(id);
                        if (t == null) {
                            t = new Thing();
                            t.id = id;
                            things.put(id, t);
                        }
                    }
                    t.kind = domain.equals("light") ? LIGHT : domain.equals("switch") ? SWITCH : domain.equals("cover") ? COVER
                            : domain.equals("script") ? SCRIPT : SCENE;
                    (t.kind == COVER ? r.covers : t.kind == SCRIPT || t.kind == SCENE ? r.shortcuts : r.lights).add(t);
                }
                wanted.add(id);
            }
            if (!r.empty()) out.add(r);
        }
        pending = out;
        pendingIds = wanted;
    }

    private volatile List<Room> pending;
    private volatile Set<String> pendingIds;
    private Set<String> allIds = new HashSet<>();

    private void readStates() throws Exception {
        if (pending != null) {
            allIds = pendingIds;
        }
        if (allIds.isEmpty()) {
            if (pending != null) publish(pending);
            return;
        }
        StringBuilder ids = new StringBuilder("[");
        for (String id : allIds) ids.append(ids.length() > 1 ? "," : "").append('"').append(id).append('"');
        ids.append(']');
        String template = "{% set ids = " + ids + " %}[{% for e in ids %}{\"i\":{{ e|tojson }},\"s\":{{ states(e)|tojson }},"
                + "\"n\":{{ state_attr(e,'friendly_name')|tojson }},\"b\":{{ state_attr(e,'brightness')|tojson }},"
                + "\"p\":{{ state_attr(e,'current_position')|tojson }},\"c\":{{ state_attr(e,'device_class')|tojson }},"
                + "\"m\":{{ (state_attr(e,'supported_color_modes') or [])|list|tojson }},"
                + "\"u\":{{ state_attr(e,'unit_of_measurement')|tojson }}}{{ ',' if not loop.last }}{% endfor %}]";
        JSONArray states = new JSONArray(template(template));
        Map<String, JSONObject> byId = new HashMap<>();
        for (int i = 0; i < states.length(); i++) byId.put(states.getJSONObject(i).getString("i"), states.getJSONObject(i));
        List<Room> list = pending != null ? pending : rooms;
        long now = SystemClock.elapsedRealtime();
        for (Room r : list) {
            for (List<Thing> group : java.util.Arrays.asList(r.shortcuts, r.lights, r.covers)) {
                for (Thing t : group) {
                    JSONObject o = byId.get(t.id);
                    if (o == null) continue;
                    t.name = clean(o.isNull("n") ? t.id : o.optString("n"), r.name, t.kind);
                    String s = o.optString("s");
                    t.available = !s.equals("unavailable") && !s.equals("unknown");
                    if (now < t.holdUntil) continue;    // ours just changed it: HA may not have caught up
                    t.state = s;
                    t.on = s.equals("on");
                    if (t.kind == LIGHT) {
                        JSONArray modes = o.optJSONArray("m");
                        t.dimmable = false;
                        for (int m = 0; modes != null && m < modes.length(); m++) t.dimmable |= !"onoff".equals(modes.optString(m));
                        t.brightness = !t.dimmable ? -1 : t.on && !o.isNull("b") ? Math.max(1, Math.round(o.optInt("b") * 100 / 255f)) : 0;
                    }
                    if (t.kind == COVER) t.position = o.isNull("p") ? (s.equals("closed") ? 0 : s.equals("open") ? 100 : -1) : o.optInt("p");
                }
            }
            r.temperature = r.humidity = null;
            r.open.clear();
            for (String id : r.sensors) {
                JSONObject o = byId.get(id);
                if (o == null) continue;
                String c = o.optString("c"), s = o.optString("s");
                if (s.isEmpty() || s.equals("unavailable") || s.equals("unknown")) continue;
                if (c.equals("temperature") && r.temperature == null) r.temperature = number(s) + "°";
                if (c.equals("humidity") && r.humidity == null) r.humidity = whole(s) + "%";
            }
            for (String id : r.openings) {
                JSONObject o = byId.get(id);
                if (o == null) continue;
                String c = o.optString("c");
                if (o.optString("s").equals("on") && (c.equals("door") || c.equals("window") || c.equals("garage_door") || c.equals("opening"))) {
                    r.open.add(clean(o.optString("n"), r.name, -1));
                }
            }
        }
        if (pending != null) {
            publish(pending);
            pending = null;
        }
    }

    private void publish(List<Room> list) {
        main.post(() -> rooms = list);
    }

    /** "55.7" → "56" (humidity reads as a whole number). */
    private static String whole(String s) {
        try {
            return String.valueOf(Math.round(Float.parseFloat(s)));
        } catch (NumberFormatException e) {
            return s;
        }
    }

    /** "21.62" → "21.6", "54" → "54". */
    private static String number(String s) {
        try {
            float f = Float.parseFloat(s);
            return f == Math.round(f) ? String.valueOf(Math.round(f)) : String.format(Locale.US, "%.1f", f);
        } catch (NumberFormatException e) {
            return s;
        }
    }

    /** HA's name without the room's ("Central Light Living Room" in Living Room → "Central Light"). */
    static String clean(String name, String room, int kind) {
        String out = name;
        if (room != null && !room.isEmpty()) {
            int at = out.toLowerCase(Locale.ROOT).indexOf(room.toLowerCase(Locale.ROOT));
            if (at >= 0) out = out.substring(0, at) + out.substring(at + room.length());
        }
        out = out.replaceAll("\\s+", " ").trim();
        if (out.startsWith("- ") || out.startsWith(": ")) out = out.substring(2);
        if (!out.isEmpty()) return Character.toUpperCase(out.charAt(0)) + out.substring(1);
        return kind == COVER ? Ui.tr("Persiana", "Shutter", "Persiana") : kind == SCRIPT || kind == SCENE ? "Scene"
                : Ui.tr("Luz", "Light", "Luz");
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onHome(); });
    }

    // MARK: - HTTP

    private static String base() {
        String u = Config.HA_URL.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private static String template(String template) throws Exception {
        return post("/api/template", new JSONObject().put("template", template).toString());
    }

    private static String post(String path, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base() + path).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(4000);
        c.setReadTimeout(8000);
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + Config.HA_TOKEN.trim());
        c.setRequestProperty("Content-Type", "application/json");
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("Home Assistant rejected the token");
            if (code / 100 != 2) throw new IOException("Home Assistant returned HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    // MARK: - WebSocket: one request, the entity registry (only it says which entities are settings)

    /** Entities HA files under settings or diagnostics, or hides: never on the TV. Empty if the registry can't be read. */
    private static Set<String> settingsEntities() {
        Set<String> out = new HashSet<>();
        try {
            JSONObject result = websocket(new JSONObject().put("id", 1).put("type", "config/entity_registry/list_for_display"));
            JSONArray list = result.optJSONArray("entities");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject e = list.getJSONObject(i);
                if (e.has("ec") || e.optBoolean("hb")) out.add(e.optString("ei"));
            }
        } catch (Exception e) {
            AppLog.w("home assistant registry: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        return out;
    }

    /** Opens HA's WebSocket, logs in, sends `request` and returns its result. Plain ws:// or wss://. */
    private static JSONObject websocket(JSONObject request) throws Exception {
        URL u = new URL(base());
        boolean tls = u.getProtocol().equals("https");
        int port = u.getPort() > 0 ? u.getPort() : tls ? 443 : 80;
        Socket s = tls ? javax.net.ssl.SSLSocketFactory.getDefault().createSocket() : new Socket();
        try {
            s.connect(new InetSocketAddress(u.getHost(), port), 4000);
            s.setSoTimeout(8000);
            OutputStream out = s.getOutputStream();
            InputStream in = new java.io.BufferedInputStream(s.getInputStream());
            byte[] nonce = new byte[16];
            new SecureRandom().nextBytes(nonce);
            String handshake = "GET /api/websocket HTTP/1.1\r\nHost: " + u.getHost() + ":" + port + "\r\nUpgrade: websocket\r\n"
                    + "Connection: Upgrade\r\nSec-WebSocket-Key: " + Base64.encodeToString(nonce, Base64.NO_WRAP)
                    + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
            out.write(handshake.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String status = line(in);
            if (!status.contains(" 101")) throw new IOException("Home Assistant WebSocket: " + status);
            while (!line(in).isEmpty()) { /* the rest of the answer's headers */ }
            receive(in);    // auth_required
            send(out, new JSONObject().put("type", "auth").put("access_token", Config.HA_TOKEN.trim()).toString());
            JSONObject auth = new JSONObject(receive(in));
            if (!"auth_ok".equals(auth.optString("type"))) throw new IOException("Home Assistant rejected the token");
            send(out, request.toString());
            for (int i = 0; i < 20; i++) {
                JSONObject m = new JSONObject(receive(in));
                if (m.optInt("id") == request.optInt("id") && "result".equals(m.optString("type"))) {
                    if (!m.optBoolean("success")) throw new IOException("Home Assistant: " + m.optJSONObject("error"));
                    return m.optJSONObject("result") == null ? new JSONObject() : m.getJSONObject("result");
                }
            }
            throw new IOException("Home Assistant: no answer");
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int c; (c = in.read()) != -1; ) {
            if (c == '\n') break;
            if (c != '\r') b.append((char) c);
        }
        return b.toString();
    }

    /** A text frame from the client: always masked (RFC 6455). */
    private static void send(OutputStream out, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(0x81);
        if (data.length < 126) {
            f.write(0x80 | data.length);
        } else if (data.length < 65536) {
            f.write(0x80 | 126);
            f.write(data.length >> 8);
            f.write(data.length & 255);
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) f.write((int) ((long) data.length >> (8 * i)) & 255);
        }
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        f.write(mask);
        for (int i = 0; i < data.length; i++) f.write(data[i] ^ mask[i & 3]);
        out.write(f.toByteArray());
        out.flush();
    }

    /** The next text message (its frames put together); pings are answered by nothing: the socket lives seconds. */
    private static String receive(InputStream in) throws IOException {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        while (true) {
            int b0 = in.read(), b1 = in.read();
            if (b0 < 0 || b1 < 0) throw new IOException("Home Assistant closed the WebSocket");
            long len = b1 & 127;
            if (len == 126) len = (in.read() << 8) | in.read();
            else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
            }
            if (len > 16_000_000) throw new IOException("Home Assistant: message too big");
            byte[] mask = null;
            if ((b1 & 0x80) != 0) {
                mask = new byte[4];
                readFully(in, mask);
            }
            byte[] payload = new byte[(int) len];
            readFully(in, payload);
            if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            int op = b0 & 15;
            if (op == 8) throw new IOException("Home Assistant closed the WebSocket");
            if (op == 1 || op == 0) msg.write(payload);
            if ((b0 & 0x80) != 0 && (op == 1 || op == 0)) return msg.toString("UTF-8");
        }
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        for (int off = 0; off < b.length; ) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new IOException("Home Assistant closed the WebSocket");
            off += n;
        }
    }
}
