# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.osint.unified.**$$serializer { *; }
-keepclassmembers class com.osint.unified.** {
    *** Companion;
}
-keepclasseswithmembers class com.osint.unified.** {
    kotlinx.serialization.KSerializer serializer(...);
}
