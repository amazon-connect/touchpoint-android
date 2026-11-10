package fixture

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Fourteen literal forms, one per line, so the gate's count is exact.
val a = Modifier.padding(12.dp)
val b = Modifier.padding(horizontal = 16.dp)
val c = 14.sp
val d = 1.5.em
val e = 8f.dp
val f = -4.dp
val g = TextUnit(14f, TextUnitType.Sp)
val h = Modifier.padding(0.dp, 12.dp)
val i = TextUnit(0.5f, TextUnitType.Sp)
val j = Dp(12f)
val k = TextUnit(value = 14f, type = TextUnitType.Sp)
val l = (12).dp
val m = 1_6.dp
val n = 0x10.dp
