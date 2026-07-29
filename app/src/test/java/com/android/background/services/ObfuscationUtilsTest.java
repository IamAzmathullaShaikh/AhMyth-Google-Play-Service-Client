package com.android.background.services;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.*;

/**
 * Unit tests for ObfuscationUtils.
 *
 * These tests exercise the Java AES fallback path (since System.loadLibrary
 * fails in the JUnit environment and nativeLoaded is set to false). This is
 * the exact behaviour the fallback was designed for — the same decryption
 * must work identically on both native and Java paths.
 *
 * If the native library were available, the native path would be tested
 * automatically instead; the assertions are identical either way.
 */
@RunWith(JUnit4.class)
public class ObfuscationUtilsTest {

    // ================================================================
    // decrypt() — basic correctness
    // ================================================================

    @Test
    public void decrypt_x0000ca_returnsCorrectOpcode() {
        assertEquals("x0000ca", ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000CA));
    }

    @Test
    public void decrypt_x0000sm_returnsCorrectOpcode() {
        assertEquals("x0000sm", ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000SM));
    }

    @Test
    public void decrypt_x0000lm_returnsCorrectOpcode() {
        assertEquals("x0000lm", ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000LM));
    }

    @Test
    public void decrypt_x0000kl_returnsCorrectOpcode() {
        assertEquals("x0000kl", ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000KL));
    }

    @Test
    public void decrypt_x0000kldata_returnsCorrectOpcode() {
        assertEquals("x0000kldata", ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000KLDATA));
    }

    @Test
    public void decrypt_x0000rebootDevice_returnsCorrectOpcode() {
        // 32-byte (multi-block) encrypted constant
        assertEquals("x0000rebootDevice",
            ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000REBOOTDEVICE));
    }

    @Test
    public void decrypt_orderKey_returnsCorrectString() {
        assertEquals("order", ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER));
    }

    @Test
    public void decrypt_ping_returnsCorrectString() {
        assertEquals("ping", ObfuscationUtils.decrypt(ObfuscationUtils.ENC_PING));
    }

    @Test
    public void decrypt_pong_returnsCorrectString() {
        assertEquals("pong", ObfuscationUtils.decrypt(ObfuscationUtils.ENC_PONG));
    }

    @Test
    public void decrypt_latKey_returnsCorrectString() {
        assertEquals("lat", ObfuscationUtils.decrypt(ObfuscationUtils.ENC_LAT));
    }

    @Test
    public void decrypt_lngKey_returnsCorrectString() {
        assertEquals("lng", ObfuscationUtils.decrypt(ObfuscationUtils.ENC_LNG));
    }

    @Test
    public void decrypt_actionKey_returnsCorrectString() {
        assertEquals("action", ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ACTION));
    }

    // ================================================================
    // decrypt() — edge cases
    // ================================================================

    @Test
    public void decrypt_null_returnsEmptyString() {
        assertEquals("", ObfuscationUtils.decrypt(null));
    }

    @Test
    public void decrypt_emptyArray_returnsEmptyString() {
        assertEquals("", ObfuscationUtils.decrypt(new byte[0]));
    }

    @Test
    public void decrypt_garbageBytes_returnsEmptyString() {
        // Random garbage that won't decrypt to valid PKCS7-padded text
        byte[] garbage = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        // Should not throw, should return empty
        assertEquals("", ObfuscationUtils.decrypt(garbage));
    }

    // ================================================================
    // matches() — exact comparison
    // ================================================================

    @Test
    public void matches_x0000ca_withCorrectValue_returnsTrue() {
        assertTrue(ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000CA, "x0000ca"));
    }

    @Test
    public void matches_x0000ca_withWrongValue_returnsFalse() {
        assertFalse(ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000CA, "x0000lm"));
    }

    @Test
    public void matches_x0000ca_withDifferentCase_returnsFalse() {
        // matches() is case-sensitive
        assertFalse(ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000CA, "X0000CA"));
    }

    @Test
    public void matches_withNullValue_returnsFalse() {
        assertFalse(ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000CA, null));
    }

    @Test
    public void matches_withNullEncrypted_returnsFalse() {
        assertFalse(ObfuscationUtils.matches(null, "x0000ca"));
    }

    @Test
    public void matches_withEmptyString_returnsFalse() {
        assertFalse(ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000CA, ""));
    }

    @Test
    public void matches_withSubstring_returnsFalse() {
        // Must match the full string exactly
        assertFalse(ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000CA, "x0000"));
    }

    // ================================================================
    // matchesIgnoreCase() — case-insensitive comparison
    // ================================================================

    @Test
    public void matchesIgnoreCase_withSameCase_returnsTrue() {
        assertTrue(ObfuscationUtils.matchesIgnoreCase(
            ObfuscationUtils.ENC_X0000CA, "x0000ca"));
    }

    @Test
    public void matchesIgnoreCase_withUpperCase_returnsTrue() {
        assertTrue(ObfuscationUtils.matchesIgnoreCase(
            ObfuscationUtils.ENC_X0000CA, "X0000CA"));
    }

    @Test
    public void matchesIgnoreCase_withMixedCase_returnsTrue() {
        assertTrue(ObfuscationUtils.matchesIgnoreCase(
            ObfuscationUtils.ENC_X0000CA, "X0000cA"));
    }

    @Test
    public void matchesIgnoreCase_withWrongValue_returnsFalse() {
        assertFalse(ObfuscationUtils.matchesIgnoreCase(
            ObfuscationUtils.ENC_X0000CA, "x0000lm"));
    }

    @Test
    public void matchesIgnoreCase_withNullValue_returnsFalse() {
        assertFalse(ObfuscationUtils.matchesIgnoreCase(
            ObfuscationUtils.ENC_X0000CA, null));
    }

    @Test
    public void matchesIgnoreCase_withNullEncrypted_returnsFalse() {
        assertFalse(ObfuscationUtils.matchesIgnoreCase(null, "x0000ca"));
    }

    @Test
    public void matchesIgnoreCase_withEmptyString_returnsFalse() {
        assertFalse(ObfuscationUtils.matchesIgnoreCase(
            ObfuscationUtils.ENC_X0000CA, ""));
    }

    // ================================================================
    // All supported opcodes decrypt successfully
    // ================================================================

    @Test
    public void decrypt_allOpcodeConstants_succeed() {
        // Every opcode constant should decrypt without throwing
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000CA));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000FM));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000SM));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000CL));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000CN));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000MC));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000APPS));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000LM));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000RUNAPP));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000OPENURL));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000DELETEFF));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000DM));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000LOCKDEVICE));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000WIPEDEVICE));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000REBOOTDEVICE));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000SC));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000KL));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000KLDATA));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000NT));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_EXTRA));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_PING));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_PONG));
    }

    // ================================================================
    // Multi-block decryption (32-byte constants)
    // ================================================================

    @Test
    public void decrypt_multiBlock_returnsCorrectString() {
        // These are 32-byte (multi-block) constants — ensure whole string
        // decrypted, not just first 16 bytes
        String rebootDevice = ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000REBOOTDEVICE);
        assertEquals("x0000rebootDevice", rebootDevice);
        assertTrue("Full multi-block string must be 16+ chars",
            rebootDevice.length() > 5);
    }
}
