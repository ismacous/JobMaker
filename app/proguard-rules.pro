# Room
-keep class com.jobmaker.data.db.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.jobmaker.**$$serializer { *; }
-keepclassmembers class com.jobmaker.** { *** Companion; }
-keepclasseswithmembers class com.jobmaker.** { kotlinx.serialization.KSerializer serializer(...); }

# JNI : les methodes natives sont resolues par nom, ne pas les renommer.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keep class com.jobmaker.llm.LlamaBridge { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
