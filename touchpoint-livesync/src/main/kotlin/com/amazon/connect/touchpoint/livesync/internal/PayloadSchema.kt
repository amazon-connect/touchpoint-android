package com.amazon.connect.touchpoint.livesync.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal

/**
 * Checks an action payload against the JSON Schema the app advertised, on the device, before any
 * handler sees it: the Live Sync backend
 * does not validate against the advertised schema, so this is the only check that holds.
 *
 * A strict subset of JSON Schema, and **fail-closed**: a keyword outside it makes the schema
 * unsupported, and every payload against it is refused. (A standards validator ignores an unknown
 * keyword as an annotation, the wrong default for a security control.) The whole schema is vetted
 * up front, so an unsupported keyword in a branch the payload never reaches still refuses it.
 *
 * - Supported: `type` (a name or a list of names), `enum`, `const`, `properties`, `required`,
 *   `additionalProperties` (a boolean or a schema), `items` (one schema), `minItems`, `maxItems`,
 *   `minLength`, `maxLength` (code points), `minimum`, `maximum`, `exclusiveMinimum`,
 *   `exclusiveMaximum`, and `format: "date"` (`YYYY-MM-DD`, a day that exists).
 * - Annotations, ignored: `title`, `description`, `$schema`, `$id`, `$comment`, `examples`,
 *   `default`, `deprecated`, `readOnly`, `writeOnly`.
 * - Unsupported: everything else (`pattern`, `multipleOf`, other formats, `oneOf`/`anyOf`/`allOf`/
 *   `not`, `$ref`, conditionals, tuple `items`), a malformed value of a supported keyword, and a
 *   kind-specific keyword (`properties`, `minLength`…) without a `type` to bind it.
 *
 * Numbers compare exactly (a double loses integers past 2^53), `1.0` equals `1`, and a boolean is
 * never a number. A `null` for a property whose schema does not admit null counts as not sent, as
 * an optional field decodes it. A violation names the keyword and an RFC 6901 pointer into the
 * payload, never a value or a key the payload chose: an unexpected key is reported at its
 * parent.
 */
internal object PayloadSchema {
    data class Violation(
        /** The keyword that failed, or the unsupported one. */
        val keyword: String,
        /** RFC 6901 pointer into the payload; `""` is the root. */
        val pointer: String,
        /** The schema itself is outside the subset. */
        val unsupported: Boolean,
    ) {
        override fun toString(): String {
            val at = pointer.ifEmpty { "/" }
            return if (unsupported) "unsupported schema keyword $keyword at $at" else "$keyword at $at"
        }
    }

    private val ANNOTATIONS = setOf("title", "description", "\$schema", "\$id", "\$comment", "examples", "default", "deprecated", "readOnly", "writeOnly")
    private val ASSERTIONS =
        setOf(
            "type", "enum", "const", "properties", "required", "additionalProperties", "items", "minItems", "maxItems",
            "minLength", "maxLength", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "format",
        )
    private val FORMATS = setOf("date")

    /** Assertions that bind one kind of value, so need a `type` beside them. */
    private val TYPE_SPECIFIC =
        listOf(
            "properties", "required", "additionalProperties", "items", "minItems", "maxItems",
            "minLength", "maxLength", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "format",
        )
    private val TYPE_NAMES = setOf("string", "number", "integer", "boolean", "object", "array", "null")

    /** The first violation of [schema] by [value], or `null` when it conforms. */
    fun validate(
        value: JsonElement,
        schema: JsonElement,
    ): Violation? = schemaProblem(schema, "") ?: inspect(value, schema, "")

    /** The first unsupported keyword anywhere in [schema]: what the app hears when it advertises one. */
    fun unsupportedKeyword(schema: JsonElement): Violation? = schemaProblem(schema, "")

    /**
     * Top-level properties the Live Sync backend rewrites: when an object payload carries a truthy
     * `items` or `value`, the engine sends only that member's value, which no longer matches.
     */
    fun backendUnwrappedProperty(schema: JsonElement): String? {
        val properties = (schema as? JsonObject)?.get("properties") as? JsonObject ?: return null
        return listOf("items", "value").firstOrNull { it in properties }
    }

    /**
     * Where the engine's own schema conversion will not produce what the schema says: it turns a node
     * with no `type`, a `type` list and `"null"` into a string. [admitsString]: whether the string it
     * sends can still pass, or the action's invocations will be refused.
     */
    data class EngineRewrite(
        val description: String,
        val admitsString: Boolean,
    )

