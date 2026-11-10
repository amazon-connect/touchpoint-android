package fixture

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.res.stringResource

/**
 * KDoc may quote code: `Text("Hello")` is documentation, not a literal on screen.
 */
@Composable
fun Greeting(name: String, count: Int) {
    Text(stringResource(R.string.touchpoint_greeting))
    Text("$name") // a composition with no text of its own
    Text("$count · ${count + 1}")
    Text(stringResource(R.string.touchpoint_greeting), modifier = Modifier.testTag("greeting-title"))
    val text = "an ordinary value, not UI"
    Text(stringResource(R.string.touchpoint_greeting), modifier = Modifier.semantics { testTag = "greeting-semantics" })
    val log = StringBuilder().append("key=value") // a builder, not buildAnnotatedString
}
