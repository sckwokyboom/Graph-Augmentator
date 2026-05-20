package slicefix;
class IntraProcLocalVar {
    void target(String s) {}
    void caller() { String x = "world"; target(x); }
}
