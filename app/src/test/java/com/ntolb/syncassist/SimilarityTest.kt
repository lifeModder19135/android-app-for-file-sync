package com.ntolb.syncassist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarityTest {
    private val paragraphA =
        "The quick brown fox jumps over the lazy dog near the river bank every single morning."
    private val paragraphB =
        "Meanwhile the committee reviewed the annual budget and approved several new community projects."
    private val paragraphC =
        "A small boat drifted slowly across the harbour while the fishermen mended their nets."

    @Test
    fun identicalTextIsFullMatch() {
        assertEquals(1.0, Similarity.content("$paragraphA $paragraphB", "$paragraphA $paragraphB"), 0.0)
    }

    @Test
    fun movedParagraphsStillMatch() {
        val original = "$paragraphA\n\n$paragraphB\n\n$paragraphC"
        val reordered = "$paragraphC\n\n$paragraphA\n\n$paragraphB"
        assertTrue(Similarity.content(original, reordered) >= 0.9)
    }

    @Test
    fun caseAndPunctuationDoNotMatter() {
        val comma = paragraphA.removeSuffix(".") + ","
        assertEquals(1.0, Similarity.content(paragraphA, comma.uppercase()), 0.0)
    }

    @Test
    fun unrelatedTextFailsEvenWithSharedCommonWords() {
        val theHeavy = List(35) { "the" }.joinToString(" ") + " " + paragraphA
        val other = List(35) { "the" }.joinToString(" ") + " " + paragraphB
        assertTrue(Similarity.content(theHeavy, other) < Similarity.CONTENT_THRESHOLD)
    }

    @Test
    fun mostlyEqualTextPasses() {
        val a = "$paragraphA $paragraphB $paragraphC"
        val b = "$paragraphA $paragraphB $paragraphC Extra closing remark that appears only here today."
        assertTrue(Similarity.content(a, b) >= Similarity.CONTENT_THRESHOLD)
    }

    @Test
    fun emptyFilesAreTreatedAsAlike() {
        assertEquals(1.0, Similarity.content("", paragraphA), 0.0)
    }

    @Test
    fun unrelatedSingleWordFilesDoNotPassBecauseTheyHaveNoShingles() {
        assertEquals(1.0, Similarity.content("alpha", "alpha"), 0.0)
        assertEquals(0.0, Similarity.content("alpha", "beta"), 0.0)

        val report = SimilarityCheck.evaluate(
            listOf("notes-a.txt", "notes-b.txt"),
            listOf("alpha", "beta"),
            SimilaritySettings(checkNames = false),
        )
        assertEquals(listOf("notes-a.txt", "notes-b.txt"), report.contentIssues.map { it.fileName })
    }

    @Test
    fun shortTextUsesMeaningfulWordOverlap() {
        assertEquals(0.75, Similarity.content("red blue green gold", "red blue green silver"), 0.0)
        assertEquals(0.5, Similarity.content("red blue green gold", "red blue silver bronze"), 0.0)
        assertEquals(1.0, Similarity.content("the quick fox", "THE, quick fox!"), 0.0)
        assertEquals(0.5, Similarity.content("not safe", "safe"), 0.0)
    }

    @Test
    fun stopwordsAloneDoNotCreateAShortTextMatchUnlessTheTextMatches() {
        assertEquals(1.0, Similarity.content("the", "THE"), 0.0)
        assertEquals(0.0, Similarity.content("the", "and"), 0.0)
    }

    @Test
    fun relatedNamesPass() {
        assertTrue(Similarity.name("Meeting Notes.txt", "Meeting Notes (1).md") >= Similarity.NAME_THRESHOLD)
        assertTrue(Similarity.name("report_final.txt", "Report Final v2.html") >= Similarity.NAME_THRESHOLD)
    }

    @Test
    fun unrelatedNamesFail() {
        assertTrue(Similarity.name("test-1.txt", "sync-a.txt") < Similarity.NAME_THRESHOLD)
        assertTrue(Similarity.name("budget.txt", "holiday-photos.md") < Similarity.NAME_THRESHOLD)
    }

    @Test
    fun outlierInLargerSetIsFlaggedAlone() {
        val names = listOf("notes.txt", "notes.md", "grocery-list.txt")
        val contents = listOf("$paragraphA $paragraphB", "$paragraphA $paragraphB", paragraphC)
        val report = SimilarityCheck.evaluate(names, contents, SimilaritySettings())
        assertEquals(listOf("grocery-list.txt"), report.nameIssues.map { it.fileName })
        assertEquals(listOf("grocery-list.txt"), report.contentIssues.map { it.fileName })
    }

    @Test
    fun blankFilesAreExcludedFromBothSimilarityChecks() {
        val report = SimilarityCheck.evaluate(
            listOf("notes.txt", "notes-copy.txt", "unrelated-name.pdf"),
            listOf("$paragraphA $paragraphB", "$paragraphA $paragraphB", " \n\t"),
            SimilaritySettings(),
        )

        assertFalse(report.hasIssues)
    }

    @Test
    fun aSingleTextBearingFileIsNotComparedAgainstBlankFiles() {
        val report = SimilarityCheck.evaluate(
            listOf("notes.txt", "unrelated-a.bin", "unrelated-b.doc"),
            listOf(paragraphA, "", "  "),
            SimilaritySettings(),
        )

        assertFalse(report.hasIssues)
    }

    @Test
    fun oneFileCannotProduceSimilarityIssues() {
        val report = SimilarityCheck.evaluate(
            listOf("notes.txt"),
            listOf(paragraphA),
            SimilaritySettings(),
        )

        assertFalse(report.hasIssues)
    }

    @Test
    fun similarityCheckRequiresOneContentForEachName() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SimilarityCheck.evaluate(
                listOf("notes.txt"),
                emptyList(),
                SimilaritySettings(),
            )
        }
    }

    @Test
    fun disabledChecksProduceNoIssues() {
        val report = SimilarityCheck.evaluate(
            listOf("a.txt", "zzz.txt"),
            listOf(paragraphA, paragraphB),
            SimilaritySettings(checkNames = false, checkContent = false),
        )
        assertFalse(report.hasIssues)
    }
}
