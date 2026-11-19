package id.bram.simpanmedia;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Menerima "Bagikan" dari IG, Threads, X, TikTok, WA, dll. Tanpa tampilan (transparan). */
@SuppressWarnings("deprecation")
public class ShareActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        String action = in.getAction();
        String type = in.getType();

        List<Uri> streams = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri s = (Uri) in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (s != null) streams.add(s);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) streams.addAll(list);
        }

        if (!streams.isEmpty() && type != null && !type.startsWith("text/")) {
            saveStreams(streams, type);
            return;
        }

        String text = in.getStringExtra(Intent.EXTRA_TEXT);
        String url = Platform.findUrl(text);
        if (url == null) {
            Toast.makeText(this, "Tidak ada link pada yang dibagikan", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        startDownload(this, url, this::finish);
    }

    /** Dipakai juga oleh MainActivity (kotak tempel link). */
    static void startDownload(Activity a, String url, Runnable after) {
        Platform p = Platform.of(url);
        Vault.unlock(a, p, (cookies, cancelled) -> {
            if (cancelled) {
                Toast.makeText(a, "Sidik jari dibatalkan — mengunduh tanpa login", Toast.LENGTH_SHORT).show();
            }
            DownloadService.start(a, url, cookies);
            Toast.makeText(a, "Mengunduh dari " + p.label + "…", Toast.LENGTH_SHORT).show();
            if (after != null) after.run();
        });
    }

    /** File dari chat WA / galeri lain → langsung simpan ke folder SimpanMedia. */
    private void saveStreams(List<Uri> streams, String type) {
        Toast.makeText(this, "Menyimpan " + streams.size() + " file…", Toast.LENGTH_SHORT).show();
        Handler h = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            int ok = 0;
            String stamp = Store.stamp();
            int n = 0;
            for (Uri u : streams) {
                n++;
                try {
                    String mime = getContentResolver().getType(u);
                    if (mime == null || mime.endsWith("*")) mime = type.endsWith("*") ? "image/jpeg" : type;
                    String name = displayName(u);
                    if (name == null || !name.contains(".")) {
                        name = "SM_wa_" + stamp + (streams.size() > 1 ? "_" + n : "") + "." + Store.extOf(mime, "jpg");
                    } else {
                        name = "SM_" + name;
                    }
                    try (InputStream in = getContentResolver().openInputStream(u)) {
                        if (in == null) continue;
                        Store.save(this, in, name, mime, null);
                        ok++;
                    }
                } catch (Exception ignored) {
                }
            }
            final int saved = ok;
            h.post(() -> {
                Toast.makeText(this, saved > 0 ? saved + " file tersimpan ke galeri (SimpanMedia)"
                        : "Gagal menyimpan file", Toast.LENGTH_LONG).show();
                finish();
            });
        }).start();
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        return null;
    }
}
