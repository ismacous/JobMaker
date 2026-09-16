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

# Les enums sont persistes par leur nom dans DataStore (mode du moteur,
# fournisseur, langue de sortie, role d'agent). Si R8 renomme leurs constantes,
# Enum.name() change et les reglages enregistres deviennent illisibles apres
# une mise a jour. On garde donc les enums du projet intacts.
-keepclassmembers enum com.jobmaker.** { *; }
