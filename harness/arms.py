ALL_ARMS = ["no-context", "javabench-selective", "gt-current",
            "gt+jacoco", "gt+katz", "gt+jacoco+katz"]

STANDALONE_ARMS = [a for a in ALL_ARMS if a != "javabench-selective"]
