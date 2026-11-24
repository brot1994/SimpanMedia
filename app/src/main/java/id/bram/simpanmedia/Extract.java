package id.bram.simpanmedia;

import android.net.Uri;
import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pengambil link media langsung untuk kasus yang kurang bagus di yt-dlp:
 * foto & carousel Instagram, Threads, foto X. Semua lainnya lewat yt-dlp.
 */
final class Extract {
    static final class Item {
        final String url;
        final boolean video;

        Item(String url, boolean video) {
            this.url = url;
            this.video = video;
        }
    }

    private Extract() {}

    // ---------------- Instagram ----------------

    private static final String IG_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    static String igShortcodeToId(String code) {
        BigInteger id = BigInteger.ZERO;
        BigInteger b64 = BigInteger.valueOf(64);
        for (char ch : code.toCharArray()) {
            int v = IG_ALPHABET.indexOf(ch);
            if (v < 0) return null;
            id = id.multiply(b64).add(BigInteger.valueOf(v));
        }
        return id.toString();
    }

    /** Butuh login. Menangani foto, video, carousel, dan story. */
    static List<Item> instagram(String url, String cookies) throws Exception {
        List<Item> out = new ArrayList<>();
        if (cookies == null) return out;
        String id = null;
        Matcher m = Pattern.compile("instagram\\.com/(?:[A-Za-z0-9_.]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)")
                .matcher(url);
        if (m.find()) {
            String code = m.group(1);
            if (code.length() > 11) code = code.substring(0, 11);
            id = igShortcodeToId(code);
        } else {
            Matcher s = Pattern.compile("instagram\\.com/stories/[^/]+/(\\d+)").matcher(url);
            if (s.find()) id = s.group(1);
        }
        if (id == null) return out;
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", Http.UA_DESKTOP);
        h.put("X-IG-App-ID", "936619743392459");
        h.put("X-Requested-With", "XMLHttpRequest");
        h.put("Referer", "https://www.instagram.com/");
        h.put("Accept", "*/*");
        h.put("Cookie", Cookies.header(cookies, "www.instagram.com"));
        String csrf = Cookies.value(cookies, "csrftoken");
        if (csrf != null) h.put("X-CSRFToken", csrf);
        String json = Http.text("https://www.instagram.com/api/v1/media/" + id + "/info/", h);
        JSONObject root = new JSONObject(json);
        JSONArray items = root.optJSONArray("items");
        if (items != null && items.length() > 0) media(items.getJSONObject(0), out);
        return out;
    }

    /** Struktur media Instagram & Threads sama. */
    static void media(JSONObject m, List<Item> out) {
        JSONArray car = m.optJSONArray("carousel_media");
        if (car != null && car.length() > 0) {
            for (int i = 0; i < car.length(); i++) {
                JSONObject c = car.optJSONObject(i);
                if (c != null) media(c, out);
            }
            return;
        }
        JSONArray vv = m.optJSONArray("video_versions");
        if (vv != null && vv.length() > 0) {
            String best = null;
            int bw = -1;
            for (int i = 0; i < vv.length(); i++) {
                JSONObject v = vv.optJSONObject(i);
                if (v == null) continue;
                int w = v.optInt("width", 0);
                if (best == null || w > bw) {
                    best = v.optString("url", null);
                    bw = w;
                }
            }
            if (best != null) {
                out.add(new Item(best, true));
                return;
            }
        }
        JSONObject iv = m.optJSONObject("image_versions2");
        JSONArray cands = iv != null ? iv.optJSONArray("candidates") : null;
        if (cands != null && cands.length() > 0) {
            String best = null;
            int bw = -1;
            for (int i = 0; i < cands.length(); i++) {
                JSONObject c = cands.optJSONObject(i);
                if (c == null) continue;
                int w = c.optInt("width", 0);
                if (best == null || w > bw) {
                    best = c.optString("url", null);
                    bw = w;
                }
            }
            if (best != null) out.add(new Item(best, false));
        }
    }

    // ---------------- Threads ----------------

    static List<Item> threads(String url, String cookies) throws Exception {
        List<Item> out = new ArrayList<>();
        Matcher m = Pattern.compile("/post/([A-Za-z0-9_-]+)").matcher(url);
        if (!m.find()) return out;
        String code = m.group(1);
        Uri u = Uri.parse(url);
        String page = "https://www.threads.com" + u.getPath();
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", Http.UA_DESKTOP);
        h.put("Accept", "text/html,application/xhtml+xml");
        h.put("Sec-Fetch-Mode", "navigate");
        h.put("Sec-Fetch-Dest", "document");
        h.put("Sec-Fetch-Site", "none");
        if (cookies != null) h.put("Cookie", Cookies.header(cookies, "www.threads.com"));
        String html = Http.text(page, h);
        findPost(html, code, out);
        if (out.isEmpty() && html.contains("\\\"post\\\"")) {
            findPost(html.replace("\\\"", "\"").replace("\\/", "/"), code, out);
        }
        return out;
    }

