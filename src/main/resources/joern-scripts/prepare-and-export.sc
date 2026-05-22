// Single-pass CPG preparation + streaming export tailored for graph-tipper.
//
// Why a custom export: joern-export's `--format graphson` uses spray-json's
// PrettyPrinter, which buffers the full JSON into a single character array.
// For projects the size of picocli (with tests included) the array exceeds
// the JVM's 32-bit length limit and the export aborts with OutOfMemoryError.
//
// What we emit is the subset CpgImporter consumes — methods, types, calls,
// literals, formal parameters, plus CALL, REACHING_DEF, AST (filtered),
// CDG, OVERRIDES, and INHERITS_FROM edges. Each call/literal/parameter
// carries a precomputed PARENT_METHOD_ID, and each method carries an
// IS_TEST flag, so the consumer never needs to walk AST. The output is a
// flat, untyped JSON shape (no GraphSON v3 wrappers); CpgImporter understands
// both formats.
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
      "METHOD_PARAMETER_IN" -> 0, "MEMBER" -> 0, "RETURN" -> 0, "CONTROL_STRUCTURE" -> 0,
      "CALL_EDGE" -> 0, "REACHING_DEF" -> 0,
      "AST" -> 0, "CDG" -> 0, "OVERRIDES" -> 0, "INHERITS_FROM" -> 0,
      "READS" -> 0, "WRITES" -> 0)

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

    cpg.parameter.foreach { p =>
      openVertex(p.id, "METHOD_PARAMETER_IN")
      val parentId = p.method.id
      var f = true
      f = kv(f, "NAME", jstr(p.name))
      f = kv(f, "TYPE_FULL_NAME", jstr(p.typeFullName))
      f = kv(f, "INDEX", p.index.toString)
      f = kv(f, "PARENT_METHOD_ID", "\"" + parentId.toString + "\"")
      closeVertex()
      counts("METHOD_PARAMETER_IN") += 1
    }

    // MEMBER (field declarations). Joern stores fields as MEMBER nodes under their
    // owning TYPE_DECL. We capture name, typeFullName, and the owner's full name so
    // the consumer can build (Method) Reads/Writes (Field) edges.
    cpg.member.foreach { mb =>
      // `mb.typeDecl` is the single owner TypeDecl; its .fullName is already a String,
      // so don't call .headOption on it (would silently give the first Char).
      val ownerFqn: String = Option(mb.typeDecl).map(_.fullName).getOrElse("")
      openVertex(mb.id, "MEMBER")
      var f = true
      f = kv(f, "NAME", jstr(mb.name))
      f = kv(f, "TYPE_FULL_NAME", jstr(mb.typeFullName))
      f = kv(f, "LINE_NUMBER", mb.lineNumber.getOrElse(-1).toString)
      f = kv(f, "OWNER_TYPE_FULL_NAME", jstr(ownerFqn))
      closeVertex()
      counts("MEMBER") += 1
    }

    // RETURN statements: one per `return ...` in source. PARENT_METHOD_ID links
    // them back to the enclosing method so the importer can synthesize AstContains.
    cpg.ret.foreach { r =>
      val parentId = r.method.id
      openVertex(r.id, "RETURN")
      var f = true
      f = kv(f, "CODE", jstr(r.code))
      f = kv(f, "LINE_NUMBER", r.lineNumber.getOrElse(-1).toString)
      f = kv(f, "PARENT_METHOD_ID", "\"" + parentId.toString + "\"")
      closeVertex()
      counts("RETURN") += 1
    }

    // CONTROL_STRUCTURE: if / for / while / try / etc. The controlStructureType
    // property is what we need to translate to Node.Stmt's StmtKind.
    cpg.controlStructure.foreach { cs =>
      val parentId = cs.method.id
      openVertex(cs.id, "CONTROL_STRUCTURE")
      var f = true
      f = kv(f, "CODE", jstr(cs.code))
      f = kv(f, "CONTROL_STRUCTURE_TYPE", jstr(cs.controlStructureType))
      f = kv(f, "LINE_NUMBER", cs.lineNumber.getOrElse(-1).toString)
      f = kv(f, "PARENT_METHOD_ID", "\"" + parentId.toString + "\"")
      closeVertex()
      counts("CONTROL_STRUCTURE") += 1
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

    // AST edges, filtered to METHOD → {CALL, LITERAL, METHOD_PARAMETER_IN} so the
    // importer can navigate from a Method to its callsites/literals/parameters
    // without pulling in IDENTIFIER/BLOCK noise.
    val astChildLabels = Set("CALL", "LITERAL", "METHOD_PARAMETER_IN")
    cpg.method.foreach { m =>
      m.outE.foreach { e =>
        if (e.label == "AST" && astChildLabels.contains(e.dst.label)) {
          edge("AST", m.id, e.dst.id)
          counts("AST") += 1
        }
      }
    }

    // CDG edges between CALL and LITERAL nodes — control dependence in the chop.
    (cpg.call.iterator ++ cpg.literal.iterator).foreach { node =>
      node.outE.foreach { e =>
        if (e.label == "CDG" && (e.dst.label == "CALL" || e.dst.label == "LITERAL")) {
          edge("CDG", node.id, e.dst.id)
          counts("CDG") += 1
        }
      }
    }

    // OVERRIDES: Method → overridden Method. Joern's javasrc2cpg does not emit raw
    // OVERRIDES edges for our toy. Compute them ourselves: for each method m in a
    // TypeDecl t, walk t's transitive supertypes (INHERITS_FROM resolved via
    // typeDecl.fullNameExact) and emit OVERRIDES(m, parent.m) whenever there is a
    // method with the same name and signature.
    val methodKey = (m: io.shiftleft.codepropertygraph.generated.nodes.Method) =>
      (m.name, m.signature)
    cpg.typeDecl.foreach { childTd =>
      val ancestors = childTd.inheritsFromTypeFullName.flatMap(cpg.typeDecl.fullNameExact(_).l).toSet
      for (m <- childTd.method.l) {
        for (parentTd <- ancestors) {
          parentTd.method.filter(pm => methodKey(pm) == methodKey(m)).foreach { parent =>
            edge("OVERRIDES", m.id, parent.id)
            counts("OVERRIDES") += 1
          }
        }
      }
    }

    // READS / WRITES: synthesized from `<operator>.fieldAccess` and the various
    // assignment/increment CALL nodes. Owner type taken from the fieldAccess receiver.
    val memberIdByOwnerAndName: Map[(String, String), Long] = cpg.member.map { mb =>
      val owner: String = Option(mb.typeDecl).map(_.fullName).getOrElse("")
      ((owner, mb.name): (String, String)) -> mb.id
    }.toMap

    def memberIdFor(fieldAccess: io.shiftleft.codepropertygraph.generated.nodes.Call): Option[Long] = {
      val args = fieldAccess.argument.l
      // FIELD_IDENTIFIER carries the field name; resolve via label-string for portability.
      val fieldName: Option[String] = args.find(_.label == "FIELD_IDENTIFIER")
        .flatMap { fi =>
          val v = fi.propertyOption("CANONICAL_NAME")
          if (v.isDefined) Some(v.get.toString) else None
        }
      fieldName.flatMap { fname =>
        // Receiver TYPE_FULL_NAME (first non-FIELD_IDENTIFIER arg).
        val receiverType: Option[String] = args.find(_.label != "FIELD_IDENTIFIER")
          .flatMap { node =>
            val opt = node.propertyOption("TYPE_FULL_NAME")
            if (opt.isDefined) {
              val s = opt.get.toString
              if (s.isEmpty || s == "<empty>") None else Some(s)
            } else None
          }
        val owners: Iterable[String] = receiverType match {
          case Some(t) => Seq(t)
          case None    => memberIdByOwnerAndName.keys.filter(_._2 == fname).map(_._1).toSeq.distinct
        }
        owners.flatMap(o => memberIdByOwnerAndName.get((o, fname))).headOption
      }
    }

    // For each fieldAccess CALL, emit READS(method → member). For each assignment whose
    // LHS is a fieldAccess, emit WRITES(method → member).
    val emittedReads = scala.collection.mutable.Set.empty[(Long, Long)]
    val emittedWrites = scala.collection.mutable.Set.empty[(Long, Long)]
    cpg.call.methodFullNameExact("<operator>.fieldAccess").foreach { fa =>
      val containing = fa.method
      memberIdFor(fa).foreach { mid =>
        val pair = (containing.id, mid)
        if (!emittedReads.contains(pair)) {
          edge("READS", containing.id, mid); emittedReads += pair; counts("READS") += 1
        }
      }
    }
    val writeOps = Set(
      "<operator>.assignment",
      "<operator>.assignmentPlus", "<operator>.assignmentMinus",
      "<operator>.assignmentMultiplication", "<operator>.assignmentDivision",
      "<operator>.assignmentModulo",
      "<operator>.assignmentAnd", "<operator>.assignmentOr", "<operator>.assignmentXor",
      "<operator>.assignmentShiftLeft", "<operator>.assignmentArithmeticShiftRight",
      "<operator>.assignmentLogicalShiftRight",
      "<operator>.preIncrement", "<operator>.postIncrement",
      "<operator>.preDecrement", "<operator>.postDecrement"
    )
    cpg.call.filter(c => writeOps.contains(c.methodFullName)).foreach { wop =>
      wop.argument.l.headOption.foreach {
        case lhs: io.shiftleft.codepropertygraph.generated.nodes.Call
          if lhs.methodFullName == "<operator>.fieldAccess" =>
          memberIdFor(lhs).foreach { mid =>
            val containing = wop.method
            val pair = (containing.id, mid)
            if (!emittedWrites.contains(pair)) {
              edge("WRITES", containing.id, mid); emittedWrites += pair; counts("WRITES") += 1
            }
          }
        case _ => ()
      }
    }

    // INHERITS_FROM: TypeDecl → TypeDecl. Joern's CPG actually emits TypeDecl → Type,
    // where Type then REFs to a TypeDecl. Resolve through inheritsFromTypeFullName so the
    // exported graph has direct TypeDecl → TypeDecl edges that downstream consumers can use.
    cpg.typeDecl.foreach { td =>
      td.inheritsFromTypeFullName.foreach { parentFqn =>
        cpg.typeDecl.fullNameExact(parentFqn).foreach { parent =>
          edge("INHERITS_FROM", td.id, parent.id)
          counts("INHERITS_FROM") += 1
        }
      }
    }

    w.write("]}")
    w.flush()
    println(s"[export-graph] vertices: METHOD=${counts("METHOD")} TYPE_DECL=${counts("TYPE_DECL")} " +
            s"CALL=${counts("CALL")} LITERAL=${counts("LITERAL")} " +
            s"METHOD_PARAMETER_IN=${counts("METHOD_PARAMETER_IN")} " +
            s"MEMBER=${counts("MEMBER")} RETURN=${counts("RETURN")} " +
            s"CONTROL_STRUCTURE=${counts("CONTROL_STRUCTURE")}")
    println(s"[export-graph] edges: CALL=${counts("CALL_EDGE")} REACHING_DEF=${counts("REACHING_DEF")} " +
            s"AST=${counts("AST")} CDG=${counts("CDG")} OVERRIDES=${counts("OVERRIDES")} " +
            s"INHERITS_FROM=${counts("INHERITS_FROM")} READS=${counts("READS")} WRITES=${counts("WRITES")}")
    println(s"[export-graph] wrote $outFile")
  } finally {
    w.close()
  }
}
