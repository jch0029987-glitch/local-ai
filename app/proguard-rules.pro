# Keep native C++ methods and JNI bindings for llama.cpp / inference wrapper
-keepclassmembers class * {
    native <methods>;
}

# Keep the local AI wrapper package classes from being obfuscated or removed
-keep class org.codeshipping.** { *; }

# Keep kotlinx.coroutines serialization/state flows intact
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-retainclassmembers class kotlinx.coroutines.** { *; }
