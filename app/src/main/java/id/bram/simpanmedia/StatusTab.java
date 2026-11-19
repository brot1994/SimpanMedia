package id.bram.simpanmedia;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tab Status WA: baca folder .Statuses WhatsApp (status yang sudah dilihat), simpan ke galeri. */
final class StatusTab extends LinearLayout {
    static final int REQ_TREE = 71;
    private static final String WA_DOC =
            "primary:Android/media/com.whatsapp/WhatsApp/Media/.Statuses";

    static final class Entry {
        Uri uri;
        String name, mime;
        long mod;
        boolean video;
    }

    private final Activity act;
    private final SharedPreferences prefs;
    private final List<Entry> items = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService thumbs = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 8)) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    private final FrameLayout body;
    private final TextView info;
    private final Button saveBtn, allBtn;
    private final Adapter adapter = new Adapter();

    StatusTab(Activity a) {
        super(a);
        act = a;
        prefs = a.getSharedPreferences("status", Context.MODE_PRIVATE);
        setOrientation(VERTICAL);
        int p = Ui.dp(a, 12);
        setPadding(p, p, p, 0);

        info = Ui.text(a, "", 13, Ui.MUTED);
        addView(info);

        body = new FrameLayout(a);
        addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bar = Ui.row(a);
        bar.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 10));
        allBtn = Ui.button(a, "Pilih semua", false);
        Button refresh = Ui.button(a, "Muat ulang", false);
        saveBtn = Ui.button(a, "Simpan", true);
        bar.addView(allBtn, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = Ui.dp(a, 8);
        bar.addView(refresh, lp);
        LayoutParams lp2 = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.2f);
        lp2.leftMargin = Ui.dp(a, 8);
        bar.addView(saveBtn, lp2);
        addView(bar);

        allBtn.setOnClickListener(v -> {
            if (selected.size() == items.size()) selected.clear();
            else for (Entry e : items) selected.add(e.name);
            adapter.notifyDataSetChanged();
            updateBar();
        });
        refresh.setOnClickListener(v -> reload());
        saveBtn.setOnClickListener(v -> saveSelected());
    }

    private Uri tree() {
        String s = prefs.getString("tree", null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission perm : act.getContentResolver().getPersistedUriPermissions()) {
            if (perm.getUri().equals(u) && perm.isReadPermission()) return u;
        }
        return null;
    }

    void reload() {
        Uri t = tree();
        body.removeAllViews();
        if (t == null) {
            showSetup();
            return;
        }
        info.setText("Ketuk untuk memilih · tekan lama untuk melihat. Status hanya muncul kalau "
                + "sudah Om buka di WhatsApp, dan hilang sekitar 24 jam.");
        GridView g = new GridView(act);
        g.setNumColumns(3);
        int sp = Ui.dp(act, 4);
        g.setHorizontalSpacing(sp);
        g.setVerticalSpacing(sp);
        g.setPadding(0, Ui.dp(act, 8), 0, 0);
        g.setClipToPadding(false);
        g.setAdapter(adapter);
        g.setOnItemClickListener((parent, view, pos, id) -> {
            Entry e = items.get(pos);
            if (!selected.remove(e.name)) selected.add(e.name);
            adapter.notifyDataSetChanged();
            updateBar();
        });
        g.setOnItemLongClickListener((parent, view, pos, id) -> {
            Entry e = items.get(pos);
            try {
                act.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(e.uri, e.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            } catch (Exception ex) {
                Toast.makeText(act, "Tidak ada aplikasi untuk membuka file ini", Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        body.addView(g);
        TextView empty = Ui.text(act, "Memuat…", 14, Ui.MUTED);
        empty.setGravity(Gravity.CENTER);
        body.addView(empty, new FrameLayout.LayoutParams(-1, -1));

        new Thread(() -> {
            List<Entry> list = list(t);
            main.post(() -> {
                items.clear();
                if (list != null) items.addAll(list);
                Set<String> names = new HashSet<>();
                for (Entry e : items) names.add(e.name);
                selected.retainAll(names);
                adapter.notifyDataSetChanged();
                if (list == null) {
                    empty.setText("Folder Status tidak bisa dibaca.\nCoba atur ulang izin di bawah.");
                    showSetupButton();
                } else if (items.isEmpty()) {
                    empty.setText("Belum ada status.\nBuka dulu status teman di WhatsApp, lalu Muat ulang.");
                } else {
                    empty.setVisibility(GONE);
                }
                updateBar();
            });
        }).start();
    }

    private void showSetup() {
        info.setText("");
        LinearLayout c = Ui.card(act);
        c.addView(Ui.text(act, "Izinkan akses folder Status WhatsApp", 17, Color.WHITE));
        TextView t = Ui.text(act, "Cukup sekali. Setelah tombol di bawah ditekan, layar folder akan terbuka "
                + "di folder .Statuses — tekan \"Gunakan folder ini\" lalu \"Izinkan\".\n\n"
                + "Status yang sudah Om lihat di WhatsApp akan muncul di sini dan bisa disimpan ke galeri.", 14, Ui.MUTED);
        c.addView(t, Ui.mlp(act, 8));
        Button b = Ui.button(act, "Izinkan akses", true);
        b.setOnClickListener(v -> requestTree());
        c.addView(b, Ui.mlp(act, 14));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(act, 8);
        body.addView(c, lp);
        updateBar();
    }

    private void showSetupButton() {
        Button b = Ui.button(act, "Atur ulang izin folder", true);
        b.setOnClickListener(v -> requestTree());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = Ui.dp(act, 20);
        body.addView(b, lp);
    }

    private void requestTree() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", WA_DOC));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            act.startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            Toast.makeText(act, "Pemilih folder tidak tersedia", Toast.LENGTH_LONG).show();
        }
    }

    void onTreeResult(Intent data) {
        if (data == null || data.getData() == null) return;
        Uri u = data.getData();
        try {
            act.getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        String docId = DocumentsContract.getTreeDocumentId(u);
        if (docId == null || !docId.endsWith(".Statuses")) {
            Toast.makeText(act, "Folder yang dipilih bukan .Statuses WhatsApp. Coba lagi dan pilih folder "
                    + "Android/media/com.whatsapp/WhatsApp/Media/.Statuses", Toast.LENGTH_LONG).show();
        }
        prefs.edit().putString("tree", u.toString()).apply();
        reload();
    }

    private List<Entry> list(Uri tree) {
        List<Entry> out = new ArrayList<>();
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        try (Cursor c = act.getContentResolver().query(kids, cols, null, null, null)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                String mime = c.getString(2);
                String name = c.getString(1);
                if (mime == null || name == null || name.startsWith(".")) continue;
                boolean video = mime.startsWith("video/");
                if (!video && !mime.startsWith("image/")) continue;
                Entry e = new Entry();
                e.uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0));
                e.name = name;
                e.mime = mime;
                e.mod = c.getLong(3);
                e.video = video;
                out.add(e);
            }
        } catch (Exception e) {
            return null;
        }
        out.sort((a, b) -> Long.compare(b.mod, a.mod));
        return out;
    }

    private Set<String> savedNames() {
        return new HashSet<>(prefs.getStringSet("saved", new HashSet<>()));
    }

    private void updateBar() {
        saveBtn.setText(selected.isEmpty() ? "Simpan" : "Simpan (" + selected.size() + ")");
        saveBtn.setEnabled(!selected.isEmpty());
        saveBtn.setAlpha(selected.isEmpty() ? 0.5f : 1f);
        allBtn.setText(!items.isEmpty() && selected.size() == items.size() ? "Batal pilih" : "Pilih semua");
    }

    private void saveSelected() {
        List<Entry> todo = new ArrayList<>();
        for (Entry e : items) if (selected.contains(e.name)) todo.add(e);
        if (todo.isEmpty()) return;
        saveBtn.setEnabled(false);
        saveBtn.setText("Menyimpan…");
        new Thread(() -> {
            int ok = 0;
            Set<String> saved = savedNames();
            for (Entry e : todo) {
                try (InputStream in = act.getContentResolver().openInputStream(e.uri)) {
                    if (in == null) continue;
                    Store.save(act, in, "WA_status_" + e.name, e.mime, null);
                    saved.add(e.name);
                    ok++;
                } catch (Exception ignored) {
                }
            }
            prefs.edit().putStringSet("saved", saved).apply();
            final int n = ok;
            main.post(() -> {
                Toast.makeText(act, n + " status tersimpan ke galeri (SimpanMedia)", Toast.LENGTH_LONG).show();
                selected.clear();
                adapter.notifyDataSetChanged();
                updateBar();
            });
        }).start();
    }

    // ------------------------------------------------------------------

    private static final class Tile extends FrameLayout {
        final ImageView img;
        final TextView play, check, done;

        Tile(Context c) {
            super(c);
            setBackgroundColor(Ui.CARD);
            img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(img, new LayoutParams(-1, -1));
            play = badge(c, "▶", Gravity.CENTER);
            play.setTextSize(22);
            play.setBackground(null);
            play.setShadowLayer(6, 0, 0, Color.BLACK);
            addView(play, new LayoutParams(-2, -2, Gravity.CENTER));
            check = badge(c, "✓", 0);
            check.setBackground(Ui.round(c, Ui.ACCENT, 14));
            LayoutParams lc = new LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28), Gravity.TOP | Gravity.END);
            lc.setMargins(0, Ui.dp(c, 6), Ui.dp(c, 6), 0);
            addView(check, lc);
            done = badge(c, "tersimpan", 0);
            done.setTextSize(10);
            done.setBackground(Ui.round(c, 0xCC1E8E4E, 6));
            done.setPadding(Ui.dp(c, 5), Ui.dp(c, 1), Ui.dp(c, 5), Ui.dp(c, 1));
            LayoutParams ld = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
            ld.setMargins(Ui.dp(c, 5), 0, 0, Ui.dp(c, 5));
            addView(done, ld);
        }

        private static TextView badge(Context c, String s, int g) {
            TextView t = new TextView(c);
            t.setText(s);
            t.setTextColor(Color.WHITE);
            t.setGravity(Gravity.CENTER);
            return t;
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, w);
        }
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Tile t = convert instanceof Tile ? (Tile) convert : new Tile(act);
            Entry e = items.get(pos);
            boolean sel = selected.contains(e.name);
            t.play.setVisibility(e.video ? VISIBLE : GONE);
            t.check.setVisibility(sel ? VISIBLE : GONE);
            t.img.setAlpha(sel ? 0.6f : 1f);
            t.done.setVisibility(savedNames().contains(e.name) ? VISIBLE : GONE);
            String key = e.uri.toString();
            t.img.setTag(key);
            Bitmap b = cache.get(key);
            if (b != null) {
                t.img.setImageBitmap(b);
            } else {
                t.img.setImageDrawable(null);
                thumbs.execute(() -> {
                    Bitmap bm = thumb(e);
                    if (bm == null) return;
                    cache.put(key, bm);
                    main.post(() -> {
                        if (key.equals(t.img.getTag())) t.img.setImageBitmap(bm);
                    });
                });
            }
            return t;
        }
    }

    private Bitmap thumb(Entry e) {
        int target = Ui.dp(act, 130);
        try {
            if (e.video) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(act, e.uri);
                    return r.getScaledFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, target, target);
                } finally {
                    r.release();
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = act.getContentResolver().openInputStream(e.uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int s = 1;
            while (Math.min(o.outWidth, o.outHeight) / (s * 2) >= target) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            try (InputStream in = act.getContentResolver().openInputStream(e.uri)) {
                return BitmapFactory.decodeStream(in, null, o2);
            }
        } catch (Exception ex) {
            return null;
        }
    }
}
