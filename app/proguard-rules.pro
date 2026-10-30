# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keep class com.rawcapture.converter.** { *; }
-dontwarn com.rawcapture.converter.**

# Keep MediaCodec related classes
-keep class android.media.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
