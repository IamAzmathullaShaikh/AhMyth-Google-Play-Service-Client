package com.android.background.services;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Robolectric-powered tests for ObfuscationUtils.
 *
 * Unlike the plain JUnit tests, these run with Robolectric's shadow
 * Android environment which properly shadows android.util.Log and
 * other Android framework classes — verifying that the log-guarded
 * error paths work without throwing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RobolectricObfuscationUtilsTest {

    // ================================================================
    // decrypt() — basic correctness (same as JUnit tests)
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
    public void decrypt_orderKey_returnsCorrectString() {
        assertEquals("order", ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER));
    }

    // ================================================================
    // decrypt() — error paths (rely on shadow Log)
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
        byte[] garbage = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        assertEquals("", ObfuscationUtils.decrypt(garbage));
    }

    // ================================================================
    // matches()
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
    public void matches_withNullValue_returnsFalse() {
        assertFalse(ObfuscationUtils.matches(ObfuscationUtils.ENC_X0000CA, null));
    }

    // ================================================================
    // matchesIgnoreCase()
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
    public void matchesIgnoreCase_withWrongValue_returnsFalse() {
        assertFalse(ObfuscationUtils.matchesIgnoreCase(
            ObfuscationUtils.ENC_X0000CA, "x0000lm"));
    }

    // ================================================================
    // Multi-block decryption
    // ================================================================

    @Test
    public void decrypt_multiBlock_returnsCorrectString() {
        String rebootDevice = ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000REBOOTDEVICE);
        assertEquals("x0000rebootDevice", rebootDevice);
    }

    // ================================================================
    // All opcodes decrypt without exception
    // ================================================================

    @Test
    public void decrypt_allOpcodeConstants_succeed() {
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
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_PING));
        assertNotNull(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_PONG));
    }
}
