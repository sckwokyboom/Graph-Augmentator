package com.graphtipper.slice;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class JacocoExecReport {

    private final Map<String, Set<Integer>> executedLinesByFile;

    private JacocoExecReport(Map<String, Set<Integer>> executedLinesByFile) {
        this.executedLinesByFile = executedLinesByFile;
    }

    /** Path key format: "<package-with-slashes>/<SourceFile.java>", matching JaCoCo's hierarchy. */
    public boolean isExecuted(String packageQualifiedSourcePath, int line) {
        Set<Integer> s = executedLinesByFile.get(packageQualifiedSourcePath);
        return s != null && s.contains(line);
    }

    public static JacocoExecReport fromXml(Path xml) {
        Map<String, Set<Integer>> acc = new HashMap<>();
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        try (InputStream in = Files.newInputStream(xml)) {
            XMLStreamReader r = f.createXMLStreamReader(in);
            try {
                String currentPackage = null;
                String currentSourceFile = null;
                while (r.hasNext()) {
                    int ev = r.next();
                    if (ev == XMLStreamConstants.START_ELEMENT) {
                        String name = r.getLocalName();
                        if ("package".equals(name)) {
                            currentPackage = r.getAttributeValue(null, "name");
                        } else if ("sourcefile".equals(name)) {
                            currentSourceFile = r.getAttributeValue(null, "name");
                        } else if ("line".equals(name) && currentPackage != null && currentSourceFile != null) {
                            // nr is required by JaCoCo XML schema; parseInt throws on malformed input.
                            int nr = Integer.parseInt(r.getAttributeValue(null, "nr"));
                            int ci = parseIntSafe(r.getAttributeValue(null, "ci"));
                            // ci > 0 means at least one instruction executed; cb-only is not produced by well-formed JaCoCo.
                            if (ci > 0) {
                                String key = currentPackage + "/" + currentSourceFile;
                                acc.computeIfAbsent(key, k -> new HashSet<>()).add(nr);
                            }
                        }
                    } else if (ev == XMLStreamConstants.END_ELEMENT) {
                        if ("sourcefile".equals(r.getLocalName())) currentSourceFile = null;
                        if ("package".equals(r.getLocalName())) currentPackage = null;
                    }
                }
                return new JacocoExecReport(acc);
            } finally {
                r.close();
            }
        } catch (Exception e) {
            throw new RuntimeException("failed to parse JaCoCo XML " + xml, e);
        }
    }

    private static int parseIntSafe(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }
}