    fun engineRewrite(
        schema: JsonElement,
        path: String = "",
    ): EngineRewrite? {
        val s = schema as? JsonObject ?: return null
        val at = path.ifEmpty { "/" }
        when (val type = s["type"]) {
            null -> {
                // An enum or const of strings survives the string conversion; anything else does not.
                val values = ((s["enum"] as? JsonArray)?.toList() ?: emptyList()) + listOfNotNull(s["const"])
                if (values.isEmpty() || !values.all { it is JsonPrimitive && it.isString }) {
                    return EngineRewrite("a node with no type at $at", admitsString = values.isEmpty())
                }
            }
            is JsonArray -> return EngineRewrite("a type array at $at", admitsString = JsonPrimitive("string") in type)
            else -> if (type == JsonPrimitive("null")) return EngineRewrite("type null at $at", admitsString = false)
        }
        (s["properties"] as? JsonObject)?.let { properties ->
            for (name in properties.keys.sorted()) properties[name]?.let { child -> engineRewrite(child, path + "/" + escape(name))?.let { return it } }
        }
        s["items"]?.let { items -> engineRewrite(items, "$path/items")?.let { return it } }
        (s["additionalProperties"] as? JsonObject)?.let { extra -> engineRewrite(extra, path)?.let { return it } }
        return null
    }

    private fun schemaProblem(
        schema: JsonElement,
        path: String,
    ): Violation? {
        // `true` and `false` are schemas only as additionalProperties, handled there.
        val keywords = schema as? JsonObject ?: return Violation("schema", path, unsupported = true)
        fun unsupported(keyword: String) = Violation(keyword, path, unsupported = true)
        keywords.keys.sorted().firstOrNull { it !in ANNOTATIONS && it !in ASSERTIONS }?.let { return unsupported(it) }
        keywords["format"]?.let { if (!(it is JsonPrimitive && it.isString && it.content in FORMATS)) return unsupported("format") }
        for (key in listOf("exclusiveMinimum", "exclusiveMaximum", "minimum", "maximum")) keywords[key]?.let { if (number(it) == null) return unsupported(key) }
        for (key in listOf("minItems", "maxItems", "minLength", "maxLength")) keywords[key]?.let { if (count(it) == null) return unsupported(key) }
        keywords["type"]?.let { if (typeNames(it) == null) return unsupported("type") }
        // Without a type, JSON Schema applies these only to values of their own kind: a string would
        // pass a schema that looks like an object's.
        if ("type" !in keywords) TYPE_SPECIFIC.firstOrNull { it in keywords }?.let { return unsupported("$it without type") }
        keywords["enum"]?.let { if (it !is JsonArray) return unsupported("enum") }
        keywords["required"]?.let { if (!(it is JsonArray && it.all { n -> n is JsonPrimitive && n.isString })) return unsupported("required") }
        keywords["properties"]?.let { properties ->
            if (properties !is JsonObject) return unsupported("properties")
            for (name in properties.keys.sorted()) properties[name]?.let { child -> schemaProblem(child, path + "/" + escape(name))?.let { return it } }
        }
        keywords["additionalProperties"]?.let { extra ->
            if (!(extra is JsonPrimitive && !extra.isString && extra.booleanOrNull != null)) schemaProblem(extra, path)?.let { return it }
        }
        // A list (`items: [ … ]`) is draft-04's positional form: not in the subset.
        keywords["items"]?.let { items -> schemaProblem(items, "$path/items")?.let { return it } }
        return null
    }

