package com.teches.filesync

import kotlin.math.max
import kotlin.math.min

object Similarity {
    const val CONTENT_THRESHOLD = 0.70
    const val NAME_THRESHOLD = 0.50
    private const val SHINGLE_WORDS = 5

    private val wordPattern = Regex("[\\p{L}\\p{N}]+")
    private val segmentSplitter = Regex("(?<=[.!?])\\s+|[\\r\\n]+")
    private val commonWords = setOf(
        "a", "about", "after", "again", "against", "all", "also", "am", "an", "and",
        "any", "are", "as", "at", "be", "because", "been", "before", "being", "between",
        "both", "but", "by", "can", "could", "did", "do", "does", "doing", "down",
        "during", "each", "few", "for", "from", "further", "had", "has", "have", "having",
        "he", "her", "here", "hers", "him", "his", "how", "i", "if", "in", "into", "is",
        "it", "its", "itself", "just", "me", "more", "most", "my",
        "of", "off", "on", "once", "only", "or", "other", "our", "out", "over", "own",
        "same", "she", "should", "so", "some", "such", "than", "that", "the", "their",
        "them", "then", "there", "these", "they", "this", "those", "through", "to", "too",
        "under", "until", "up", "very", "was", "we", "were", "what", "when", "where",
        "which", "while", "who", "whom", "why", "will", "with", "would", "you", "your",
    )

    private fun words(text: String): List<String> =
        wordPattern.findAll(text.lowercase()).map { it.value }.toList()

    private fun meaningfulWords(text: String): List<String> =
        words(text).filterNot(commonWords::contains)

    /** Share of the larger file's content that also appears in the other file. */
    fun content(a: String, b: String): Double {
        if (a.isBlank() || b.isBlank()) return 1.0

        val wordsA = meaningfulWords(a)
        val wordsB = meaningfulWords(b)
        if (min(wordsA.size, wordsB.size) < SHINGLE_WORDS) {
            return tokenOverlap(a, b, wordsA, wordsB)
        }

        val shinglesA = shingles(a)
        val shinglesB = shingles(b)
        val totalA = shinglesA.values.sum()
        val totalB = shinglesB.values.sum()
        if (totalA == 0 || totalB == 0) return tokenOverlap(a, b, wordsA, wordsB)
        var shared = 0
        for ((shingle, count) in shinglesA) {
            shared += min(count, shinglesB[shingle] ?: 0)
        }
        return shared.toDouble() / max(totalA, totalB)
    }

    private fun tokenOverlap(
        textA: String,
        textB: String,
        a: List<String>,
        b: List<String>,
    ): Double {
        if (a.isEmpty() || b.isEmpty()) {
            if (a.isEmpty() && b.isEmpty()) {
                val allWordsA = words(textA)
                val allWordsB = words(textB)
                return if (allWordsA == allWordsB && allWordsA.isNotEmpty()) 1.0 else 0.0
            }
            return 0.0
        }
        val countsA = a.groupingBy { it }.eachCount()
        val countsB = b.groupingBy { it }.eachCount()
        val shared = countsA.entries.sumOf { (word, count) -> min(count, countsB[word] ?: 0) }
        return shared.toDouble() / max(a.size, b.size)
    }

    private fun shingles(text: String): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (segment in text.split(segmentSplitter)) {
            val segmentWords = words(segment)
            if (segmentWords.size < 2) continue
            val size = min(SHINGLE_WORDS, segmentWords.size)
            for (start in 0..segmentWords.size - size) {
                val shingleWords = segmentWords.subList(start, start + size)
                if (shingleWords.count { it !in commonWords } < 2) continue
                val key = shingleWords.joinToString(" ")
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return counts
    }

    /** Looser than [content]: shared name words, one name inside the other, or small edit distance. */
    fun name(a: String, b: String): Double {
        val baseA = a.substringBeforeLast('.')
        val baseB = b.substringBeforeLast('.')
        val tokensA = words(baseA).toSet()
        val tokensB = words(baseB).toSet()
        val joinedA = words(baseA).joinToString("")
        val joinedB = words(baseB).joinToString("")
        if (joinedA.isEmpty() || joinedB.isEmpty()) return 0.0

        val jaccard = tokensA.intersect(tokensB).size.toDouble() / tokensA.union(tokensB).size
        val contained = if (joinedA.contains(joinedB) || joinedB.contains(joinedA)) 1.0 else 0.0
        val edit = 1.0 - editDistance(joinedA, joinedB).toDouble() / max(joinedA.length, joinedB.length)
        return maxOf(jaccard, contained, edit)
    }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}

data class SimilarityIssue(val fileName: String, val bestMatchName: String, val score: Double)

data class SimilarityReport(
    val nameIssues: List<SimilarityIssue>,
    val contentIssues: List<SimilarityIssue>,
) {
    val hasIssues: Boolean get() = nameIssues.isNotEmpty() || contentIssues.isNotEmpty()
}

object SimilarityCheck {
    /**
     * A file passes when it resembles at least one other file in the set, so one outlier in a
     * larger set is flagged without penalizing files that match each other.
     */
    fun evaluate(
        names: List<String>,
        contents: List<String>,
        settings: SimilaritySettings,
    ): SimilarityReport {
        if (names.size < 2) return SimilarityReport(emptyList(), emptyList())
        val nameIssues = if (settings.checkNames) {
            flagOutliers(names) { i, j -> Similarity.name(names[i], names[j]) }
                .filter { it.score < Similarity.NAME_THRESHOLD }
        } else {
            emptyList()
        }
        val contentIssues = if (settings.checkContent) {
            flagOutliers(names) { i, j -> Similarity.content(contents[i], contents[j]) }
                .filter { it.score < Similarity.CONTENT_THRESHOLD }
        } else {
            emptyList()
        }
        return SimilarityReport(nameIssues, contentIssues)
    }

    private fun flagOutliers(names: List<String>, score: (Int, Int) -> Double): List<SimilarityIssue> =
        names.indices.map { i ->
            val best = names.indices.filter { it != i }.maxBy { score(i, it) }
            SimilarityIssue(names[i], names[best], score(i, best))
        }
}
