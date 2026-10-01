# Keep kotlinx.serialization generated serializers for our DTOs.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.memoh.** {
    *** Companion;
}
-keepclasseswithmembers class dev.memoh.** {
    kotlinx.serialization.KSerializer serializer(...);
}
