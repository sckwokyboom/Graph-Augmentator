package slicefix;
class FieldReadFails {
    String field = "stored";
    void target(String s) {}
    void caller() { target(this.field); }
}
