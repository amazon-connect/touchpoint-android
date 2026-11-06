package fixture

import kotlin.collections.List as KList // an alias is not a cast

/* A block comment mentioning error() and x!! /* nested, with TODO() */ still a comment !! */
// error("in a line comment"), require(false), y as String
class Clean(private val name: String?) {
    fun length(): Int = name?.length ?: 0

    fun cast(x: Any): String? = x as? String

    fun text(): String = "Done!! as planned, error() and check() are only words here"

    fun template(): String = "Hello ${name?.uppercase() ?: "nobody"}, done!!"

    fun raw(): String = """
        Raw strings may say !! and TODO() and as String without tripping anything.
    """

    fun quote(): Char = '"'

    fun `backticked name with as and !! in it`() = Unit

    fun exceptions(): Exception = IllegalStateException("constructed, not thrown")
}

// Declaring members named check/error is not calling the stdlib's, and a reviewed exception is
// suppressed on its own line with the reason.
class Validator {
    fun check(value: Int): Boolean = value > 0

    fun error(message: String): String = message


    fun use() = error("shown in the field") // touchpoint-lint: allow error — fixture: a member call, not kotlin.error
}