    private fun inspect(
        value: JsonElement,
        schema: JsonElement,
        pointer: String,
    ): Violation? {
        val s = schema as? JsonObject ?: return null
        fun fail(keyword: String) = Violation(keyword, pointer, unsupported = false)

        s["type"]?.let { type -> typeNames(type)?.let { names -> if (names.none { matches(value, it) }) return fail("type") } }
        (s["enum"] as? JsonArray)?.let { options -> if (options.none { same(value, it) }) return fail("enum") }
        s["const"]?.let { if (!same(value, it)) return fail("const") }

        when {
            value is JsonPrimitive && value.isString -> {
                val length = value.content.codePointCount(0, value.content.length)
                s["minLength"]?.let(::count)?.let { if (length < it) return fail("minLength") }
                s["maxLength"]?.let(::count)?.let { if (length > it) return fail("maxLength") }
                if (s["format"] == JsonPrimitive("date") && !isCalendarDate(value.content)) return fail("format")
            }
            value is JsonPrimitive && value != JsonNull && value.booleanOrNull == null -> {
                if (number(value) == null) return fail("type")
                fun order(bound: String): Int? = s[bound]?.let { compare(value, it) }
                order("minimum")?.let { if (it < 0) return fail("minimum") }
                order("maximum")?.let { if (it > 0) return fail("maximum") }
                order("exclusiveMinimum")?.let { if (it <= 0) return fail("exclusiveMinimum") }
                order("exclusiveMaximum")?.let { if (it >= 0) return fail("exclusiveMaximum") }
            }
            value is JsonArray -> {
                s["minItems"]?.let(::count)?.let { if (value.size < it) return fail("minItems") }
                s["maxItems"]?.let(::count)?.let { if (value.size > it) return fail("maxItems") }
                s["items"]?.let { items -> value.forEachIndexed { i, element -> inspect(element, items, "$pointer/$i")?.let { return it } } }
            }
            value is JsonObject -> {
                val properties = s["properties"] as? JsonObject ?: JsonObject(emptyMap())
                // A null for a property whose schema does not admit null counts as not sent.
                fun notSent(name: String): Boolean {
                    val member = value[name] ?: return true
                    return member == JsonNull && !admitsNull(properties[name])
                }
                (s["required"] as? JsonArray)?.forEach { name -> if (name is JsonPrimitive && name.isString && notSent(name.content)) return fail("required") }
                for (name in value.keys.sorted()) {
                    val member = value[name] ?: JsonNull
                    val child = properties[name]
                    if (child != null) {
                        if (notSent(name)) continue
                        inspect(member, child, pointer + "/" + escape(name))?.let { return it }
                    } else {
                        when (val extra = s["additionalProperties"]) {
                            null -> Unit
                            is JsonPrimitive -> if (extra.booleanOrNull == false) return fail("additionalProperties") // the parent, never the key
                            else -> inspect(member, extra, pointer)?.let { return it }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun admitsNull(schema: JsonElement?): Boolean = (schema as? JsonObject)?.get("type")?.let(::typeNames)?.contains("null") == true

    private fun typeNames(type: JsonElement): List<String>? =
        when {
            type is JsonPrimitive && type.isString -> listOf(type.content).takeIf { type.content in TYPE_NAMES }
            type is JsonArray -> {
                val names = type.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString && p.content in TYPE_NAMES }?.content }
                names.takeIf { it.size == type.size && it.isNotEmpty() }
            }
            else -> null
        }

    private fun matches(
        value: JsonElement,
        type: String,
    ): Boolean =
        when (type) {
            "string" -> value is JsonPrimitive && value.isString
            "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            "null" -> value == JsonNull
            "number" -> number(value) != null
            // Integral, exactly: 3.5 is not, 3.0 is, and 2^53 + 1 stays itself.
            "integer" -> number(value)?.let { it.signum() == 0 || it.stripTrailingZeros().scale() <= 0 } == true
            else -> false
        }

    /** A number, exactly, whichever way the wire wrote it; never a boolean, a string or null. */
    private fun number(value: JsonElement): BigDecimal? =
        (value as? JsonPrimitive)?.takeIf { !it.isString && it != JsonNull && it.booleanOrNull == null }?.content?.toBigDecimalOrNull()

    /** A non-negative integral bound (`minLength` and the like). */
    private fun count(value: JsonElement): Int? =
        number(value)?.takeIf { it.signum() >= 0 }?.let {
            try {
                it.intValueExact()
            } catch (_: ArithmeticException) {
                null
            }
        }

    private fun compare(
        a: JsonElement,
        b: JsonElement,
    ): Int? {
        val x = number(a) ?: return null
        val y = number(b) ?: return null
        return x.compareTo(y)
    }

    /** JSON equality, numbers by value: a schema's `1.0` equals a wire `1`. */
    private fun same(
        a: JsonElement,
        b: JsonElement,
    ): Boolean =
        when {
            number(a) != null && number(b) != null -> compare(a, b) == 0
            a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
            a is JsonObject && b is JsonObject -> a.size == b.size && a.all { (key, v) -> b[key]?.let { same(v, it) } == true }
            else -> a == b
        }

    /** RFC 3339 `full-date`: four, two and two ASCII digits, and a day that exists. */
    fun isCalendarDate(text: String): Boolean {
        if (text.length != DATE_LENGTH || text[YEAR_END] != '-' || text[MONTH_END] != '-') return false
        fun digits(range: IntRange): Int? = text.substring(range.first, range.last + 1).takeIf { s -> s.all { it in '0'..'9' } }?.toInt()
        val year = digits(0 until YEAR_END) ?: return false
        val month = digits(YEAR_END + 1 until MONTH_END) ?: return false
        val day = digits(MONTH_END + 1 until DATE_LENGTH) ?: return false
        if (month !in 1..MONTHS || day < 1) return false
        val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[month - 1]
        return day <= days
    }

    private const val DATE_LENGTH = 10
    private const val YEAR_END = 4
    private const val MONTH_END = 7
    private const val MONTHS = 12

    /** RFC 6901 escaping of a pointer segment. */
    private fun escape(name: String): String = name.replace("~", "~0").replace("/", "~1")
}
