package com.ntolb.syncassist

data class FileComment(
    val raw: String,
    val leadingWhitespace: String,
    val trailingWhitespace: String,
    val anchorText: String?,
    val leftContext: String,
    val isBlock: Boolean,
)

data class ParsedFileText(
    val syncText: String,
    val comments: List<FileComment>,
)

class InvalidCommentMarkersException(message: String) : IllegalArgumentException(message)

object CommentText {
    const val START_MARKER = "!!!>"
    const val END_MARKER = "<!!!"
    private const val CONTEXT_LENGTH = 48

    fun parse(rawText: String, enabled: Boolean): ParsedFileText =
        if (enabled) parse(rawText) else ParsedFileText(rawText, emptyList())

    fun parse(rawText: String): ParsedFileText {
        val visible = StringBuilder()
        val pending = mutableListOf<PendingComment>()
        var cursor = 0

        while (cursor < rawText.length) {
            val start = rawText.indexOf(START_MARKER, cursor)
            val strayEnd = rawText.indexOf(END_MARKER, cursor)
            if (strayEnd >= 0 && (start < 0 || strayEnd < start)) {
                throw InvalidCommentMarkersException("Found an end marker without a start marker.")
            }
            if (start < 0) {
                visible.append(rawText, cursor, rawText.length)
                cursor = rawText.length
                break
            }

            val precedingText = rawText.substring(cursor, start)
            visible.append(precedingText)
            val leadingWhitespace = precedingText.takeLastWhile(Char::isWhitespace)
            visible.setLength(visible.length - leadingWhitespace.length)
            val end = rawText.indexOf(END_MARKER, start + START_MARKER.length)
            val nestedStart = rawText.indexOf(START_MARKER, start + START_MARKER.length)
            if (end < 0) {
                throw InvalidCommentMarkersException("Found a start marker without an end marker.")
            }
            if (nestedStart in (start + 1) until end) {
                throw InvalidCommentMarkersException("Nested comment start markers are not supported.")
            }

            val commentEnd = end + END_MARKER.length
            val trailingWhitespace = rawText.substring(commentEnd)
                .takeWhile(Char::isWhitespace)
            val lineStart = rawText.lastIndexOf('\n', start - 1) + 1
            val lineEnd = rawText.indexOf('\n', commentEnd).let {
                if (it < 0) rawText.length else it
            }
            val isBlock = rawText.substring(lineStart, start).isBlank() &&
                rawText.substring(commentEnd, lineEnd).isBlank() 

            pending.add(
                PendingComment(
                    raw = rawText.substring(start, commentEnd),
                    leadingWhitespace = leadingWhitespace,
                    trailingWhitespace = trailingWhitespace,
                    anchorOffset = visible.length,
                    isBlock = isBlock,
                ),
            )
            cursor = commentEnd + trailingWhitespace.length
        }

        val baseText = visible.toString()
        val gaps = pending.groupBy { it.anchorOffset }.mapValues { (offset, group) ->
            val hasTextBefore = baseText.substring(0, offset).any { !it.isWhitespace() }
            val hasTextAfter = baseText.substring(offset).any { !it.isWhitespace() }
            if (!hasTextBefore || !hasTextAfter) {
                ""
            } else if (group.any {
                    it.leadingWhitespace.contains('\n') || it.trailingWhitespace.contains('\n')
                }
            ) {
                "\n"
            } else if (group.any {
                    it.leadingWhitespace.isNotEmpty() || it.trailingWhitespace.isNotEmpty()
                }
            ) {
                " "
            } else {
                ""
            }
        }
        val syncTextBuilder = StringBuilder()
        var baseCursor = 0
        for ((offset, gap) in gaps.toSortedMap()) {
            syncTextBuilder.append(baseText, baseCursor, offset)
            syncTextBuilder.append(gap)
            baseCursor = offset
        }
        syncTextBuilder.append(baseText, baseCursor, baseText.length)
        val syncText = syncTextBuilder.toString()
        val comments = pending.map { comment ->
            val insertedGapLength = gaps.entries
                .filter { it.key <= comment.anchorOffset }
                .sumOf { it.value.length }
            val anchorOffset = comment.anchorOffset + insertedGapLength
            val anchorStart = (anchorOffset until syncText.length)
                .firstOrNull { !syncText[it].isWhitespace() }
            val anchorText = anchorStart?.let { start ->
                var end = start
                while (end < syncText.length && !syncText[end].isWhitespace()) end++
                syncText.substring(start, end)
            }
            val leftContext = anchorStart?.let { start ->
                syncText.substring((start - CONTEXT_LENGTH).coerceAtLeast(0), start)
            }.orEmpty()
            comment.toFileComment(anchorText, leftContext)
        }
        return ParsedFileText(syncText, comments)
    }

