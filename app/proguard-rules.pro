# FoldPhase release rules.
#
# The app has no reflection-based serialization, no JNI and no dynamic class loading, so
# very little needs keeping. The rules below exist for specific, identified reasons.

# Compose and Kotlin metadata are handled by the libraries' own consumer rules.

# Jetpack WindowManager loads its backing extension implementation reflectively from the
# OEM's sidecar/extension library. Without this, WindowAreaController and FoldingFeature
# silently degrade to "unsupported" in release builds only — a failure mode that would be
# invisible in debug and maddening to diagnose.
-keep class androidx.window.** { *; }
-keep interface androidx.window.** { *; }
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**

# The overlay service is referenced by name from a PendingIntent and from the manifest.
-keep class dev.foldphase.overlay.FoldOverlayService { *; }

# Keep the AGSL source string intact. R8 will not touch string constants, but the object
# holding it must survive so the shader can be compiled at runtime.
-keep class dev.foldphase.renderer.FoldShaderSource { *; }

# Strip debug logging from release builds. Sensor callbacks and the frame path log
# nothing, but this guarantees no string concatenation survives on a hot path.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
