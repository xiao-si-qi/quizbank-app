package com.xiaosiqi.quizbank.data

import com.xiaosiqi.quizbank.model.Option
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** SQLite 里用 JSON 字段存选项/答案/标签，避免为它们建额外的关联表。 */
object JsonCodec {

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val optionList = ListSerializer(Option.serializer())
    private val stringList = ListSerializer(String.serializer())

    fun encodeOptions(options: List<Option>): String = json.encodeToString(optionList, options)

    fun decodeOptions(raw: String?): List<Option> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(optionList, raw) }.getOrDefault(emptyList())
    }

    fun encodeStrings(values: List<String>): String = json.encodeToString(stringList, values)

    fun decodeStrings(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(stringList, raw) }.getOrDefault(emptyList())
    }
}
