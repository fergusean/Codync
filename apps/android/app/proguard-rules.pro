# Bouncy Castle is used through its lightweight APIs; no provider reflection.
-dontwarn javax.naming.**
-dontwarn java.lang.invoke.**

# Vosk binds these native methods by their names through JNA.
-keep class org.vosk.LibVosk { *; }
-keep class com.sun.jna.** { *; }
# JNA creates a NativeMapped pointer argument through its public constructor.
-keepclassmembers class * extends com.sun.jna.PointerType { public <init>(); }
-dontwarn java.awt.**

# Glance persists widget class identity to distinguish receivers across upgrades.
# Keep each class/constructor distinct while optimizing its implementation.
-keep,allowoptimization class com.codync.android.** extends androidx.glance.appwidget.GlanceAppWidget {
    public <init>();
}
