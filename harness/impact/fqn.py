"""Canonical FQN normalization shared by all producers so method/test names agree
across methods.json, coverage.json, mutation.json, and the diff parser.

Canonical method FQN: package.Outer$Nested.method  (no signature, no return type)
Canonical test FQN:   package.TestClass.testMethod  (no JUnit [param] suffix)
"""


def method_fqn_from_joern(full_name: str) -> str:
    # Joern FULL_NAME: "pkg.Cls.method:returnType(params)" — strip the ":..." signature.
    return full_name.split(":", 1)[0]


def method_fqn_from_jacoco(class_vm_name: str, method_name: str) -> str:
    # JaCoCo class names use '/' as package separator; '$' for nested stays.
    return class_vm_name.replace("/", ".") + "." + method_name


def method_fqn_from_pitest(mutated_class: str, mutated_method: str) -> str:
    # PITest already uses '.'-separated class names with '$' for nested.
    return mutated_class + "." + mutated_method


def test_fqn(class_name: str, method_name: str) -> str:
    return class_name + "." + method_name.split("[", 1)[0]


# Not a pytest test; the ``test_`` prefix names a *test*-method FQN. Without this,
# pytest collects the function when it is imported into a test module.
test_fqn.__test__ = False
