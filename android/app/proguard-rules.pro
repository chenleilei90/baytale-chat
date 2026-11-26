# BayChat Android Release ProGuard / R8 混淆保护规则

# 保留 Kotlinx Serialization 序列化器与协议模型
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.baytale.baychat.core.model.**$$serializer { *; }
-keepclassmembers class com.baytale.baychat.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.baytale.baychat.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 保留原生 WebRTC JNI 回调类
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# 保留 OkHttp 与 ZXing 核心类
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }

# 保留极光 JPush / JCore 系统级推送组件与反射入口
-dontoptimize
-dontpreverify
-dontwarn cn.jpush.**
-dontwarn cn.jiguang.**
-keep class cn.jpush.** { *; }
-keep class cn.jiguang.** { *; }
-keep class com.baytale.baychat.core.push.** { *; }
