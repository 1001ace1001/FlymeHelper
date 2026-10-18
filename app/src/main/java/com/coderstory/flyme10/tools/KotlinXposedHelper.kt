@file:Suppress("unused")

package com.coderstory.flyme10.tools

import com.coderstory.flyme10.xposedcompat.XposedHelpers

/**
 * Minimal legacy-style Kotlin extensions used by the hook code. The old version of this
 * file contained a large DSL over the legacy Xposed API; it was dead code and has been
 * dropped during the migration to the modern libxposed API.
 */
fun Any.getObjectField(fieldName: String?): Any? = XposedHelpers.getObjectField(this, fieldName)
