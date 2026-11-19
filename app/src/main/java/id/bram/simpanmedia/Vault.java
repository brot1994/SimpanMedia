package id.bram.simpanmedia;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.UserNotAuthenticatedException;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Brankas sesi login. Kunci AES ada di Android Keystore (chip keamanan HP) dan hanya bisa
 * dipakai selama 5 menit setelah sidik jari / PIN HP. Password TIDAK pernah disimpan.
 */
final class Vault {
    static final int AUTH_SECONDS = 300;
    private static final String ALIAS = "simpanmedia_sesi";
    private static final String PREFS = "vault";

    static class NeedAuth extends Exception {}

    private Vault() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean has(Context c, Platform p) {
        return prefs(c).contains("ck_" + p.name());
    }

    static void remove(Context c, Platform p) {
        prefs(c).edit().remove("ck_" + p.name()).remove("t_" + p.name()).apply();
    }

    static long savedAt(Context c, Platform p) {
        return prefs(c).getLong("t_" + p.name(), 0);
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true);
        if (Build.VERSION.SDK_INT >= 30) {
            b.setUserAuthenticationParameters(AUTH_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL);
        } else {
            b.setUserAuthenticationValidityDurationSeconds(AUTH_SECONDS);
        }
        kg.init(b.build());
        return kg.generateKey();
    }

    static void put(Context c, Platform p, String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key());
        } catch (UserNotAuthenticatedException e) {
            throw new NeedAuth();
        }
        byte[] iv = cipher.getIV();
        byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        ByteBuffer bb = ByteBuffer.allocate(1 + iv.length + ct.length);
        bb.put((byte) iv.length).put(iv).put(ct);
        prefs(c).edit()
                .putString("ck_" + p.name(), Base64.encodeToString(bb.array(), Base64.NO_WRAP))
                .putLong("t_" + p.name(), System.currentTimeMillis())
                .apply();
    }

    /** @return sesi (null kalau belum login), atau lempar NeedAuth kalau perlu sidik jari dulu. */
    static String get(Context c, Platform p) throws NeedAuth {
        String s = prefs(c).getString("ck_" + p.name(), null);
        if (s == null) return null;
        try {
            byte[] all = Base64.decode(s, Base64.NO_WRAP);
            int ivLen = all[0];
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            try {
                cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 1, ivLen));
            } catch (UserNotAuthenticatedException e) {
                throw new NeedAuth();
            }
            byte[] pt = cipher.doFinal(all, 1 + ivLen, all.length - 1 - ivLen);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (NeedAuth e) {
            throw e;
        } catch (Exception e) {
            // Kunci rusak / sidik jari HP diganti → sesi tidak bisa dibuka lagi, minta login ulang.
            remove(c, p);
            return null;
        }
    }

    static boolean deviceSecure(Context c) {
        BiometricManager bm = c.getSystemService(BiometricManager.class);
        if (bm == null) return false;
        int r;
        if (Build.VERSION.SDK_INT >= 30) {
            r = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        } else {
            r = bm.canAuthenticate();
        }
        return r == BiometricManager.BIOMETRIC_SUCCESS;
    }

    interface Result {
        void done(boolean ok);
    }

    static void authenticate(Activity a, String title, Result cb) {
        BiometricPrompt.Builder b = new BiometricPrompt.Builder(a)
                .setTitle(title)
                .setSubtitle("Tempel sidik jari untuk membuka sesi login");
        if (Build.VERSION.SDK_INT >= 30) {
            b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        } else {
            b.setDeviceCredentialAllowed(true);
        }
        b.build().authenticate(new CancellationSignal(), a.getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                        cb.done(true);
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence msg) {
                        cb.done(false);
                    }
                });
    }

    interface SessionResult {
        void done(String cookies, boolean cancelled);
    }

    /** Ambil sesi; kalau perlu, tampilkan sidik jari dulu. cookies=null kalau tidak ada login. */
    static void unlock(Activity a, Platform p, SessionResult cb) {
        if (!has(a, p)) {
            cb.done(null, false);
            return;
        }
        try {
            cb.done(get(a, p), false);
        } catch (NeedAuth e) {
            authenticate(a, "Buka login " + p.label, ok -> {
                if (!ok) {
                    cb.done(null, true);
                    return;
                }
                try {
                    cb.done(get(a, p), false);
                } catch (NeedAuth e2) {
                    cb.done(null, true);
                }
            });
        }
    }
}
