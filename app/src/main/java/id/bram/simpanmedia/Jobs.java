package id.bram.simpanmedia;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Daftar unduhan (berjalan + riwayat), disimpan di SharedPreferences. */
final class Jobs {
    static final int RUN = 0, OK = 1, ERR = 2;

    static final class Job {
        String id;
        String url;
        Platform platform;
        int status = RUN;
        String msg = "Menunggu…";
        int progress = -1;
        long time;
        final List<String> uris = new ArrayList<>();
        final List<String> mimes = new ArrayList<>();
    }

    interface Listener {
        void changed();
    }

    private static final List<Job> list = new ArrayList<>();
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean loaded;
    private static Context app;

    private Jobs() {}

    static synchronized void load(Context c) {
        app = c.getApplicationContext();
        if (loaded) return;
        loaded = true;
        try {
            JSONArray a = new JSONArray(prefs().getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Job j = new Job();
                j.id = o.getString("id");
                j.url = o.optString("url");
                j.platform = Platform.valueOf(o.optString("p", "WEB"));
                j.status = o.optInt("s", ERR);
                j.msg = o.optString("m");
                j.time = o.optLong("t");
                JSONArray u = o.optJSONArray("u");
                JSONArray m = o.optJSONArray("mi");
                if (u != null) for (int k = 0; k < u.length(); k++) {
                    j.uris.add(u.getString(k));
                    j.mimes.add(m != null && k < m.length() ? m.getString(k) : "");
                }
                if (j.status == RUN) {
                    j.status = ERR;
                    j.msg = "Terhenti (aplikasi ditutup)";
                }
                list.add(j);
            }
        } catch (Exception ignored) {
        }
    }

    private static SharedPreferences prefs() {
        return app.getSharedPreferences("jobs", Context.MODE_PRIVATE);
    }

    static synchronized List<Job> all() {
        return new ArrayList<>(list);
    }

    static synchronized Job add(Context c, String url, Platform p) {
        load(c);
        Job j = new Job();
        j.id = Long.toString(System.currentTimeMillis(), 36) + (int) (Math.random() * 1000);
        j.url = url;
        j.platform = p;
        j.time = System.currentTimeMillis();
        list.add(0, j);
        while (list.size() > 150) list.remove(list.size() - 1);
        save();
        notifyChanged();
        return j;
    }

    static synchronized Job get(String id) {
        for (Job j : list) if (j.id.equals(id)) return j;
        return null;
    }

    static synchronized void remove(Job j) {
        list.remove(j);
        save();
        notifyChanged();
    }

    static synchronized void clearFinished() {
        list.removeIf(j -> j.status != RUN);
        save();
        notifyChanged();
    }

    /** Update ringan (progress) — tidak disimpan ke disk. */
    static void progress(Job j, int pct, String msg) {
        j.progress = pct;
        if (msg != null) j.msg = msg;
        notifyChanged();
    }

    static synchronized void finish(Job j, int status, String msg) {
        j.status = status;
        j.msg = msg;
        j.progress = -1;
        save();
        notifyChanged();
    }

    static synchronized void addFile(Job j, String uri, String mime) {
        j.uris.add(uri);
        j.mimes.add(mime);
    }

    private static void save() {
        try {
            JSONArray a = new JSONArray();
            for (Job j : list) {
                JSONObject o = new JSONObject();
                o.put("id", j.id);
                o.put("url", j.url);
                o.put("p", j.platform.name());
                o.put("s", j.status);
                o.put("m", j.msg);
                o.put("t", j.time);
                o.put("u", new JSONArray(j.uris));
                o.put("mi", new JSONArray(j.mimes));
                a.put(o);
            }
            prefs().edit().putString("list", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static void listen(Listener l) {
        listeners.add(l);
    }

    static void unlisten(Listener l) {
        listeners.remove(l);
    }

    private static void notifyChanged() {
        main.post(() -> {
            for (Listener l : listeners) l.changed();
        });
    }
}
