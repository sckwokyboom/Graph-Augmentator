package gtcov;

import net.bytebuddy.asm.Advice;

/**
 * Inline advice template. ByteBuddy splices the body into the head of each instrumented
 * method. "#t" resolves to the declaring type's binary name (e.g.
 * "picocli.CommandLine$Help$TextTable") and "#m" to the method name — joined this is the
 * canonical method FQN (no signature). The inlined code calls gtcov.Recorder (bootstrap).
 */
public final class CovAdvice {
    private CovAdvice() {}

    @Advice.OnMethodEnter
    static void enter(@Advice.Origin("#t") String declaringType,
                      @Advice.Origin("#m") String methodName) {
        gtcov.Recorder.record(declaringType + "." + methodName);
    }
}
