# Valhalla's Kotlin wrapper reads and writes its config and responses with Moshi through
# Kotlin reflection, so its model classes and Kotlin metadata must survive shrinking.
-keep class com.valhalla.** { *; }
-keep class com.osrm.** { *; }
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn com.osrm.**
