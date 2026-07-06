package gtcov;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Records a small, diverse sample of runtime (args -> result) per captured method,
 *  attributed to the driving test (same outermost-project-frame rule as Recorder).
 *  Dump line: "<methodFqn>\t<testFqn|->\t<arg0> | <arg1> | ...\t=> <result>"  (result =
 *  value or "throws Type: msg"). Bootstrap-resident; the inlined ValueAdvice calls record(). */
public final class ValueRecorder {

    private static final int CAP = Integer.getInteger("gtcov.vcap", 6);       // distinct non-throwing lines per (method, test)
    private static final int EXC_CAP = Integer.getInteger("gtcov.vexc", 2);   // reserved throwing slots per (method, test)
    private static final int MAXLEN = 100;  // per-value truncation
    private static final String THROW_MARK = "\t=> throws ";
    private static final String PKG = System.getProperty("gtcov.pkg", "picocli.");
    private static final Map<String, Set<String>> SAMPLES = new ConcurrentHashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(ValueRecorder::dump, "gtcov-values"));
    }

    private ValueRecorder() {}

    public static void record(String methodFqn, Object[] args, Object ret, Throwable thrown) {
        String key = methodFqn + "\t" + drivingTest();
        Set<String> set = SAMPLES.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
        // Cap non-throwing and throwing examples independently so the exception contract
        // (e.g. invalid-row → IllegalArgumentException) is never crowded out by returns.
        long throwsSoFar = 0;
        for (String s : set) {
            if (s.contains(THROW_MARK)) throwsSoFar++;
        }
        boolean isThrow = thrown != null;
        if (isThrow ? throwsSoFar >= EXC_CAP : (set.size() - throwsSoFar) >= CAP) {
            return;
        }
        StringBuilder a = new StringBuilder();
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (i > 0) a.append(" | ");
                a.append(repr(args[i]));
            }
        }
        String result = (thrown != null)
                ? "throws " + thrown.getClass().getSimpleName()
                  + (thrown.getMessage() != null ? ": " + clip(thrown.getMessage()) : "")
                : repr(ret);
        set.add(key + "\t" + a + "\t=> " + result);
    }

    /** Same attribution rule as Recorder.record: the OUTERMOST project-package frame
     *  is the driving @Test method. "-" when no project frame is on the stack. */
    private static String drivingTest() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        for (int i = st.length - 1; i >= 0; i--) {
            if (st[i].getClassName().startsWith(PKG)) {
                return st[i].getClassName() + "." + st[i].getMethodName();
            }
        }
        return "-";
    }

    private static String repr(Object o) {
        if (o == null) return "null";
        String s;
        try {
            s = String.valueOf(o);
        } catch (Throwable t) {
            s = "<toString threw " + t.getClass().getSimpleName() + ">";
        }
        // Default Object.toString → "pkg.Class@1a2b3c": fall back to a field dump.
        if (s.matches(".*@[0-9a-fA-F]+$")) {
            String fields = fieldDump(o);
            if (fields != null) return fields;
        }
        return clip(s);
    }

    private static String fieldDump(Object o) {
        try {
            StringBuilder b = new StringBuilder(o.getClass().getSimpleName()).append("{");
            Field[] fs = o.getClass().getDeclaredFields();
            int n = 0;
            for (Field f : fs) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (n++ > 0) b.append(", ");
                f.setAccessible(true);
                b.append(f.getName()).append("=").append(clip(String.valueOf(f.get(o))));
                if (n >= 6) { b.append(", …"); break; }
            }
            return b.append("}").toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String clip(String s) {
        s = s.replace("\n", "\\n").replace("\t", " ");
        return s.length() > MAXLEN ? s.substring(0, MAXLEN) + "…" : s;
    }

    static void dump() {
        String out = System.getProperty("gtcov.out", "./gtcov-out");
        long pid = ProcessHandle.current().pid();
        File f = new File(out, "values." + pid + ".tsv");
        try (PrintWriter w = new PrintWriter(f, "UTF-8")) {
            for (Set<String> set : SAMPLES.values()) {
                for (String line : set) w.println(line);
            }
        } catch (IOException ignored) { }
    }
}
