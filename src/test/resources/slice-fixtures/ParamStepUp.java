package slicefix;
class ParamStepUp {
    void target(String s) {}
    void mid(String s) { target(s); }
    void top() { mid("from-top"); }
}
