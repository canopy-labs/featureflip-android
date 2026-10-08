# Keep rules for apps that shrink with R8 or ProGuard. Both read this file from the
# jar automatically, so an app needs no rules of its own for this SDK.
#
# Without them a minified app gets every flag's default, sends empty event batches,
# and never sees background or foreground (verified on an emulator, #3545).

# Jackson maps the SDK's models by reflection, through their names, annotations and
# generic signatures.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class dev.featureflip.android.** { *; }
-keep class kotlin.Metadata { *; }

# jackson-module-kotlin's readValue<T>() reads T from an anonymous TypeReference
# subclass's generic signature, which R8 full mode drops unless the hierarchy is kept.
-keep,allowobfuscation,allowshrinking class com.fasterxml.jackson.core.type.TypeReference
-keep,allowobfuscation,allowshrinking class * extends com.fasterxml.jackson.core.type.TypeReference

# LifecycleObserver.kt reaches these by name, so the SDK works without a compile-time
# androidx dependency. Renamed or removed, registration fails and the app never sees
# background or foreground.
-keep class androidx.lifecycle.ProcessLifecycleOwner {
    public static *** get();
    public *** getLifecycle();
}
-keepclassmembers class * extends androidx.lifecycle.Lifecycle {
    public *** addObserver(...);
    public *** removeObserver(...);
}
-keep interface androidx.lifecycle.LifecycleObserver
-keep interface androidx.lifecycle.DefaultLifecycleObserver { *; }
