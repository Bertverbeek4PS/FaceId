# R8 runs on debug only to force a whole-program dex (see build.gradle.kts).
# Keep it a pure pass-through: shrink/optimize/obfuscate all off, so no class the
# Meta SDK, TensorFlow Lite or ML Kit reaches by reflection is removed or renamed.
-dontshrink
-dontoptimize
-dontobfuscate

# The coroutine spilling helper the Meta SDK references but per-library dexing drops.
-keep class kotlin.coroutines.jvm.internal.SpillingKt { *; }
-keep class kotlin.coroutines.jvm.internal.** { *; }
