package kotlin.coroutines.jvm.internal;

/**
 * Stand-in for the compiler-intrinsic stdlib helper `SpillingKt`, which is never
 * shipped as a real class: the dexer normally rewrites calls to it away. The Meta
 * Wearables SDK was compiled with a Kotlin whose spilling calls the dexer does not
 * rewrite, so those 66 call sites need a real class to resolve to at runtime.
 * Identity return matches the stdlib default (the debug agent may override it).
 */
public final class SpillingKt {

    private SpillingKt() {
    }

    public static Object nullOutSpilledVariable(Object value) {
        return value;
    }
}
