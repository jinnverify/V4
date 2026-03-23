# VoxLink ProGuard Rules
# Keep app classes
-keep class com.voxlink.** { *; }

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Keep JSON
-keepclassmembers class * {
    @org.json.* *;
}

# Android basics
-keep class android.** { *; }
-dontwarn android.**

# Shrink aggressively
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-dontpreverify
