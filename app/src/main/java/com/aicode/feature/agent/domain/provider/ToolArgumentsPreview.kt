package com.aicode.feature.agent.domain.provider

internal class ToolArgumentsPreview(
    private val maxChars: Int = 128 * 1024,
    private val intervalNanos: Long = 75_000_000L,
    private val nanoTime: () -> Long = System::nanoTime
) {
    private data class Counter(val source: CharSequence, val length: Int, val ascii: Int, val other: Int)
    private data class Emission(val name: String, val text: String, val time: Long, val counter: Counter)

    private val emitted = mutableMapOf<String, Emission>()

    fun update(
        id: String,
        name: String,
        arguments: CharSequence,
        force: Boolean = false
    ): AIStreamChunk.ToolCallArgumentsDelta? {
        if (id.isBlank() || name.isBlank() || arguments.isEmpty()) return null
        val previous = emitted[id]
        val now = nanoTime()
        if (!force && previous != null && now - previous.time < intervalNanos) return null
        var length = minOf(arguments.length, maxChars)
        if (length > 0 && arguments[length - 1].isHighSurrogate()) length--
        val text = arguments.subSequence(0, length).toString()
        // 适配器对同一缓冲只追加，完整重写会更换对象；计数仅扫描新增后缀。
        val prior = previous?.counter?.takeIf { it.source === arguments && it.length <= arguments.length }
        var ascii = prior?.ascii ?: 0
        var other = prior?.other ?: 0
        for (index in (prior?.length ?: 0) until arguments.length) {
            if (arguments[index].code < 128) ascii++ else other++
        }
        val counter = Counter(arguments, arguments.length, ascii, other)
        emitted[id] = Emission(name, text, now, counter)
        if (previous?.text == text && previous.name == name &&
            previous.counter.ascii == ascii && previous.counter.other == other) return null
        return AIStreamChunk.ToolCallArgumentsDelta(id, name, text, ascii, other)
    }
}
