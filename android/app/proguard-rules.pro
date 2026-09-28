# kotlinx.serialization: keep generated serializers for DTOs.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.strategyforge.android.core.** {
    *** Companion;
}
-keepclasseswithmembers class app.strategyforge.android.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.strategyforge.android.core.**$$serializer { *; }
# OkHttp platform detection
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
