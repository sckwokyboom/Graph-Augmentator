package gtcov;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/** Inlined at the exit of captured methods. Grabs all arguments, the return value, and any
 *  thrown exception, and forwards them to gtcov.ValueRecorder (bootstrap). onThrowable so the
 *  advice also runs when the method throws. */
public final class ValueAdvice {
    private ValueAdvice() {}

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    static void exit(@Advice.Origin("#t") String declaringType,
                     @Advice.Origin("#m") String methodName,
                     @Advice.AllArguments Object[] args,
                     @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object ret,
                     @Advice.Thrown Throwable thrown) {
        gtcov.ValueRecorder.record(declaringType + "." + methodName, args, ret, thrown);
    }
}
