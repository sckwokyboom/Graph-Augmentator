package slicefix;
class ArrayInitAndAccess {
    void target(String s) {}
    void caller() { String[] arr = new String[]{"first", "second"}; target(arr[0]); }
}
