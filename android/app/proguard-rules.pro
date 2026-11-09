# ONNX Runtime calls into these classes from native code.
-keep class ai.onnxruntime.** { *; }

# OpenCV's Java layer is reached through JNI.
-keep class org.opencv.** { *; }

# supabase-kt serialises through generated serializers.
-keep,includedescriptorclasses class com.misamabbas.helmetguard.**$$serializer { *; }
-keepclassmembers class com.misamabbas.helmetguard.** {
    *** Companion;
}
-keepclasseswithmembers class com.misamabbas.helmetguard.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
