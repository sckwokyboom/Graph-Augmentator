from harness.kgpool.bytecode import redact_member

JAVAP = """Compiled from "C.java"
class p.C {
  public int m(int);
    Code:
       0: iload_1
       1: ireturn
  public int other();
    Code:
       0: invokevirtual #2  // Method m:(I)I
       3: ireturn
}"""


def test_redact_member_removes_code_keeps_references():
    out = redact_member(JAVAP, "m")
    assert "iload_1" not in out
    assert "REDACTED" in out
    assert "public int other();" in out
    assert "Method m:(I)I" in out
