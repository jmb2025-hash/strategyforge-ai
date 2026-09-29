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

# ------------------------------------------------------------------ on-device engine (D-027)
# The engine, its JSON libraries and the Anthropic SDK use reflection (Jackson data binding,
# Kotlin metadata, JSON Schema keyword loading), so they are kept whole; R8 still shrinks the rest.
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault, MethodParameters, Exceptions
-keep class app.strategyforge.engine.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class com.networknt.schema.** { *; }
-keep class com.ethlo.time.** { *; }
-keep class com.anthropic.** { *; }
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-keepclassmembers class kotlin.Metadata { public <methods>; }

# JVM-only classes referenced by these libraries but never used on Android.
-dontwarn java.beans.**
-dontwarn java.lang.management.**
-dontwarn javax.annotation.**
-dontwarn javax.naming.**
-dontwarn javax.lang.model.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.slf4j.**
-dontwarn org.joda.time.**
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.reflect.jvm.internal.**
-dontwarn com.google.re2j.**
-dontwarn org.graalvm.**
-dontwarn reactor.blockhound.**
