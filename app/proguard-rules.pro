# Vigil Project ProGuard/R8 Rules

# --- ONNX Runtime (CRITICAL: Fixes JNI GetMethodID crash) ---
-keep class ai.onnxruntime.** { *; }
-keep interface ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# --- CameraX & Camera2 (Fixes session teardown crashes) ---
-keep class androidx.camera.** { *; }
-keep interface androidx.camera.** { *; }
-dontwarn androidx.camera.**
-keep class androidx.camera.camera2.internal.** { *; }
-keep class androidx.camera.core.internal.** { *; }

# --- Retrofit & OkHttp ---
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers interface * {
    @retrofit2.http.* <methods>;
}
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# --- Gson (Prevents data mapping errors) ---
-keep class com.google.gson.** { *; }
-keep class com.vigil.android.core.di.model.** { *; }
-keep class com.vigil.android.presentation.feature_attendance.patroling_ui.Prefs { *; }

# --- Hilt / Dagger ---
-keep class dagger.hilt.** { *; }
-keep class com.vigil.android.Hilt_** { *; }
-keep class * extends androidx.lifecycle.ViewModel
-keep class * extends android.app.Service
-keep class * extends android.app.Activity
-keep class * extends android.app.Application

# --- OSMDroid ---
-keep class org.osmdroid.** { *; }

# --- AndroidX Core & Navigation ---
-keep class androidx.navigation.** { *; }
-keep class androidx.core.** { *; }

# --- General Project Logic ---
# Keeping feature packages to avoid issues with reflection-based view binding or other dynamic lookups
-keep class com.vigil.android.presentation.** { *; }
-keep class com.vigil.android.core.** { *; }
-keep class com.vigil.android.utils.** { *; }
