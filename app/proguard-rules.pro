# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.xiaosiqi.quizbank.**$$serializer { *; }
-keepclassmembers class com.xiaosiqi.quizbank.** {
    *** Companion;
}
-keepclasseswithmembers class com.xiaosiqi.quizbank.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# 手写 XML 解析用到的 SAX/DOM
-dontwarn javax.xml.**