    private static void findPost(String html, String code, List<Item> out) {
        String needle = "\"post\":{";
        String codeStr = "\"" + code + "\"";
        int idx = 0, tries = 0;
        while ((idx = html.indexOf(needle, idx)) >= 0 && tries++ < 400) {
            int start = idx + needle.length() - 1;
            idx += needle.length();
            String obj = balanced(html, start);
            if (obj == null || !obj.contains(codeStr)) continue;
            try {
                JSONObject o = new JSONObject(obj);
                if (code.equals(o.optString("code"))) {
                    media(o, out);
                    if (!out.isEmpty()) return;
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** Ambil objek JSON {…} yang seimbang mulai dari posisi '{'. */
    static String balanced(String s, int start) {
        if (start >= s.length() || s.charAt(start) != '{') return null;
        int depth = 0;
        boolean inStr = false, esc = false;
        int limit = Math.min(s.length(), start + 3_000_000);
        for (int i = start; i < limit; i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return s.substring(start, i + 1);
            }
        }
        return null;
    }

    // ---------------- X / Twitter ----------------

    /** Lewat layanan publik FxTwitter (tanpa login): foto asli + video. */
    static List<Item> twitter(String url) throws Exception {
        List<Item> out = new ArrayList<>();
        Matcher m = Pattern.compile("/status(?:es)?/(\\d+)").matcher(url);
        if (!m.find()) return out;
        String json = Http.text("https://api.fxtwitter.com/status/" + m.group(1), null);
        JSONObject tweet = new JSONObject(json).optJSONObject("tweet");
        if (tweet == null) return out;
        JSONObject media = tweet.optJSONObject("media");
        if (media == null) return out;
        JSONArray all = media.optJSONArray("all");
        if (all == null) {
            all = new JSONArray();
            JSONArray ph = media.optJSONArray("photos"), vd = media.optJSONArray("videos");
            if (ph != null) for (int i = 0; i < ph.length(); i++) all.put(ph.get(i));
            if (vd != null) for (int i = 0; i < vd.length(); i++) all.put(vd.get(i));
        }
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null) continue;
            String type = o.optString("type");
            String u = o.optString("url", null);
            if (u == null || u.isEmpty()) continue;
            if (type.equals("photo")) {
                if (u.contains("pbs.twimg.com/media") && !u.contains("?")) u += "?name=orig";
                out.add(new Item(u, false));
            } else {
                out.add(new Item(u, true));
            }
        }
        return out;
    }

    /** Cadangan kedua untuk X: layanan publik vxTwitter. */
    static List<Item> twitterVx(String url) throws Exception {
        List<Item> out = new ArrayList<>();
        Matcher m = Pattern.compile("/status(?:es)?/(\\d+)").matcher(url);
        if (!m.find()) return out;
        JSONObject o = new JSONObject(Http.text("https://api.vxtwitter.com/Twitter/status/" + m.group(1), null));
        JSONArray me = o.optJSONArray("media_extended");
        if (me != null) {
            for (int i = 0; i < me.length(); i++) {
                JSONObject x = me.optJSONObject(i);
                if (x == null) continue;
                String u = x.optString("url", "");
                if (u.isEmpty()) continue;
                boolean img = "image".equals(x.optString("type"));
                if (img && u.contains("pbs.twimg.com/media") && !u.contains("?")) u += "?name=orig";
                out.add(new Item(u, !img));
            }
        }
        return out;
    }

    // ---------------- Cadangan: meta og: ----------------

    private static final Pattern OG1 = Pattern.compile(
            "<meta[^>]+property=[\"']og:(video:secure_url|video:url|video|image)[\"'][^>]*content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OG2 = Pattern.compile(
            "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]*property=[\"']og:(video:secure_url|video:url|video|image)[\"']",
            Pattern.CASE_INSENSITIVE);

    static List<Item> og(String url, String cookies) throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", Http.UA_DESKTOP);
        if (cookies != null) {
            String host = Uri.parse(url).getHost();
            h.put("Cookie", Cookies.header(cookies, host));
        }
        String html = Http.text(url, h);
        Set<String> videos = new LinkedHashSet<>(), images = new LinkedHashSet<>();
        Matcher m1 = OG1.matcher(html);
        while (m1.find()) add(m1.group(1), m1.group(2), videos, images);
        Matcher m2 = OG2.matcher(html);
        while (m2.find()) add(m2.group(2), m2.group(1), videos, images);
        List<Item> out = new ArrayList<>();
        if (!videos.isEmpty()) {
            out.add(new Item(videos.iterator().next(), true));
        } else if (!images.isEmpty()) {
            out.add(new Item(images.iterator().next(), false));
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    private static void add(String kind, String val, Set<String> videos, Set<String> images) {
        String u = Html.fromHtml(val).toString();
        if (!u.startsWith("http")) return;
        if (kind.toLowerCase().startsWith("video")) videos.add(u);
        else images.add(u);
    }
}
