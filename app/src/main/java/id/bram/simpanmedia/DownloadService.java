package id.bram.simpanmedia;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.IBinder;
import android.os.PowerManager;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import kotlin.Unit;

public class DownloadService extends Service {
    static final String CH_RUN = "unduh", CH_DONE = "selesai";
    private static final int FG_ID = 1;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final AtomicInteger active = new AtomicInteger();
    private PowerManager.WakeLock wake;
    private static volatile boolean ydlReady;
    private static final Object ydlLock = new Object();

    /** Mulai unduh. cookies boleh null (tanpa login). */
    static void start(Context c, String url, String cookies) {
        Platform p = Platform.of(url);
        Jobs.Job j = Jobs.add(c, url, p);
        Intent i = new Intent(c, DownloadService.class)
                .putExtra("job", j.id)
                .putExtra("cookies", cookies);
        c.startForegroundService(i);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Jobs.load(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_RUN, "Proses unduh", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_DONE, "Unduhan selesai", NotificationManager.IMPORTANCE_DEFAULT));
        PowerManager pm = getSystemService(PowerManager.class);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SimpanMedia:unduh");
        wake.setReferenceCounted(false);
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification runningNotif(String text, int pct) {
        Notification.Builder b = new Notification.Builder(this, CH_RUN)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Simpan Media – mengunduh")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp());
        if (pct >= 0) b.setProgress(100, pct, false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = runningNotif("Menyiapkan…", -1);
        startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (intent == null) {
            maybeStop();
            return START_NOT_STICKY;
        }
        Jobs.Job j = Jobs.get(intent.getStringExtra("job"));
        String cookies = intent.getStringExtra("cookies");
        if (j == null) {
            maybeStop();
            return START_NOT_STICKY;
        }
        active.incrementAndGet();
        wake.acquire(30 * 60 * 1000L);
        pool.execute(() -> {
            try {
                run(j, cookies);
            } catch (Throwable t) {
                Jobs.finish(j, Jobs.ERR, "Gagal: " + shortErr(t));
            } finally {
                notifyDone(j);
                if (active.decrementAndGet() == 0) maybeStop();
            }
        });
        return START_NOT_STICKY;
    }

    private void maybeStop() {
        if (active.get() > 0) return;
        if (wake != null && wake.isHeld()) wake.release();
        Cookies.wipeTemp(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void updateFg(Jobs.Job j, int pct, String msg) {
        Jobs.progress(j, pct, msg);
        getSystemService(NotificationManager.class).notify(FG_ID,
                runningNotif(j.platform.label + " · " + msg, pct));
    }

    private void notifyDone(Jobs.Job j) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        Notification.Builder b = new Notification.Builder(this, CH_DONE)
                .setSmallIcon(R.drawable.ic_stat)
                .setAutoCancel(true);
        if (j.status == Jobs.OK) {
            b.setContentTitle("Tersimpan ke galeri").setContentText(j.platform.label + " · " + j.msg);
            if (!j.uris.isEmpty()) {
                Intent view = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.parse(j.uris.get(0)), j.mimes.get(0))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                b.setContentIntent(PendingIntent.getActivity(this, j.id.hashCode(), view,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            }
        } else {
            b.setContentTitle("Gagal mengunduh").setContentText(j.msg).setContentIntent(openApp())
                    .setStyle(new Notification.BigTextStyle().bigText(j.msg));
        }
        nm.notify(j.id.hashCode(), b.build());
    }

    // ------------------------------------------------------------------

    private void run(Jobs.Job j, String cookies) throws Exception {
        String url = j.url;
        Platform p = j.platform;
        updateFg(j, -1, "Membaca link…");

        // Link pendek/share → link asli (supaya kode postingan terbaca)
        if (url.contains("/share/") || p == Platform.WEB || url.contains("vt.tiktok") || url.contains("t.co/")) {
            String r = Http.resolve(url);
            if (r != null && !r.equals(url)) {
                url = r;
                Platform p2 = Platform.of(url);
                if (p2 != Platform.WEB) p = p2;
            }
        }

        Exception last = null;
        List<Extract.Item> items = new ArrayList<>();
        try {
            if (p == Platform.INSTAGRAM && cookies != null) items = Extract.instagram(url, cookies);
            else if (p == Platform.THREADS) items = Extract.threads(url, cookies);
            else if (p == Platform.X) items = Extract.twitter(url);
        } catch (Exception e) {
            last = e;
        }
        if (items.isEmpty() && p == Platform.X) {
            try {
                items = Extract.twitterVx(url);
            } catch (Exception e) {
                if (last == null) last = e;
            }
        }
        int saved = 0;
        if (!items.isEmpty()) saved = downloadItems(j, items, p);

        // Threads: yt-dlp tidak mendukung → langsung pakai browser tersembunyi.
        if (saved == 0 && p == Platform.THREADS) {
            saved = webGrab(j, url, p, cookies, "www.threads.com");
            if (saved == 0) last = new Exception("Threads: postingan tidak terbaca (" + lastNote + ")");
        }
        if (saved == 0 && p != Platform.THREADS) {
            try {
                saved = ytdlp(j, url, p, cookies);
            } catch (Exception e) {
                last = e;
            }
        }
        if (saved == 0 && p == Platform.INSTAGRAM) {
            saved = webGrab(j, url, p, cookies, "www.instagram.com");
        }
        if (saved == 0) {
            try {
                List<Extract.Item> og = Extract.og(url, cookies);
                if (!og.isEmpty()) saved = downloadItems(j, og, p);
            } catch (Exception e) {
                if (last == null) last = e;
            }
        }
        if (saved == 0) {
            String why = last != null ? shortErr(last) : "Media tidak ditemukan";
            if (cookies == null && p.canLogin() && (why.contains("login") || why.contains("HTTP 4")
                    || why.contains("private") || p == Platform.THREADS || why.contains("tidak ditemukan") || why.contains("not")))
                why += "\nCoba login " + p.label + " di tab Akun.";
            Jobs.finish(j, Jobs.ERR, why);
            return;
        }
        Jobs.finish(j, Jobs.OK, saved + " file tersimpan");
    }

    private volatile String lastNote = "";

    private int webGrab(Jobs.Job j, String url, Platform p, String cookies, String host) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("/(?:post|p|reel|reels|tv)/([A-Za-z0-9_-]+)").matcher(url);
        // Link share (tanpa kode) tetap dibuka: browser akan diarahkan ke postingan aslinya.
        String code = m.find() ? m.group(1) : "";
        updateFg(j, -1, "Membuka halaman " + p.label + "…");
        try {
            String page = url;
            if (p == Platform.THREADS) page = url.replace("threads.net", "threads.com");
            WebGrab.Result r = WebGrab.run(this, page, code, cookies, host, 25000);
            lastNote = r.note;
            if (r.items.isEmpty()) return 0;
            return downloadItems(j, r.items, p);
        } catch (Exception e) {
            lastNote = String.valueOf(e.getMessage());
            return 0;
        }
    }

    private int downloadItems(Jobs.Job j, List<Extract.Item> items, Platform p) {
        int saved = 0;
        String stamp = Store.stamp();
        for (int i = 0; i < items.size(); i++) {
            Extract.Item it = items.get(i);
            String label = items.size() > 1 ? "File " + (i + 1) + "/" + items.size() : "Mengunduh";
            updateFg(j, items.size() > 1 ? i * 100 / items.size() : -1, label + "…");
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", Http.UA_DESKTOP);
            HttpURLConnection c = null;
            try {
                c = Http.open(it.url, h);
                String mime = c.getContentType();
                if (mime == null || mime.startsWith("application/octet") || mime.startsWith("binary"))
                    mime = it.video ? "video/mp4" : "image/jpeg";
                mime = mime.split(";")[0].trim();
                String ext = Store.extOf(mime, it.video ? "mp4" : "jpg");
                String name = "SM_" + p.name().toLowerCase() + "_" + stamp
                        + (items.size() > 1 ? "_" + (i + 1) : "") + "." + ext;
                final long len = c.getContentLengthLong();
                final String lbl = label;
                try (InputStream in = c.getInputStream()) {
                    Uri u = Store.save(this, in, name, mime, done -> {
                        if (len > 0) updateFg(j, (int) (done * 100 / len), lbl + " " + (done * 100 / len) + "%");
                    });
                    Jobs.addFile(j, u.toString(), mime);
                    saved++;
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return saved;
    }

    static void ensureYdl(Context c) throws Exception {
        if (ydlReady) return;
        synchronized (ydlLock) {
            if (ydlReady) return;
            YoutubeDL.INSTANCE.init(c.getApplicationContext());
            FFmpeg.INSTANCE.init(c.getApplicationContext());
            ydlReady = true;
        }
    }

    private int ytdlp(Jobs.Job j, String url, Platform p, String cookies) throws Exception {
        updateFg(j, -1, "Menyiapkan mesin unduh…");
        ensureYdl(this);
        File dir = new File(getCacheDir(), "dl/" + j.id);
        deleteTree(dir);
        dir.mkdirs();
        File ck = null;
        try {
            YoutubeDLRequest r = new YoutubeDLRequest(url);
            r.addOption("-o", dir.getAbsolutePath() + "/%(id).60B_%(autonumber)s.%(ext)s");
            r.addOption("-f", "bv*[vcodec^=avc]+ba[acodec^=mp4a]/bv*+ba/b");
            r.addOption("--merge-output-format", "mp4");
            r.addOption("--no-mtime");
            r.addOption("--no-part");
            r.addOption("-N", 4);
            r.addOption("--retries", 3);
            if (p == Platform.YOUTUBE) r.addOption("--no-playlist");
            if (cookies != null) {
                ck = Cookies.netscapeFile(this, cookies);
                r.addOption("--cookies", ck.getAbsolutePath());
            }
            updateFg(j, -1, "Mengunduh…");
            YoutubeDL.INSTANCE.execute(r, j.id, (prog, eta, line) -> {
                int pct = Math.max(0, Math.min(100, Math.round(prog)));
                if (prog > 0) updateFg(j, pct, "Mengunduh " + pct + "%");
                return Unit.INSTANCE;
            });
        } finally {
            if (ck != null) ck.delete();
        }
        File[] files = dir.listFiles();
        int saved = 0;
        if (files != null) {
            Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            String stamp = Store.stamp();
            int n = 0;
            for (File f : files) {
                String nm = f.getName();
                if (nm.endsWith(".part") || nm.endsWith(".ytdl") || nm.endsWith(".temp") || f.length() == 0) continue;
                n++;
                String mime = Store.mimeOf(nm, "application/octet-stream");
                String ext = nm.contains(".") ? nm.substring(nm.lastIndexOf('.') + 1) : "bin";
                String name = "SM_" + p.name().toLowerCase() + "_" + stamp + (files.length > 1 ? "_" + n : "") + "." + ext;
                updateFg(j, -1, "Menyimpan ke galeri…");
                try (InputStream in = new FileInputStream(f)) {
                    Uri u = Store.save(this, in, name, mime, null);
                    Jobs.addFile(j, u.toString(), mime);
                    saved++;
                }
            }
        }
        deleteTree(dir);
        return saved;
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    static String shortErr(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.trim().isEmpty()) m = t.getClass().getSimpleName();
        // yt-dlp: ambil baris ERROR terakhir saja
        String best = null;
        for (String line : m.split("\n")) {
            if (line.contains("ERROR")) best = line;
        }
        if (best != null) m = best;
        m = m.replace("ERROR: ", "").trim();
        if (m.length() > 220) m = m.substring(0, 220) + "…";
        String low = m.toLowerCase();
        if (low.contains("login") || low.contains("log in") || low.contains("cookies"))
            m = "Postingan ini butuh login. " + m;
        else if (low.contains("unable to resolve host") || low.contains("unknownhost"))
            m = "Tidak ada koneksi internet.";
        return m;
    }

    @Override
    public void onDestroy() {
        pool.shutdownNow();
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }
}
