package com.graphtipper.slice;

public final class SnippetCoveragePruner {

    private final JacocoExecReport report;
    private final String targetPackageQualifiedFile;
    private final int targetStartLine;
    private final int targetEndLine;

    private SnippetCoveragePruner(JacocoExecReport report, String targetPackageQualifiedFile,
                                   int targetStartLine, int targetEndLine) {
        this.report = report;
        this.targetPackageQualifiedFile = targetPackageQualifiedFile;
        this.targetStartLine = targetStartLine;
        this.targetEndLine = targetEndLine;
    }

    public static SnippetCoveragePruner of(JacocoExecReport report,
                                            String targetPackageQualifiedFile,
                                            int targetStartLine, int targetEndLine) {
        return new SnippetCoveragePruner(report, targetPackageQualifiedFile,
                targetStartLine, targetEndLine);
    }

    public boolean isExecuted(String packageQualifiedSourcePath, int line) {
        if (targetPackageQualifiedFile.equals(packageQualifiedSourcePath)
                && line >= targetStartLine && line <= targetEndLine) {
            return false;
        }
        return report.isExecuted(packageQualifiedSourcePath, line);
    }
}
