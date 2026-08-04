package com.android.background.services;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Pure-JVM tests for the runtime config extractor (no Android framework
 * involved -- C2Config.extract is deliberately dependency-free).
 */
public class C2ConfigTest {

    @Test
    public void extractUrl() {
        String json = "{\"url\": \"http://192.168.0.108:42474\", \"device_id\": \"x\"}";
        assertEquals("http://192.168.0.108:42474",
                C2Config.extract(json, "url", "fallback"));
    }

    @Test
    public void extractDeviceId() {
        String json = "{\"url\": \"http://x:1\", \"device_id\": \"my-test-phone\"}";
        assertEquals("my-test-phone", C2Config.extract(json, "device_id", "fallback"));
    }

    @Test
    public void deviceIdWithSpacesAndDashes() {
        String json = "{\"device_id\": \"test device-01\"}";
        assertEquals("test device-01", C2Config.extract(json, "device_id", "fb"));
    }

    @Test
    public void missingKeyFallsBack() {
        String json = "{\"url\": \"http://x:1\"}";
        assertEquals("fb", C2Config.extract(json, "device_id", "fb"));
    }

    @Test
    public void nullInputFallsBack() {
        assertEquals("fb", C2Config.extract(null, "url", "fb"));
    }

    @Test
    public void emptyJsonFallsBack() {
        assertEquals("fb", C2Config.extract("", "url", "fb"));
    }

    @Test
    public void malformedJsonFallsBack() {
        assertEquals("fb", C2Config.extract("{url: http://x", "url", "fb"));
    }

    @Test
    public void emptyValueFallsBack() {
        String json = "{\"url\": \"\"}";
        assertEquals("fb", C2Config.extract(json, "url", "fb"));
    }

    @Test
    public void wrongTypeFallsBack() {
        // non-string values (numbers / booleans / objects) are not matched
        String json = "{\"url\": 123, \"device_id\": true}";
        assertNull(C2Config.extract(json, "url", null));
        assertNull(C2Config.extract(json, "device_id", null));
    }
}
