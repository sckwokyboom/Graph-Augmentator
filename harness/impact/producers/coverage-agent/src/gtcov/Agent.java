package gtcov;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * Premain for the in-JVM coverage agent.
 *
 * Agent args (comma-separated key=value): out=<dir>, includes=<prefix;prefix;...>,
 * boot=<path to gtcov-boot.jar> (optional; defaults to gtcov-boot.jar beside this jar).
 */
public final class Agent {
    private Agent() {}

    public static void premain(String args, Instrumentation inst) throws Exception {
        Map<String, String> cfg = parseArgs(args);
        String out = cfg.getOrDefault("out", "./gtcov-out");
        new File(out).mkdirs();
        System.setProperty("gtcov.out", out);
        // Project package prefix (tests + source). The Recorder picks the outermost frame
        // in this package as the driving test. Defaults to picocli for the validation.
        System.setProperty("gtcov.pkg", cfg.getOrDefault("pkg", "picocli."));

        // Make gtcov.Recorder visible to ALL classloaders (the inlined advice calls it).
        File self = new File(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String bootArg = cfg.get("boot");
        File bootJar = (bootArg != null) ? new File(bootArg)
                                         : new File(self.getParentFile(), "gtcov-boot.jar");
        inst.appendToBootstrapClassLoaderSearch(new JarFile(bootJar));

        String includes = cfg.getOrDefault("includes", "picocli.CommandLine$Help$TextTable");
        ElementMatcher.Junction<TypeDescription> typeMatcher = none();
        for (String inc : includes.split(";")) {
            if (!inc.isEmpty()) {
                typeMatcher = typeMatcher.or(nameStartsWith(inc));
            }
        }
        final ElementMatcher.Junction<TypeDescription> tm = typeMatcher;

        new AgentBuilder.Default()
            .ignore(nameStartsWith("gtcov.").or(nameStartsWith("net.bytebuddy.")))
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .type(tm)
            .transform((builder, type, cl, module, pd) ->
                builder.visit(Advice.to(CovAdvice.class).on(
                    isMethod()
                        .and(not(isAbstract()))
                        .and(not(isNative()))
                        .and(not(isBridge()))
                        .and(not(isSynthetic())))))
            .installOn(inst);

        String capture = cfg.get("capture");
        if (capture != null && !capture.isEmpty()) {
            // Map "pkg.Class.method" specs → class-name matcher + method-name set.
            java.util.Set<String> classNames = new java.util.HashSet<>();
            java.util.Set<String> methodNames = new java.util.HashSet<>();
            for (String spec : capture.split(";")) {
                int dot = spec.lastIndexOf('.');
                if (dot > 0) {
                    classNames.add(spec.substring(0, dot));
                    methodNames.add(spec.substring(dot + 1));
                }
            }
            ElementMatcher.Junction<TypeDescription> capType = none();
            for (String cn : classNames) capType = capType.or(named(cn));
            final ElementMatcher.Junction<TypeDescription> ct = capType;
            final java.util.Set<String> mNames = methodNames;
            new AgentBuilder.Default()
                .ignore(nameStartsWith("gtcov.").or(nameStartsWith("net.bytebuddy.")))
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .type(ct)
                .transform((builder, type, cl, module, pd) ->
                    builder.visit(Advice.to(ValueAdvice.class).on(
                        isMethod().and(namedOneOf(mNames.toArray(new String[0]))))))
                .installOn(inst);
            System.err.println("[gtcov] value capture on: " + capture);
        }

        System.err.println("[gtcov] agent installed: out=" + out + " includes=" + includes);
    }

    private static Map<String, String> parseArgs(String args) {
        Map<String, String> m = new HashMap<>();
        if (args == null || args.isEmpty()) {
            return m;
        }
        for (String kv : args.split(",")) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                m.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        return m;
    }
}
