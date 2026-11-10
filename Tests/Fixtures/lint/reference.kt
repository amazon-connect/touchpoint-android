package fixture
fun f(xs: List<String?>) = xs.map(::requireNotNull)
