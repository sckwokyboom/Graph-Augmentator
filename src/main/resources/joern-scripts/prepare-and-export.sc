// Single-pass CPG preparation + streaming export tailored for graph-tipper.
//
// Why a custom export: joern-export's `--format graphson` uses spray-json's
// PrettyPrinter, which buffers the full JSON into a single character array.
// For projects the size of picocli (with tests included) the array exceeds
// the JVM's 32-bit length limit and the export aborts with OutOfMemoryError.
//
// What we emit is the minimal subset CpgImporter consumes — methods, types,
// calls, literals, plus CALL and REACHING_DEF edges. Each call/literal carries
// a precomputed PARENT_METHOD_ID, and each method carries an IS_TEST flag, so
// the consumer never needs to walk AST. The output is a flat, untyped JSON
// shape (no GraphSON v3 wrappers); CpgImporter understands both formats.
//
// Side effects: removes orphan METHOD_REF nodes (a javasrc2cpg schema bug),
// applies default overlays under the shell's permissive scheduler.

import java.io.{BufferedWriter, FileWriter}

@main def main(cpgPath: String, outFile: String): Unit = {
  importCpg(cpgPath)

  val orphans = cpg.methodRef.filter(mr => !mr.outE.exists(_.label == "REF")).l
  println(s"[prepare-cpg] orphan METHOD_REF nodes (missing REF→METHOD edge): ${orphans.size}")
  orphans.foreach { mr =>
    val file = mr.file.name.headOption.getOrElse("<unknown>")
    val line = mr.lineNumber.map(_.toString).getOrElse("?")
    val col = mr.columnNumber.map(_.toString).getOrElse("?")
    val inMethod = mr.method.fullName.headOption.getOrElse("<unknown>")
    println(s"[prepare-cpg]   id=${mr.id} $file:$line:$col code='${mr.code}' " +
            s"methodFullName=${mr.methodFullName} in=$inMethod")
  }

  if (orphans.nonEmpty) {
    val diff = new flatgraph.DiffGraphBuilder(cpg.graph.schema)
    orphans.foreach(mr => diff.removeNode(mr))
    flatgraph.DiffGraphApplier.applyDiff(cpg.graph, diff)
    println(s"[prepare-cpg] removed ${orphans.size} orphan METHOD_REF nodes; " +
            "REACHING_DEF edges through those references are skipped.")
  }

  run.ossdataflow

  // ---- streaming JSON export ----
  def jstr(s: String): String = {
    if (s == null) return "\"\""
    val sb = new StringBuilder(s.length + 2)
    sb.append('"')
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      c match {
        case '"'  => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case ch if ch < 0x20 || ch == 0x7f =>
          sb.append("\\u%04x".format(ch.toInt))
        case ch => sb.append(ch)
      }
      i += 1
    }
    sb.append('"')
    sb.toString
  }

  val w = new BufferedWriter(new FileWriter(outFile), 1 << 20)
  try {
    var counts = scala.collection.mutable.Map[String, Long](
      "METHOD" -> 0, "TYPE_DECL" -> 0, "CALL" -> 0, "LITERAL" -> 0,
      "CALL_EDGE" -> 0, "REACHING_DEF" -> 0)

    w.write("{\"vertices\":[")
    var firstV = true
    def openVertex(id: Long, label: String): Unit = {
      if (!firstV) w.write(",")
      firstV = false
      w.write("{\"id\":\""); w.write(id.toString); w.write("\",\"label\":\"")
      w.write(label); w.write("\",\"properties\":{")
    }
    def closeVertex(): Unit = w.write("}}")
    def kv(first: Boolean, key: String, value: String): Boolean = {
      if (!first) w.write(",")
      w.write("\""); w.write(key); w.write("\":"); w.write(value)
      false
    }

    val testAnnoNames = Set("Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate")
    cpg.method.foreach { m =>
      val isTest = m.annotation.exists { a =>
        val name = a.name
        val fn = a.fullName
        testAnnoNames.contains(name) ||
          fn.endsWith(".Test") || fn.endsWith(".ParameterizedTest") ||
          fn.endsWith(".RepeatedTest") || fn.endsWith(".TestFactory") ||
          fn.endsWith(".TestTemplate")
      }
      openVertex(m.id, "METHOD")
      var f = true
      f = kv(f, "FULL_NAME", jstr(m.fullName))
      f = kv(f, "SIGNATURE", jstr(m.signature))
      f = kv(f, "FILENAME", jstr(m.filename))
      f = kv(f, "LINE_NUMBER", m.lineNumber.getOrElse(-1).toString)
      f = kv(f, "LINE_NUMBER_END", m.lineNumberEnd.getOrElse(-1).toString)
      f = kv(f, "IS_TEST", if (isTest) "true" else "false")
      closeVertex()
      counts("METHOD") += 1
    }

    cpg.typeDecl.foreach { t =>
      openVertex(t.id, "TYPE_DECL")
      var f = true
      f = kv(f, "FULL_NAME", jstr(t.fullName))
      f = kv(f, "FILENAME", jstr(t.filename))
      closeVertex()
      counts("TYPE_DECL") += 1
    }

    cpg.call.foreach { c =>
      openVertex(c.id, "CALL")
      val parentId = c.method.id
      var f = true
      f = kv(f, "METHOD_FULL_NAME", jstr(c.methodFullName))
      f = kv(f, "LINE_NUMBER", c.lineNumber.getOrElse(-1).toString)
      f = kv(f, "COLUMN_NUMBER", c.columnNumber.getOrElse(-1).toString)
      f = kv(f, "CODE", jstr(c.code))
      f = kv(f, "PARENT_METHOD_ID", "\"" + parentId.toString + "\"")
      closeVertex()
      counts("CALL") += 1
    }

    cpg.literal.foreach { l =>
      openVertex(l.id, "LITERAL")
      val parentId = l.method.id
      var f = true
      f = kv(f, "CODE", jstr(l.code))
      f = kv(f, "LINE_NUMBER", l.lineNumber.getOrElse(-1).toString)
      f = kv(f, "PARENT_METHOD_ID", "\"" + parentId.toString + "\"")
      closeVertex()
      counts("LITERAL") += 1
    }

    w.write("],\"edges\":[")
    var firstE = true
    def edge(label: String, outV: Long, inV: Long): Unit = {
      if (!firstE) w.write(",")
      firstE = false
      w.write("{\"label\":\""); w.write(label)
      w.write("\",\"outV\":\""); w.write(outV.toString)
      w.write("\",\"inV\":\""); w.write(inV.toString); w.write("\"}")
    }

    cpg.call.foreach { c =>
      c.callee.foreach { callee =>
        edge("CALL", c.id, callee.id)
        counts("CALL_EDGE") += 1
      }
    }

    // REACHING_DEF edges out of CALL and LITERAL nodes are what graph-tipper
    // uses for the data-dependence overlay; intermediate identifier-to-identifier
    // edges aren't read on import. Iterating outE on these node kinds keeps the
    // output bounded.
    (cpg.call.iterator ++ cpg.literal.iterator).foreach { node =>
      node.outE.foreach { e =>
        if (e.label == "REACHING_DEF") {
          edge("REACHING_DEF", node.id, e.dst.id)
          counts("REACHING_DEF") += 1
        }
      }
    }

    w.write("]}")
    w.flush()
    println(s"[export-graph] vertices: METHOD=${counts("METHOD")} TYPE_DECL=${counts("TYPE_DECL")} " +
            s"CALL=${counts("CALL")} LITERAL=${counts("LITERAL")}")
    println(s"[export-graph] edges: CALL=${counts("CALL_EDGE")} REACHING_DEF=${counts("REACHING_DEF")}")
    println(s"[export-graph] wrote $outFile")
  } finally {
    w.close()
  }
}
