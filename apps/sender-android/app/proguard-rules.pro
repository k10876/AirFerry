# JNI native method signatures — R8 must not rename them.
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.airferry.sender.nativelib.** { *; }

-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
