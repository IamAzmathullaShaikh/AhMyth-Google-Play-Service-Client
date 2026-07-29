/*
 * JNI bridge for ObfuscationUtils.nativeDecrypt() / nativeMatches().
 *
 * The AES key is XOR-obfuscated here in native code, so even if
 * an attacker extracts the .so library they still don't have the
 * raw key — only the XOR-masked version + the unmask constant.
 *
 * Thread safety: key initialization uses pthread_once.
 * Stack cleaning: memset(0) on all sensitive buffers before returning.
 * Bounds checking: encrypted data > 48 bytes is rejected.
 */

#include <jni.h>
#include <pthread.h>
#include <string.h>
#include <strings.h>   /* strncasecmp */
#include "aes.h"

/* ================================================================
 * XOR-obfuscated AES-128 key (16 bytes).
 *
 * Plain key (UTF-8): a1b2c3d4e5f6g7h8
 * Storage:  8 bytes XOR'd with 0xAA + 8 bytes derived from first half
 * ================================================================ */
static const uint8_t KEY_MASKED[8] = {
    0xa1 ^ 0xAA, 0xb2 ^ 0xAA, 0xc3 ^ 0xAA, 0xd4 ^ 0xAA,
    0xe5 ^ 0xAA, 0xf6 ^ 0xAA, 0x67 ^ 0xAA, 0x68 ^ 0xAA,
};
#define XOR_MASK_1   0xAA
#define XOR_MASK_2   0x55

/* Recovered real key (populated once via pthread_once) */
static uint8_t real_key[16];
static pthread_once_t key_once = PTHREAD_ONCE_INIT;

/* ================================================================
 * One-time key recovery from obfuscated form.
 * ================================================================ */
static void do_ensure_key(void) {
    int i;
    for (i = 0; i < 8; i++) {
        real_key[i] = KEY_MASKED[i] ^ XOR_MASK_1;
    }
    for (i = 8; i < 16; i++) {
        real_key[i] = real_key[i - 8] ^ XOR_MASK_2;
    }
}

static void ensure_key(void) {
    pthread_once(&key_once, do_ensure_key);
}

/* ================================================================
 * Common helper: decrypt byte array into a caller-provided buffer.
 * Returns the unpadded length, or 0 on failure.
 * The output buffer must be at least `len` bytes.
 * ================================================================ */
static int decrypt_bytes(const jbyte *elements, jsize len,
                         uint8_t *output, int output_cap) {
    uint32_t rk[44];
    uint8_t  block[16];
    int      i, num_blocks, out_len = 0, pad_len;

    if (len <= 0 || len > output_cap) return 0;

    aes128_key_expand(real_key, rk);

    num_blocks = len / 16;
    if (num_blocks == 0) return 0;

    for (i = 0; i < num_blocks; i++) {
        memcpy(block, elements + i * 16, 16);
        aes128_decrypt_block(block, rk);
        memcpy(output + i * 16, block, 16);
        out_len += 16;
    }

    /* Strip PKCS7 padding */
    pad_len = output[out_len - 1];
    if (pad_len > 0 && pad_len <= 16 && pad_len <= out_len) {
        out_len -= pad_len;
    }
    if (out_len < 0) out_len = 0;

    /* Wipe sensitive stack buffers */
    memset(rk, 0, sizeof(rk));
    memset(block, 0, sizeof(block));

    return out_len;
}

/* ================================================================
 * JNI: nativeDecrypt
 * Signature: ([B)Ljava/lang/String;
 * ================================================================ */
JNIEXPORT jstring JNICALL
Java_com_android_background_services_ObfuscationUtils_nativeDecrypt(
    JNIEnv *env,
    jclass clazz,
    jbyteArray encrypted)
{
    jsize   len;
    jbyte  *elements;
    uint8_t output[48];
    int     out_len;
    jstring result;

    ensure_key();

    len      = (*env)->GetArrayLength(env, encrypted);
    elements = (*env)->GetByteArrayElements(env, encrypted, NULL);
    if (elements == NULL) return (*env)->NewStringUTF(env, "");

    /* Guard: our encrypted constants are at most 32 bytes */
    if (len > 48 || len <= 0) {
        (*env)->ReleaseByteArrayElements(env, encrypted, elements, JNI_ABORT);
        return (*env)->NewStringUTF(env, "");
    }

    memset(output, 0, sizeof(output));
    out_len = decrypt_bytes(elements, len, output, sizeof(output));

    (*env)->ReleaseByteArrayElements(env, encrypted, elements, JNI_ABORT);

    result = (*env)->NewStringUTF(env, (const char *)output);

    /* Wipe output */
    memset(output, 0, sizeof(output));

    return result;
}

