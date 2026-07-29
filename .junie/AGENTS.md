# Technical Guidelines & Developer Documentation

## Build & Configuration Instructions

### Prerequisites
- **JDK Version**: Java 21 (build compatible with `sourceCompatibility` / `targetCompatibility` Java 1.8).
- **Gradle Version**: 9.5.0 (managed via Gradle Wrapper `./gradlew`).
- **Android Gradle Plugin (AGP)**: 9.3.1.
- **SDK Versions**: `compileSdk` 34, `targetSdk` 34, `minSdk` 21.

### Configuration
- **Socket Server URL**: Configured via `buildConfigField` in `app/build.gradle`:
  ```groovy
  buildConfigField "String", "SOCKET_URL", "\"http://<SERVER_IP>:<PORT>\""
  ```
  Update this field prior to building if targeting a different command-and-control socket endpoint.
- **Gradle Options**: Set in `gradle.properties`:
  - `android.useAndroidX=true`
  - `android.enableJetifier=true`
  - `org.gradle.configuration-cache=true`

### Build Commands
- **Assemble Debug APK**:
  ```powershell
  .\gradlew assembleDebug
  ```
- **Assemble Release APK**:
  ```powershell
  .\gradlew assembleRelease
  ```
- **Clean Build Directory**:
  ```powershell
  .\gradlew clean
  ```

---

## Testing Information

### Test Execution

#### Unit Tests (Host Machine JVM)
Run local unit tests located in `app/src/test/java`:
```powershell
.\gradlew testDebugUnitTest
```
Or run all unit test tasks:
```powershell
.\gradlew test
```

#### Instrumented Tests (Android Emulator / Device)
Run instrumented tests located in `app/src/androidTest/java` (requires an active emulator or connected device):
```powershell
.\gradlew connectedAndroidTest
```

### Adding New Unit Tests
- Place unit test classes under `app/src/test/java/com/android/background/services/`.
- Use JUnit 4 annotations (`@Test`, `@Before`, `@After`, `@RunWith`).
- Ensure helpers or utilities tested do not depend directly on Android hardware APIs (like `Camera` or `AudioRecord`) without proper abstractions or robolectric/mocking.

#### Example Unit Test
Below is an example unit test verifying utility logic (e.g., `FileManager.fileSizeFormatter`):

```java
package com.android.background.services;

import com.android.background.services.helpers.FileManager;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class FileManagerTest {

    @Test
    public void testFileSizeFormatter() {
        assertEquals("?", FileManager.fileSizeFormatter(0));
        assertEquals("1 KB", FileManager.fileSizeFormatter(1024));
        assertEquals("1 MB", FileManager.fileSizeFormatter(1024 * 1024));
    }
}
```

---

## Architecture & Development Information

### Project Architecture & Key Components
- **`MainActivity`**: UI entry point; handles device admin activation (`DevicePolicyManager`) and runtime permissions before launching background services.
- **`MainService`**: Foreground/background service (`Service`) responsible for maintaining the application lifecycle and keeping socket connection active.
- **`IOSocket`**: Singleton wrapper around `io.socket.client.Socket` configured with client device metadata (Model, Manufacturer, Android Release, Android ID) sent via query parameters during handshake.
- **`ConnectionManager`**: Central command dispatcher receiving Socket.IO events (`order`, `ping`). Dispatches command strings (e.g., `x0000ca`, `x0000fm`, `x0000sm`, `x0000lm`, `x0000mc`) to respective helper modules.
- **Helpers (`com.android.background.services.helpers.*`)**:
  - `CameraManager`: Captures images via camera hardware.
  - `FileManager`: Browses directory structure and handles file uploads/downloads.
  - `SMSManager`: Retrieves SMS inbox logs and sends SMS messages.
  - `CallsManager` & `ContactsManager`: Reads call logs and contact lists.
  - `LocManager`: Handles GPS/Location queries.
  - `MicManager`: Records ambient audio using `MediaRecorder`.
  - `ScreenManager`: Screen capture handling via `MediaProjection`.
  - `AppsListManager`: Enumerates installed applications on device.
- **Receivers (`com.android.background.services.receivers.*`)**:
  - `AdminReceiver`: Android `DeviceAdminReceiver` extension for device management features (lock/wipe/reboot).
  - `MyReceiver`: BroadcastReceiver listening for boot completion (`ACTION_BOOT_COMPLETED`) to restart background service automatically.

### Code Style & Development Guidelines
- **Language**: Java 8 compatible.
- **Logging**: Use standard `android.util.Log` (`Log.d`, `Log.e`) for runtime debugging.
- **Error Handling**: Wrap socket handler callbacks in try-catch blocks to prevent unhandled background exceptions from crashing `MainService`.
- **JSON Protocol**: Payloads exchanged with socket server are formatted using `org.json.JSONObject` and `org.json.JSONArray`.
