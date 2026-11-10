package fixture

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class Metrics(val padding: androidx.compose.ui.unit.Dp)

fun clean(metrics: Metrics): Modifier {
    val count = 12            // a number that is not a dimension
    val label = "12.dp"       // a dimension inside a string
    return Modifier.padding(metrics.padding).padding(0.dp)   // zero carries no size
}
