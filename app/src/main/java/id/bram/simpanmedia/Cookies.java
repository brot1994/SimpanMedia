package id.bram.simpanmedia;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Sesi login disimpan sebagai baris "domain\tnama\tnilai".
 * (Isi ini selalu terenkripsi di Vault; hanya ada di memori saat dipakai.)
 */
final class Cookies {
    private Cookies() {}

    static String header(String lines, String host) {
        if (lines == null || host == null) return null;
        StringBuilder sb = new StringBuilder();
        for (String line : lines.split("\n")) {
            String[] p = line.split("\t", 3);
            if (p.length < 3) continue;
            String d = p[0].startsWith(".") ? p[0].substring(1) : p[0];
            if (host.equals(d) || host.endsWith("." + d)) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(p[1]).append('=').append(p[2]);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    static String value(String lines, String name) {
        if (lines == null) return null;
        for (String line : lines.split("\n")) {
            String[] p = line.split("\t", 3);
            if (p.length == 3 && p[1].equals(name)) return p[2];
        }
        return null;
    }

    /** File cookie format Netscape untuk yt-dlp. Wajib dihapus setelah dipakai. */
    static File netscapeFile(Context ctx, String lines) throws IOException {
        File dir = new File(ctx.getNoBackupFilesDir(), "tmpck");
        dir.mkdirs();
        File f = new File(dir, UUID.randomUUID() + ".txt");
        StringBuilder sb = new StringBuilder("# Netscape HTTP Cookie File\n");
        for (String line : lines.split("\n")) {
            String[] p = line.split("\t", 3);
            if (p.length < 3) continue;
            String d = p[0].startsWith(".") ? p[0] : "." + p[0];
            sb.append(d).append("\tTRUE\t/\tTRUE\t2147483647\t")
                    .append(p[1]).append('\t').append(p[2]).append('\n');
        }
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }

    static void wipeTemp(Context ctx) {
        File dir = new File(ctx.getNoBackupFilesDir(), "tmpck");
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }
}
