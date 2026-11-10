package fixture
fun f(c: Char, a: String?): String = "${if (c == '"') a!!.length else 0}"