    fun preserveComments(syncText: String, comments: List<FileComment>): String {
        if (comments.isEmpty()) return syncText
        val insertions = mutableMapOf<Int, MutableList<FileComment>>()
        val unresolved = mutableListOf<FileComment>()

        for (comment in comments) {
            val anchor = comment.anchorText
            val position = if (anchor.isNullOrEmpty()) {
                null
            } else {
                findAnchor(syncText, anchor, comment.leftContext)
            }
            if (position == null) {
                unresolved.add(comment)
            } else {
                insertions.getOrPut(position) { mutableListOf() }.add(comment)
            }
        }

        val result = StringBuilder()
        var cursor = 0
        for ((position, anchoredComments) in insertions.toSortedMap()) {
            val precedingWhitespaceStart = cursor + syncText.substring(cursor, position)
                .indexOfLast { !it.isWhitespace() } + 1
            result.append(syncText, cursor, precedingWhitespaceStart)
            appendComments(result, anchoredComments)
            cursor = position
        }
        result.append(syncText, cursor, syncText.length)
        appendUnresolvedComments(result, unresolved)
        return result.toString()
    }

    private fun findAnchor(text: String, anchor: String, leftContext: String): Int? {
        var searchFrom = 0
        var bestPosition: Int? = null
        var bestScore = -1
        while (searchFrom <= text.length - anchor.length) {
            val position = text.indexOf(anchor, searchFrom)
            if (position < 0) break
            val score = matchingContextLength(text, position, leftContext)
            if (score > bestScore) {
                bestPosition = position
                bestScore = score
            }
            searchFrom = position + 1
        }
        return bestPosition
    }

    private fun matchingContextLength(text: String, position: Int, context: String): Int {
        var count = 0
        while (
            count < context.length &&
            position - count - 1 >= 0 &&
            context[context.length - count - 1] == text[position - count - 1]
        ) {
            count++
        }
        return count
    }

    private fun appendComments(result: StringBuilder, comments: List<FileComment>) {
        comments.forEach { comment ->
            if (
                comment.isBlock && result.isNotEmpty() &&
                result.last() != '\n' && !comment.leadingWhitespace.contains('\n')
            ) {
                result.append('\n')
            }
            result.append(comment.leadingWhitespace)
            result.append(comment.raw)
            result.append(comment.trailingWhitespace)
            if (comment.isBlock && !comment.trailingWhitespace.contains('\n')) {
                result.append('\n')
            }
        }
    }

    private fun appendUnresolvedComments(result: StringBuilder, comments: List<FileComment>) {
        if (comments.isEmpty()) return
        if (result.isNotEmpty() && result.last() != '\n' &&
            comments.any { it.isBlock && !it.leadingWhitespace.contains('\n') }
        ) {
            result.append('\n')
        }
        appendComments(result, comments)
    }

    private fun PendingComment.toFileComment(anchorText: String?, leftContext: String) =
        FileComment(
            raw = raw,
            leadingWhitespace = leadingWhitespace,
            trailingWhitespace = trailingWhitespace,
            anchorText = anchorText,
            leftContext = leftContext,
            isBlock = isBlock,
        )

    private data class PendingComment(
        val raw: String,
        val leadingWhitespace: String,
        val trailingWhitespace: String,
        val anchorOffset: Int,
        val isBlock: Boolean,
    )
}