/* ================================================================
 * JNI: nativeMatches
 * Signature: ([BLjava/lang/String;)Z
 * ================================================================ */
JNIEXPORT jboolean JNICALL
Java_com_android_background_services_ObfuscationUtils_nativeMatches(
    JNIEnv *env,
    jclass clazz,
    jbyteArray encrypted,
    jstring value)
{
    jsize       len;
    jbyte      *elements;
    uint8_t     output[48];
    int         out_len;
    const char *native_value;
    jboolean    result;

    if (value == NULL) return JNI_FALSE;

    ensure_key();

    len      = (*env)->GetArrayLength(env, encrypted);
    elements = (*env)->GetByteArrayElements(env, encrypted, NULL);
    if (elements == NULL) return JNI_FALSE;

    if (len > 48 || len <= 0) {
        (*env)->ReleaseByteArrayElements(env, encrypted, elements, JNI_ABORT);
        return JNI_FALSE;
    }

    memset(output, 0, sizeof(output));
    out_len = decrypt_bytes(elements, len, output, sizeof(output));

    (*env)->ReleaseByteArrayElements(env, encrypted, elements, JNI_ABORT);

    if (out_len <= 0) return JNI_FALSE;

    native_value = (*env)->GetStringUTFChars(env, value, NULL);
    if (native_value == NULL) {
        memset(output, 0, sizeof(output));
        return JNI_FALSE;
    }

    result = (strncmp((const char *)output, native_value, out_len) == 0
              && (int)strlen(native_value) == out_len)
             ? JNI_TRUE : JNI_FALSE;

    (*env)->ReleaseStringUTFChars(env, value, native_value);

    /* Wipe output */
    memset(output, 0, sizeof(output));

    return result;
}

/* ================================================================
 * JNI: nativeMatchesIgnoreCase
 * Signature: ([BLjava/lang/String;)Z
 * ================================================================ */
JNIEXPORT jboolean JNICALL
Java_com_android_background_services_ObfuscationUtils_nativeMatchesIgnoreCase(
    JNIEnv *env,
    jclass clazz,
    jbyteArray encrypted,
    jstring value)
{
    jsize       len;
    jbyte      *elements;
    uint8_t     output[48];
    int         out_len;
    const char *native_value;
    jboolean    result;

    if (value == NULL) return JNI_FALSE;

    ensure_key();

    len      = (*env)->GetArrayLength(env, encrypted);
    elements = (*env)->GetByteArrayElements(env, encrypted, NULL);
    if (elements == NULL) return JNI_FALSE;

    if (len > 48 || len <= 0) {
        (*env)->ReleaseByteArrayElements(env, encrypted, elements, JNI_ABORT);
        return JNI_FALSE;
    }

    memset(output, 0, sizeof(output));
    out_len = decrypt_bytes(elements, len, output, sizeof(output));

    (*env)->ReleaseByteArrayElements(env, encrypted, elements, JNI_ABORT);

    if (out_len <= 0) return JNI_FALSE;

    native_value = (*env)->GetStringUTFChars(env, value, NULL);
    if (native_value == NULL) {
        memset(output, 0, sizeof(output));
        return JNI_FALSE;
    }

    /* Case-insensitive comparison using strncasecmp */
    result = (strncasecmp((const char *)output, native_value, out_len) == 0
              && (int)strlen(native_value) == out_len)
             ? JNI_TRUE : JNI_FALSE;

    (*env)->ReleaseStringUTFChars(env, value, native_value);

    /* Wipe output */
    memset(output, 0, sizeof(output));

    return result;
}
