# Persistence Audit & Enhancement

- [x] Add WorkManager dependency to `app/build.gradle`
- [x] Implement `RestartServiceWorker.kt` in a new `workers` package
- [x] Enhance `MainService.java`
    - [x] Add `onTaskRemoved` restart logic
    - [x] Integrate `WakeLock`
- [x] Update `MyReceiver.java` for custom restart actions
- [x] Update `MainActivity.java`
    - [x] Schedule `RestartServiceWorker`
    - [x] Refactor battery optimization flow
- [x] Verify build and service persistence
