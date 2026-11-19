package id.bram.simpanmedia;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class Http {
    static final String UA_MOBILE = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    static final String UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private Http() {}

    /**
     * Buka koneksi dan ikuti redirect secara manual. Header Cookie hanya dikirim ke host
     * yang sama dengan link awal, supaya sesi login tidak bocor ke situs lain.
     */
    static HttpURLConnection open(String url, Map<String, String> headers) throws IOException {
        String u = url;
        String firstHost = new URL(url).getHost();
        for (int i = 0; i < 8; i++) {
            URL target = new URL(u);
            HttpURLConnection c = (HttpURLConnection) target.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", UA_MOBILE);
            c.setRequestProperty("Accept-Language", "id-ID,id;q=0.9,en;q=0.8");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getValue() == null) continue;
                    if (e.getKey().equalsIgnoreCase("Cookie") && !target.getHost().equals(firstHost)) continue;
                    c.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("Redirect tanpa tujuan");
                u = new URL(target, loc).toString();
                continue;
            }
            if (code >= 400) {
                c.disconnect();
                throw new IOException("HTTP " + code);
            }
            return c;
        }
        throw new IOException("Terlalu banyak redirect");
    }

    static String text(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection c = open(url, headers);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total > 12_000_000) break;
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }

    /** Link pendek / share → link akhir. */
    static String resolve(String url) {
        try {
            HttpURLConnection c = open(url, null);
            String f = c.getURL().toString();
            c.disconnect();
            return f;
        } catch (Exception e) {
            return url;
        }
    }
}
