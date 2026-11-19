package id.bram.simpanmedia;

import android.net.Uri;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Platform yang dikenali + data untuk halaman "Login sekali". */
enum Platform {
    INSTAGRAM("Instagram", "https://www.instagram.com/accounts/login/",
            new String[]{"https://www.instagram.com", "https://i.instagram.com"}, "sessionid"),
    THREADS("Threads", "https://www.threads.com/login",
            new String[]{"https://www.threads.com", "https://www.threads.net"}, "sessionid"),
    X("X / Twitter", "https://x.com/i/flow/login",
            new String[]{"https://x.com", "https://twitter.com"}, "auth_token"),
    FACEBOOK("Facebook", "https://m.facebook.com/login/",
            new String[]{"https://www.facebook.com", "https://m.facebook.com"}, "c_user"),
    TIKTOK("TikTok", "https://www.tiktok.com/login",
            new String[]{"https://www.tiktok.com"}, "sessionid"),
    YOUTUBE("YouTube", null, null, null),
    WEB("Web", null, null, null);

    final String label;
    final String loginUrl;
    final String[] cookieUrls;
    final String loginMarker;

    Platform(String label, String loginUrl, String[] cookieUrls, String loginMarker) {
        this.label = label;
        this.loginUrl = loginUrl;
        this.cookieUrls = cookieUrls;
        this.loginMarker = loginMarker;
    }

    boolean canLogin() {
        return loginUrl != null;
    }

    static Platform of(String url) {
        String host;
        try {
            host = Uri.parse(url).getHost();
        } catch (Exception e) {
            host = null;
        }
        if (host == null) return WEB;
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith("instagram.com") || host.equals("instagr.am")) return INSTAGRAM;
        if (host.endsWith("threads.net") || host.endsWith("threads.com")) return THREADS;
        if (host.equals("x.com") || host.endsWith(".x.com") || host.endsWith("twitter.com")
                || host.equals("t.co")) return X;
        if (host.endsWith("facebook.com") || host.endsWith("fb.watch") || host.equals("fb.com"))
            return FACEBOOK;
        if (host.endsWith("tiktok.com")) return TIKTOK;
        if (host.endsWith("youtube.com") || host.equals("youtu.be")) return YOUTUBE;
        return WEB;
    }

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"']+");

    /** Ambil link pertama dari teks yang dibagikan (sering ada teks lain di depannya). */
    static String findUrl(String text) {
        if (text == null) return null;
        Matcher m = URL.matcher(text);
        if (!m.find()) return null;
        String u = m.group();
        while (u.endsWith(".") || u.endsWith(",") || u.endsWith(")")) u = u.substring(0, u.length() - 1);
        return u;
    }
}
