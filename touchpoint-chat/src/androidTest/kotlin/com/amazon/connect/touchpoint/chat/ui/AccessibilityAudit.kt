package com.amazon.connect.touchpoint.chat.ui

import android.util.Log
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator

/**
 * Google's Accessibility Test Framework over every interaction a test makes, from the window's root,
 * as the demo's suite runs it: an error fails the test; warnings — contrast the framework estimates
 * from pixels, which misreads empty fields and mid-animation frames — are logged (tag `TPA11Y`).
 */
internal object AccessibilityAudit {
    fun validator(): AccessibilityValidator =
        AccessibilityValidator()
            .setRunChecksFromRootView(true)
            .addCheckListener(
                AccessibilityValidator.CheckResultsListener { callback ->
                    // Read as a plain list: the framework's own type is Guava's, which is not on this classpath.
                    val results = callback.javaClass.getMethod("getAccessibilityViewCheckResults").invoke(callback) as Iterable<*>
                    results.filterIsInstance<AccessibilityViewCheckResult>()
                        .filter { it.type == AccessibilityCheckResultType.ERROR || it.type == AccessibilityCheckResultType.WARNING }
                        .forEach { r ->
                            Log.w("TPA11Y", "${r.type} ${r.sourceCheckClass.simpleName} ${r.element?.boundsInScreen} ${r.element?.className}: ${r.message}")
                        }
                },
            )
}
