package fixture
fun f(c: Char, a: String?): String = "${if (c == '}') a!! else "b"}"
