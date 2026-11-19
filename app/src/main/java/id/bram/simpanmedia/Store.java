package id.bram.simpanmedia;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Simpan file ke galeri: semua masuk ke satu folder DCIM/SimpanMedia. */
final class Store {
    static final String FOLDER = "DCIM/SimpanMedia";
    static final String FOLDER_OTHER = "Download/SimpanMedia";

    private Store() {}

    interface Progress {
        void bytes(long done);
    }

    static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    static String extOf(String mime, String fallback) {
        if (mime != null) {
            String m = mime.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (m.equals("image/jpeg") || m.equals("image/jpg")) return "jpg";
            if (m.equals("video/mp4")) return "mp4";
            String e = MimeTypeMap.getSingleton().getExtensionFromMimeType(m);
            if (e != null) return e;
        }
        return fallback;
    }

    static String mimeOf(String name, String fallback) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (m != null) return m;
            if (ext.equals("mkv")) return "video/x-matroska";
            if (ext.equals("webm")) return "video/webm";
            if (ext.equals("m4a")) return "audio/mp4";
        }
        return fallback;
    }

    static Uri save(Context ctx, InputStream in, String name, String mime, Progress p) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        Uri collection;
        String rel = FOLDER;
        if (mime.startsWith("video/")) {
            collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        } else if (mime.startsWith("image/")) {
            collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        } else {
            collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            rel = FOLDER_OTHER;
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(collection, v);
        if (uri == null) throw new IOException("Tidak bisa membuat file di galeri");
        try (OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) throw new IOException("Tidak bisa menulis file");
            byte[] buf = new byte[65536];
            int n;
            long total = 0, last = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (p != null && total - last > 256 * 1024) {
                    last = total;
                    p.bytes(total);
                }
            }
        } catch (IOException e) {
            cr.delete(uri, null, null);
            throw e;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, done, null, null);
        return uri;
    }
}
