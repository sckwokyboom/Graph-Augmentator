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

    /** Project package prefix (tests + source live here). Set by the agent premain. */
    private static final String PKG = System.getProperty("gtcov.pkg", "picocli.");

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(Recorder::dump, "gtcov-dump"));
    }

    private Recorder() {}

    /** Called (inlined) at the entry of every instrumented method. methodFqn is the
     *  canonical "package.Outer$Nested.method" (no signature). */
    public static void record(String methodFqn) {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        // Stack is most-recent-first: index 0 is Thread.getStackTrace, low indices are
        // the instrumented method + its callees, high indices are the test/runner side.
        // The OUTERMOST project-package frame (highest index = closest to the JUnit runner,
        // found first scanning from the bottom) is the test entry point that drove this
        // call: source frames are always deeper than the test that calls them, and JUnit /
        // gradle frames are not in the project package. No class-name heuristic needed —
        // this captures tests whose class name has no "Test" in it (e.g. picocli.Issue1351).
        for (int i = st.length - 1; i >= 0; i--) {
            if (st[i].getClassName().startsWith(PKG)) {
                MATRIX.add(methodFqn + "\t" + testFqn(st[i]) + "\touter");
                return;
            }
        }
        // No project-package frame on the stack (e.g. static-init / non-test call); skip.
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
