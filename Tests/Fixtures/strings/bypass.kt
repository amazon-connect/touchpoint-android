package fixture

import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.buildAnnotatedString

// Thirteen user-facing literals that bypass resources (two lines hold two each), each a form the gate must see.
@Composable
fun Bypasses(name: String, label: String?, open: Boolean, state: Int, user: String?) {
    Text("Hello there")
    BasicText("Send message")
    Text("Don\'t go")
    Text("""Raw literal""")
    Text(
        "On the next line",
    )
    Text(label ?: "Send")
    Text(text = buildAnnotatedString { append("Annotated hello") })
    Text("Welcome back, $name!")
    val description = Description(contentDescription = if (open) "Close" else "Open")
    Text(when (state) { 0 -> "Alpha"; else -> "Beta" })
    Text(user?.let { "Hello $it" } ?: "")
}

class Description(val contentDescription: String)
