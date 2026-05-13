package com.graphtipper.cpg;

import java.nio.file.Path;

public interface JoernInvoker {
    void runJavasrc2Cpg(Path projectRoot, Path cpgFile) throws Exception;
    void runJoernExport(Path cpgFile, Path outDir) throws Exception;
}
