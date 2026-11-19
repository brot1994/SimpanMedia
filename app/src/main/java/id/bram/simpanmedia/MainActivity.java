package id.bram.simpanmedia;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.yausername.youtubedl_android.YoutubeDL;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements Jobs.Listener {
    private static final int REQ_LOGIN = 81;

    private FrameLayout content;
    private final TextView[] tabs = new TextView[3];
    private int tab = 0;

    private View unduhView;
    private LinearLayout jobList;
    private EditText input;
    private StatusTab statusTab;
    private ScrollView akunView;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Jobs.load(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);

        TextView title = Ui.text(this, "Simpan Media", 22, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 4));
        root.addView(title);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout nav = Ui.row(this);
        nav.setBackgroundColor(Ui.CARD);
        String[] names = {"⬇  Unduh", "◎  Status WA", "🔒  Akun"};
        for (int i = 0; i < 3; i++) {
            TextView t = Ui.text(this, names[i], 14.5f, Ui.MUTED);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 15), 0, Ui.dp(this, 15));
            final int idx = i;
            t.setOnClickListener(v -> show(idx));
            nav.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            tabs[i] = t;
        }
        root.addView(nav);
        setContentView(root);

        buildUnduh();
        statusTab = new StatusTab(this);
        akunView = new ScrollView(this);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        show(b != null ? b.getInt("tab", 0) : 0);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
    }

    private void show(int i) {
        tab = i;
        for (int k = 0; k < 3; k++) {
            tabs[k].setTextColor(k == i ? Color.WHITE : Ui.MUTED);
            tabs[k].setTypeface(k == i ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        content.removeAllViews();
        if (i == 0) {
            content.addView(unduhView);
            renderJobs();
        } else if (i == 1) {
            content.addView(statusTab);
            statusTab.reload();
        } else {
            buildAkun();
            content.addView(akunView);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Jobs.listen(this);
        if (tab == 0) renderJobs();
        else if (tab == 1) statusTab.reload();
        else if (tab == 2) buildAkun();
    }

    @Override
    protected void onPause() {
        Jobs.unlisten(this);
        super.onPause();
    }

    @Override
    public void changed() {
        if (tab == 0) renderJobs();
    }

    // ============================== Tab Unduh ==============================

    private void buildUnduh() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 12);
        col.setPadding(p, p, p, p);
        sv.addView(col);

        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "Tempel link postingan", 15, Color.WHITE));
        input = new EditText(this);
        input.setHint("https://www.instagram.com/p/…");
        input.setHintTextColor(0xFF6B7380);
        input.setTextColor(Color.WHITE);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setBackground(Ui.round(this, Ui.CARD2, 10));
        input.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        input.setOnEditorActionListener((v, a, e) -> {
            go();
            return true;
        });
        card.addView(input, Ui.mlp(this, 10));
        LinearLayout row = Ui.row(this);
        Button paste = Ui.button(this, "Tempel", false);
        Button dl = Ui.button(this, "Unduh", true);
        row.addView(paste, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.leftMargin = Ui.dp(this, 8);
        row.addView(dl, lp);
        card.addView(row, Ui.mlp(this, 10));
        paste.setOnClickListener(v -> {
            String c = clip();
            if (c == null) Toast.makeText(this, "Papan klip kosong", Toast.LENGTH_SHORT).show();
            else input.setText(c);
        });
        dl.setOnClickListener(v -> go());
        TextView tip = Ui.text(this, "Lebih cepat: di IG / Threads / X / TikTok / FB / YouTube tekan Bagikan → "
                + "pilih Simpan Media. Foto & video dari chat WA juga bisa dibagikan ke sini.", 12.5f, Ui.MUTED);
        card.addView(tip, Ui.mlp(this, 10));
        col.addView(card);

        LinearLayout head = Ui.row(this);
        TextView h = Ui.text(this, "Riwayat", 16, Color.WHITE);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(h, new LinearLayout.LayoutParams(0, -2, 1));
        TextView clear = Ui.text(this, "Bersihkan", 13.5f, Ui.ACCENT);
        clear.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), 0, Ui.dp(this, 6));
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Hapus riwayat yang sudah selesai? (File di galeri tetap ada.)")
                .setPositiveButton("Hapus", (d, w) -> Jobs.clearFinished())
                .setNegativeButton("Batal", null).show());
        head.addView(clear);
        col.addView(head, Ui.mlp(this, 18));

        jobList = new LinearLayout(this);
        jobList.setOrientation(LinearLayout.VERTICAL);
        col.addView(jobList, Ui.mlp(this, 6));
        unduhView = sv;
    }

    private String clip() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm == null || !cm.hasPrimaryClip()) return null;
        ClipData cd = cm.getPrimaryClip();
        if (cd == null || cd.getItemCount() == 0) return null;
        CharSequence t = cd.getItemAt(0).coerceToText(this);
        return t == null ? null : t.toString();
    }

    private void go() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) text = clip();
        String url = Platform.findUrl(text);
        if (url == null) {
            Toast.makeText(this, "Tidak ada link yang valid", Toast.LENGTH_SHORT).show();
            return;
        }
        input.setText("");
        ShareActivity.startDownload(this, url, null);
    }

    private void renderJobs() {
        if (jobList == null) return;
        jobList.removeAllViews();
        java.util.List<Jobs.Job> all = Jobs.all();
        if (all.isEmpty()) {
            TextView t = Ui.text(this, "Belum ada unduhan.", 14, Ui.MUTED);
            t.setPadding(0, Ui.dp(this, 10), 0, 0);
            jobList.addView(t);
            return;
        }
        for (Jobs.Job j : all) jobList.addView(jobRow(j), Ui.mlp(this, 8));
    }

    private View jobRow(Jobs.Job j) {
        LinearLayout c = Ui.card(this);
        c.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        LinearLayout top = Ui.row(this);
        TextView plat = Ui.text(this, j.platform.label, 14, Color.WHITE);
        plat.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(plat, new LinearLayout.LayoutParams(0, -2, 1));
        String when = DateUtils.isToday(j.time)
                ? new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(j.time))
                : new SimpleDateFormat("d MMM HH:mm", new Locale("id")).format(new Date(j.time));
        top.addView(Ui.text(this, when, 12, Ui.MUTED));
        c.addView(top);

        TextView url = Ui.text(this, j.url, 12, Ui.MUTED);
        url.setSingleLine(true);
        url.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        c.addView(url, Ui.mlp(this, 2));

        int color = j.status == Jobs.OK ? Ui.OK : j.status == Jobs.ERR ? Ui.ERR : Ui.ACCENT;
        String prefix = j.status == Jobs.OK ? "✓ " : j.status == Jobs.ERR ? "✕ " : "";
        TextView st = Ui.text(this, prefix + j.msg, 13, color);
        c.addView(st, Ui.mlp(this, 4));
        if (j.status == Jobs.RUN) {
            ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            pb.setIndeterminate(j.progress < 0);
            pb.setMax(100);
            if (j.progress >= 0) pb.setProgress(j.progress);
            c.addView(pb, Ui.mlp(this, 4));
        }
        c.setOnClickListener(v -> {
            if (j.status == Jobs.OK && !j.uris.isEmpty()) open(j, 0);
            else if (j.status == Jobs.ERR) jobMenu(j);
        });
        c.setOnLongClickListener(v -> {
            jobMenu(j);
            return true;
        });
        return c;
    }

    private void open(Jobs.Job j, int i) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(j.uris.get(i)), j.mimes.get(i))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception e) {
            Toast.makeText(this, "File tidak bisa dibuka (mungkin sudah dihapus)", Toast.LENGTH_SHORT).show();
        }
    }

    private void jobMenu(Jobs.Job j) {
        java.util.List<String> opts = new java.util.ArrayList<>();
        java.util.List<Runnable> acts = new java.util.ArrayList<>();
        for (int i = 0; i < j.uris.size(); i++) {
            final int k = i;
            opts.add(j.uris.size() > 1 ? "Buka file " + (i + 1) : "Buka");
            acts.add(() -> open(j, k));
        }
        if (!j.uris.isEmpty()) {
            opts.add("Bagikan");
            acts.add(() -> {
                java.util.ArrayList<Uri> us = new java.util.ArrayList<>();
                for (String s : j.uris) us.add(Uri.parse(s));
                Intent send = new Intent(us.size() > 1 ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND)
                        .setType(j.mimes.get(0).startsWith("video") ? "video/*" : "*/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (us.size() > 1) send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, us);
                else send.putExtra(Intent.EXTRA_STREAM, us.get(0));
                startActivity(Intent.createChooser(send, "Bagikan"));
            });
        }
        if (j.status != Jobs.RUN) {
            opts.add("Unduh ulang");
            acts.add(() -> ShareActivity.startDownload(this, j.url, null));
        }
        opts.add("Salin link");
        acts.add(() -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("link", j.url));
            Toast.makeText(this, "Link disalin", Toast.LENGTH_SHORT).show();
        });
        if (j.status != Jobs.RUN) {
            opts.add("Hapus dari riwayat");
            acts.add(() -> Jobs.remove(j));
        }
        new AlertDialog.Builder(this)
                .setTitle(j.status == Jobs.ERR ? j.msg : j.platform.label)
                .setItems(opts.toArray(new String[0]), (d, w) -> acts.get(w).run())
                .show();
    }

    // ============================== Tab Akun ==============================

    private void buildAkun() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 12);
        col.setPadding(p, p, p, p);

        TextView intro = Ui.text(this, "Login hanya perlu untuk akun privat yang Om ikuti, atau kalau postingan "
                + "publik gagal diunduh. Password diketik manual dan tidak disimpan; yang disimpan hanya sesi "
                + "login, terenkripsi dan hanya terbuka dengan sidik jari (berlaku 5 menit setiap kali dibuka).",
                13, Ui.MUTED);
        col.addView(intro);

        for (Platform pl : Platform.values()) {
            if (!pl.canLogin()) continue;
            LinearLayout c = Ui.card(this);
            LinearLayout r = Ui.row(this);
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = Ui.text(this, pl.label, 15.5f, Color.WHITE);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            texts.addView(name);
            boolean has = Vault.has(this, pl);
            String st = has ? "🔒 Login tersimpan · " + new SimpleDateFormat("d MMM yyyy", new Locale("id"))
                    .format(new Date(Vault.savedAt(this, pl))) : "Belum login";
            texts.addView(Ui.text(this, st, 12.5f, has ? Ui.OK : Ui.MUTED));
            r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
            if (has) {
                Button del = Ui.button(this, "Keluar", false);
                del.setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setMessage("Hapus login " + pl.label + " dari aplikasi ini?\n\nUntuk mencabut sepenuhnya, "
                                + "hapus juga perangkat ini di pengaturan keamanan akun " + pl.label + ".")
                        .setPositiveButton("Hapus", (d, w) -> {
                            Vault.remove(this, pl);
                            buildAkun();
                        })
                        .setNegativeButton("Batal", null).show());
                r.addView(del);
            }
            Button login = Ui.button(this, has ? "Ulangi" : "Login", !has);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = Ui.dp(this, 8);
            login.setOnClickListener(v -> {
                if (!Vault.deviceSecure(this)) {
                    Toast.makeText(this, "Aktifkan dulu sidik jari / PIN layar kunci HP.", Toast.LENGTH_LONG).show();
                    return;
                }
                startActivityForResult(new Intent(this, LoginActivity.class).putExtra("platform", pl.name()), REQ_LOGIN);
            });
            r.addView(login, lp);
            c.addView(r);
            if (pl == Platform.THREADS) {
                c.addView(Ui.text(this, "Postingan Threads publik biasanya bisa tanpa login.", 12, Ui.MUTED), Ui.mlp(this, 6));
            }
            col.addView(c, Ui.mlp(this, 10));
        }

        // Mesin unduh
        LinearLayout eng = Ui.card(this);
        TextView et = Ui.text(this, "Mesin unduh (yt-dlp)", 15.5f, Color.WHITE);
        et.setTypeface(Typeface.DEFAULT_BOLD);
        eng.addView(et);
        String ver = null;
        try {
            ver = YoutubeDL.INSTANCE.versionName(this);
        } catch (Throwable ignored) {
        }
        TextView ev = Ui.text(this, "Versi: " + (ver != null ? ver : "bawaan aplikasi")
                + "\nKalau IG / X / TikTok mendadak gagal semua, coba update mesin dulu.", 12.5f, Ui.MUTED);
        eng.addView(ev, Ui.mlp(this, 4));
        Button up = Ui.button(this, "Update mesin unduh", true);
        up.setOnClickListener(v -> updateEngine(up, ev));
        eng.addView(up, Ui.mlp(this, 10));
        col.addView(eng, Ui.mlp(this, 16));

        LinearLayout info = Ui.card(this);
        info.addView(Ui.text(this, "Lokasi file", 15.5f, Color.WHITE));
        info.addView(Ui.text(this, "Semua hasil masuk ke Galeri → album SimpanMedia\n(folder DCIM/SimpanMedia).",
                12.5f, Ui.MUTED), Ui.mlp(this, 4));
        col.addView(info, Ui.mlp(this, 10));

        TextView ver2 = Ui.text(this, "Simpan Media v" + appVersion(), 11.5f, 0xFF59616C);
        ver2.setGravity(Gravity.CENTER);
        col.addView(ver2, Ui.mlp(this, 16));

        akunView.removeAllViews();
        akunView.addView(col);
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void updateEngine(Button b, TextView ev) {
        b.setEnabled(false);
        b.setText("Mengupdate…");
        new Thread(() -> {
            String msg;
            try {
                DownloadService.ensureYdl(this);
                YoutubeDL.UpdateStatus s = YoutubeDL.INSTANCE.updateYoutubeDL(this, YoutubeDL.UpdateChannel._STABLE);
                String v = YoutubeDL.INSTANCE.versionName(this);
                msg = (s == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE ? "Sudah versi terbaru" : "Berhasil diupdate")
                        + (v != null ? " (" + v + ")" : "");
            } catch (Throwable t) {
                msg = "Gagal update: " + DownloadService.shortErr(t);
            }
            final String m = msg;
            runOnUiThread(() -> {
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                if (tab == 2) buildAkun();
            });
        }).start();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == StatusTab.REQ_TREE && res == RESULT_OK) statusTab.onTreeResult(data);
        if (req == REQ_LOGIN) buildAkun();
    }
}
