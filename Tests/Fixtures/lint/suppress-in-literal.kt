package fixture

// A suppression marker quoted inside a string literal is not a suppression.
fun quoted(x: String?): String = x!! + "// touchpoint-lint: allow nonnull — not a comment"
