package id.bram.simpanmedia;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Browser tersembunyi: memuat halaman postingan (JavaScript ikut jalan seperti browser biasa),
 * lalu membaca data postingan dari halaman. Dipakai untuk Threads (yt-dlp tidak mendukung Threads)
 * dan cadangan Instagram.
 */
final class WebGrab {
    static final class Result {
        final List<Extract.Item> items = new ArrayList<>();
        String note = "";
    }

    private WebGrab() {}

    private static final String JS =
            "(function(code){"
            + "if(!code){var mm=location.pathname.match(/\\/(?:post|p|reel|reels|tv)\\/([A-Za-z0-9_-]+)/);"
            + " if(!mm)return JSON.stringify({n:0,items:[],title:document.title,url:location.href});code=mm[1];}"
            + "function walk(o,out,d){if(!o||typeof o!=='object'||d>80||out.length)return;"
            + " if(Array.isArray(o)){for(var i=0;i<o.length;i++)walk(o[i],out,d+1);return;}"
            + " if(o.code===code&&(o.image_versions2||o.video_versions||o.carousel_media)){out.push(o);return;}"
            + " for(var k in o){if(o[k]&&typeof o[k]==='object')walk(o[k],out,d+1);}}"
            + "var found=[],ss=document.querySelectorAll('script[type=\"application/json\"]');"
            + "for(var i=0;i<ss.length&&!found.length;i++){var t=ss[i].textContent;"
            + " if(!t||t.indexOf(code)<0)continue;try{walk(JSON.parse(t),found,0);}catch(e){}}"
            + "function best(a){var b=null;for(var i=0;i<a.length;i++){if(!b||(a[i].width||0)>(b.width||0))b=a[i];}return b;}"
            + "function media(m,out){if(m.carousel_media&&m.carousel_media.length){for(var i=0;i<m.carousel_media.length;i++)media(m.carousel_media[i],out);return;}"
            + " if(m.video_versions&&m.video_versions.length){var v=best(m.video_versions);if(v&&v.url){out.push({u:v.url,v:true});return;}}"
            + " var c=m.image_versions2&&m.image_versions2.candidates;if(c&&c.length){var x=best(c);if(x&&x.url)out.push({u:x.url,v:false});}}"
            + "var out=[];if(found.length)media(found[0],out);"
            + "var src='json';"
            + "if(!out.length){src='dom';var root=document.querySelector('[data-pressable-container=true]')||document.querySelector('article')||document;"
            + " var vs=root.querySelectorAll('video');for(var i=0;i<vs.length;i++){var s=vs[i].currentSrc||vs[i].src;"
            + "  if(!s){var so=vs[i].querySelector('source');if(so)s=so.src;}if(s&&s.indexOf('http')===0)out.push({u:s,v:true});}"
            + " var is=root.querySelectorAll('img');for(var i=0;i<is.length;i++){var s=is[i].currentSrc||is[i].src;"
            + "  if(s&&/cdninstagram|fbcdn/.test(s)&&is[i].naturalWidth>=320&&!/s150x150|_s\\.jpg|profile_pic/.test(s))out.push({u:s,v:false});}}"
            + "return JSON.stringify({n:ss.length,src:src,items:out,title:document.title,url:location.href});"
            + "})(%s)";

    /** Blocking — panggil dari thread latar, bukan main thread. */
    static Result run(Context ctx, String url, String code, String cookieLines, String cookieHost, long timeoutMs)
            throws InterruptedException {
        Result res = new Result();
        CountDownLatch done = new CountDownLatch(1);
        Handler main = new Handler(Looper.getMainLooper());
        WebView[] holder = new WebView[1];
        String script = String.format(JS, JSONObject.quote(code));
        long deadline = System.currentTimeMillis() + timeoutMs;

        main.post(() -> {
            try {
                holder[0] = create(ctx.getApplicationContext());
                if (cookieLines != null) setCookies(cookieLines, cookieHost);
                holder[0].loadUrl(url);
                poll(main, holder[0], script, res, done, deadline, 2500);
            } catch (Throwable t) {
                res.note = "browser gagal: " + t.getMessage();
                done.countDown();
            }
        });

        done.await(timeoutMs + 5000, TimeUnit.MILLISECONDS);
        main.post(() -> {
            WebView w = holder[0];
            if (w != null) {
                w.stopLoading();
                w.loadUrl("about:blank");
                w.clearCache(true);
                w.destroy();
            }
            // Sesi login hanya boleh ada di brankas terenkripsi.
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
        });
        return res;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private static WebView create(Context c) {
        WebView w = new WebView(c);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUserAgentString(Http.UA_DESKTOP);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.setWebViewClient(new WebViewClient());
        // Ukuran layar virtual supaya halaman ter-render normal walau tidak tampil.
        int wSpec = View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY);
        int hSpec = View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY);
        w.measure(wSpec, hSpec);
        w.layout(0, 0, 1280, 2000);
        return w;
    }

    private static void setCookies(String lines, String host) {
        CookieManager cm = CookieManager.getInstance();
        for (String line : lines.split("\n")) {
            String[] p = line.split("\t", 3);
            if (p.length < 3) continue;
            String d = p[0].startsWith(".") ? p[0].substring(1) : p[0];
            if (!(host.equals(d) || host.endsWith("." + d))) continue;
            cm.setCookie("https://" + host, p[1] + "=" + p[2] + "; Domain=." + d + "; Path=/; Secure");
        }
        cm.flush();
    }

    private static void poll(Handler main, WebView w, String script, Result res, CountDownLatch done,
                             long deadline, long delay) {
        main.postDelayed(() -> {
            if (done.getCount() == 0) return;
            w.evaluateJavascript(script, raw -> {
                try {
                    Object v = new JSONTokener(raw).nextValue();
                    if (v instanceof String) {
                        JSONObject o = new JSONObject((String) v);
                        JSONArray items = o.optJSONArray("items");
                        res.note = "skrip=" + o.optInt("n") + " judul=\"" + o.optString("title") + "\" alamat=" + o.optString("url");
                        if (items != null && items.length() > 0) {
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject it = items.getJSONObject(i);
                                res.items.add(new Extract.Item(it.getString("u"), it.optBoolean("v")));
                            }
                            done.countDown();
                            return;
                        }
                    }
                } catch (Exception ignored) {
                }
                if (System.currentTimeMillis() >= deadline) {
                    done.countDown();
                } else {
                    poll(main, w, script, res, done, deadline, 1500);
                }
            });
        }, delay);
    }
}
