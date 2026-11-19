package id.bram.simpanmedia;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Halaman "Login sekali". Password diketik manual, tidak disimpan siapa pun (autofill dimatikan).
 * Setelah login, hanya sesi (cookie) yang diambil, dienkripsi dengan kunci sidik jari,
 * lalu semua data browser di aplikasi ini dihapus.
 */
public class LoginActivity extends Activity {
    private WebView web;
    private Platform platform;
    private boolean saved;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        platform = Platform.valueOf(getIntent().getStringExtra("platform"));
        float d = getResources().getDisplayMetrics().density;

        // Mulai bersih: jangan ada sisa sesi lama di WebView.
        clearBrowserData();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
        bar.setBackgroundColor(Ui.CARD);
        TextView title = new TextView(this);
        title.setText("Login " + platform.label);
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button cancel = Ui.button(this, "Batal", false);
        cancel.setOnClickListener(v -> finish());
        bar.addView(cancel);
        Button save = Ui.button(this, "Simpan login", true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = (int) (8 * d);
        bar.addView(save, lp);
        root.addView(bar);

        TextView hint = new TextView(this);
        hint.setText("Ketik username & password secara manual. Password tidak disimpan — hanya sesi login "
                + "yang dikunci sidik jari. Login lewat tombol Google tidak didukung di sini.");
        hint.setTextColor(0xFFAAB2BD);
        hint.setTextSize(12.5f);
        hint.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
        root.addView(hint);

        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        root.addView(pb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (3 * d)));

        web = new WebView(this);
        web.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSaveFormData(false);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "").replace(" Version/4.0", ""));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                pb.setProgress(p);
                pb.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        save.setOnClickListener(v -> trySave());
        web.loadUrl(platform.loginUrl);
    }

    /** Kumpulkan cookie dari semua domain platform → baris "domain\tnama\tnilai". */
    private String collect() {
        CookieManager cm = CookieManager.getInstance();
        cm.flush();
        Map<String, String> seen = new LinkedHashMap<>();
        for (String u : platform.cookieUrls) {
            String raw = cm.getCookie(u);
            if (raw == null) continue;
            String host = Uri.parse(u).getHost();
            String domain = host.startsWith("www.") ? host.substring(4) : host;
            if (domain.startsWith("m.") || domain.startsWith("i.")) domain = domain.substring(2);
            for (String part : raw.split(";")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String name = part.substring(0, eq).trim();
                String val = part.substring(eq + 1).trim();
                seen.put(domain + "\t" + name, val);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : seen.entrySet()) {
            sb.append('.').append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    private void trySave() {
        String c = collect();
        boolean marker = c.contains("\t" + platform.loginMarker + "\t");
        if (!marker) {
            new AlertDialog.Builder(this)
                    .setTitle("Login belum terdeteksi")
                    .setMessage("Sepertinya Om belum selesai login. Selesaikan login dulu "
                            + "(sampai beranda " + platform.label + " tampil), lalu tekan Simpan lagi.")
                    .setPositiveButton("Oke", null)
                    .setNegativeButton("Tetap simpan", (dlg, w) -> doSave(c))
                    .show();
            return;
        }
        doSave(c);
    }

    private void doSave(String cookies) {
        if (!Vault.deviceSecure(this)) {
            Toast.makeText(this, "Aktifkan dulu sidik jari / PIN layar kunci HP.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Vault.put(this, platform, cookies);
            done();
        } catch (Vault.NeedAuth e) {
            Vault.authenticate(this, "Kunci login " + platform.label, ok -> {
                if (!ok) return;
                try {
                    Vault.put(this, platform, cookies);
                    done();
                } catch (Exception ex) {
                    Toast.makeText(this, "Gagal menyimpan: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception ex) {
            Toast.makeText(this, "Gagal menyimpan: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void done() {
        saved = true;
        Toast.makeText(this, "Login " + platform.label + " tersimpan & dikunci sidik jari", Toast.LENGTH_LONG).show();
        setResult(RESULT_OK);
        finish();
    }

    private void clearBrowserData() {
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        WebStorage.getInstance().deleteAllData();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.clearHistory();
            web.clearCache(true);
            web.clearFormData();
            web.destroy();
        }
        // Sesi hanya boleh ada di brankas terenkripsi, bukan di WebView.
        clearBrowserData();
        super.onDestroy();
    }
}
