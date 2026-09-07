# R8 runs on debug only to force a whole-program dex (see build.gradle.kts).
# Keep it a pure pass-through: shrink/optimize/obfuscate all off, so no class the
# Meta SDK, TensorFlow Lite or ML Kit reaches by reflection is removed or renamed.
-dontshrink
-dontoptimize
-dontobfuscate

# The coroutine spilling helper the Meta SDK references but per-library dexing drops.
-keep class kotlin.coroutines.jvm.internal.SpillingKt { *; }
-keep class kotlin.coroutines.jvm.internal.** { *; }

# The Meta/Facebook SDK references optional classes it does not ship (e.g.
# com.facebook.common.build.BuildConstants). They sit on cold paths; ignore the
# missing-reference warnings so R8 does not fail the build over them.
-dontwarn com.facebook.**
-dontwarn com.meta.**
