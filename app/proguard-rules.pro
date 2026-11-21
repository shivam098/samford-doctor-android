# kotlinx.serialization: keep generated serializers for @Serializable classes.
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.samford.doctor.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.samford.doctor.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.samford.doctor.**$$serializer { *; }

# WorkManager instantiates workers reflectively by class name.
-keep class com.samford.doctor.sync.SyncWorker { <init>(...); }
-keep class com.samford.doctor.sync.StatusWorker { <init>(...); }

# Ink loads native code through JNI.
-keep class androidx.ink.** { native <methods>; }

# OkHttp optional platform integrations.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
