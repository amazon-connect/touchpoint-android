package fixture
fun f(): Nothing =
    throw
        RuntimeException("split across lines")
