package fixture
// No Compose import: scaled-metrics is about rendered dimensions and does not apply.
class Dp(val v: Int)
val Int.dp: Dp get() = Dp(this)
val gap = 12.dp
