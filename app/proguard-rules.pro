# kotlinx.serialization: the plugin generates serializers reflectively looked up by name.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class org.areel.fishball.** {
    *** Companion;
}
-keepclasseswithmembers class org.areel.fishball.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Tool argument classes are deserialized from LLM-produced JSON by name.
-keep class org.areel.fishball.agent.*Tool$Args { *; }

# Ktor / OkHttp
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-keepclassmembers class io.ktor.** { volatile <fields>; }

# Koog pulls in reflection-adjacent machinery for provider clients; widen if release
# builds start failing at runtime where debug builds pass.
-dontwarn ai.koog.**
