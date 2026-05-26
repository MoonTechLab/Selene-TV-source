# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ---- kotlinx.serialization ----
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class org.moontechlab.selenetv.model.**$$serializer { *; }
-keepclassmembers class org.moontechlab.selenetv.model.** {
    *** Companion;
}
-keepclasseswithmembers class org.moontechlab.selenetv.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- OkHttp ----
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Ktor ----
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class io.netty.** { *; }
-dontwarn io.netty.**
# Ktor-Netty 传递依赖的可选日志/调试集成，运行时不需要（仅被反射引用）
-dontwarn org.slf4j.**
-dontwarn reactor.blockhound.**

# ---- Coil ----
-dontwarn coil.**

# ---- Coroutines ----
-dontwarn kotlinx.coroutines.**

# libmpv JNI 绑定：native 方法所在类不可裁剪/混淆
-keep class dev.jdtech.mpv.** { *; }
