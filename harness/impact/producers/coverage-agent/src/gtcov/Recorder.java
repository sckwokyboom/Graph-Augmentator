package gtcov;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bootstrap-resident recorder for the in-JVM coverage agent. The ByteBuddy advice is
 * inlined into target methods and calls {@link #record(String)} on entry. We attribute
 * each call to the test that drove it by walking the current thread's stack for the
 * outermost picocli.*Test frame (the @Test method); inner *Test frames are helpers.
 *
 * Call-time work is in-memory only (no IO, no permissions) so it survives the
 * SecurityManager picocli enables on Java 18-23; the only IO is the shutdown dump.
 *
 * Matrix line format (TSV, deduped): "<methodFqn>\t<testFqn>\t<kind>" where kind is
 * "outer" (the driving @Test method) or "inner" (a *Test helper on the stack).
 */
public final class Recorder {

    /** Deduped matrix rows: methodFqn \t testFqn \t kind. */
    private static final Set<String> MATRIX = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(Recorder::dump, "gtcov-dump"));
    }

    private Recorder() {}

    private static boolean isTestClass(String c) {
        return c.startsWith("picocli.") && c.contains("Test");
    }

    /** Called (inlined) at the entry of every instrumented method. methodFqn is the
     *  canonical "package.Outer$Nested.method" (no signature). */
    public static void record(String methodFqn) {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        // Stack is most-recent-first: index 0 is Thread.getStackTrace, low indices are
        // the instrumented method + callees, high indices are the test/runner side.
        // The outermost *Test frame (highest index, found first scanning from the bottom)
        // is the @Test method that drove this call.
        int outerIdx = -1;
        for (int i = st.length - 1; i >= 0; i--) {
            if (isTestClass(st[i].getClassName())) { outerIdx = i; break; }
        }
        if (outerIdx < 0) {
            return; // no test frame on stack (e.g. static-init / non-test call); skip
        }
        MATRIX.add(methodFqn + "\t" + testFqn(st[outerIdx]) + "\touter");
        for (int i = 0; i < outerIdx; i++) {
            if (isTestClass(st[i].getClassName())) {
                MATRIX.add(methodFqn + "\t" + testFqn(st[i]) + "\tinner");
            }
        }
    }

    private static String testFqn(StackTraceElement e) {
        // Bytecode method name has no JUnit [param] suffix → already canonical.
        return e.getClassName() + "." + e.getMethodName();
    }

    static void dump() {
        String out = System.getProperty("gtcov.out", "./gtcov-out");
        long pid = ProcessHandle.current().pid();
        File f = new File(out, "matrix." + pid + ".tsv");
        try (PrintWriter w = new PrintWriter(f, "UTF-8")) {
            for (String row : MATRIX) {
                w.println(row);
            }
        } catch (IOException ignored) {
            // shutdown best-effort; if a leftover restrictive SM denies write we lose this dump
        }
    }
}
