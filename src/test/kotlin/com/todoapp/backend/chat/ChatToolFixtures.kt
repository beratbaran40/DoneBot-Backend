package com.todoapp.backend.chat

import com.google.protobuf.ListValue
import com.google.protobuf.NullValue
import com.google.protobuf.Struct
import com.google.protobuf.Value

/**
 * Builds the protobuf [Struct] Vertex hands to `ChatToolService.execute`, from ordinary Kotlin values.
 *
 * Worth having rather than assembling builders inline: several tool behaviours hinge on the difference
 * between a field being ABSENT and being PRESENT-but-empty (`setTaskSchedule` clears on the latter and
 * inherits on the former), and that distinction is only readable in a test if constructing the two is
 * this cheap.
 */
internal fun structOf(vararg pairs: Pair<String, Any?>): Struct {
    val builder = Struct.newBuilder()
    pairs.forEach { (key, value) -> builder.putFields(key, protoValue(value)) }
    return builder.build()
}

private fun protoValue(value: Any?): Value = when (value) {
    null -> Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()
    is String -> Value.newBuilder().setStringValue(value).build()
    is Boolean -> Value.newBuilder().setBoolValue(value).build()
    is Number -> Value.newBuilder().setNumberValue(value.toDouble()).build()
    is List<*> -> Value.newBuilder()
        .setListValue(ListValue.newBuilder().addAllValues(value.map(::protoValue)).build())
        .build()
    is Map<*, *> -> Value.newBuilder()
        .setStructValue(
            Struct.newBuilder().apply {
                value.forEach { (k, v) -> putFields(k.toString(), protoValue(v)) }
            }.build(),
        )
        .build()
    else -> error("structOf: unsupported value type ${value::class.simpleName}")
}
