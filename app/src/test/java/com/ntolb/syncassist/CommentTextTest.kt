package com.ntolb.syncassist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentTextTest {
    @Test
    fun disabledCommentsAreTreatedAsOrdinaryText() {
        val raw = "before !!!>private note<!!! after"

        val parsed = CommentText.parse(raw, enabled = false)

        assertEquals(raw, parsed.syncText)
        assertTrue(parsed.comments.isEmpty())
    }

    @Test
    fun removesCommentContentsButKeepsSurroundingWhitespaceInSyncText() {
        val parsed = CommentText.parse("before \n!!!>private note<!!!\n after")

        assertEquals("before\nafter", parsed.syncText)
        assertEquals(1, parsed.comments.size)
        assertEquals("!!!>private note<!!!", parsed.comments.single().raw)
    }

    @Test
    fun changingOnlyCommentTextDoesNotChangeSyncText() {
        val first = CommentText.parse("shared text !!!>first note<!!! stays here")
        val second = CommentText.parse("shared text !!!>different note<!!! stays here")

        assertEquals(first.syncText, second.syncText)
    }

    @Test
    fun commentsAndTheirSpacingDoNotCreateASyncTextDifference() {
        val parsed = CommentText.parse("text before !!!>private<!!! text after")

        assertEquals("text before text after", parsed.syncText)
    }

    @Test
    fun commentAtBeginningDoesNotAddLeadingSyncWhitespace() {
        val parsed = CommentText.parse("!!!>private<!!! text")

        assertEquals("text", parsed.syncText)
    }

    @Test
    fun commentAtEndDoesNotAddTrailingSyncWhitespace() {
        val parsed = CommentText.parse("text !!!>private<!!!")

        assertEquals("text", parsed.syncText)
    }

    @Test
    fun unchangedSyncTextRestoresTheOriginalCommentAndSpacing() {
        val raw = "before \n!!!>private note<!!!\n after"
        val parsed = CommentText.parse(raw)

        assertEquals(raw, CommentText.preserveComments(parsed.syncText, parsed.comments))
    }

    @Test
    fun restoresInlineCommentAtItsFollowingTextAnchor() {
        val parsed = CommentText.parse("old !!!>keep this<!!! text")

        assertEquals(
            "new !!!>keep this<!!! text",
            CommentText.preserveComments("new text", parsed.comments),
        )
    }

    @Test
    fun restoresBlockCommentAtItsFollowingTextAnchor() {
        val parsed = CommentText.parse("old line\n!!!>keep this<!!!\nanchor text")

        assertEquals(
            "new line\n!!!>keep this<!!!\nanchor text",
            CommentText.preserveComments("new line\nanchor text", parsed.comments),
        )
    }

    @Test
    fun blockCommentSkipsWhitespaceWhenFindingItsAnchor() {
        val parsed = CommentText.parse("old\n!!!>keep this<!!!\n\n\tanchor")

        assertEquals(
            "new\n!!!>keep this<!!!\n\n\tanchor",
            CommentText.preserveComments("new\n\n\tanchor", parsed.comments),
        )
    }

    @Test
    fun keepsCommentsWhenTheirOriginalAnchorIsGone() {
        val parsed = CommentText.parse("before !!!>keep this<!!! anchor")

        assertEquals(
            "replacement text !!!>keep this<!!! ",
            CommentText.preserveComments("replacement text", parsed.comments),
        )
    }

    @Test
    fun preservesMultipleCommentsAtTheirRespectiveAnchors() {
        val parsed = CommentText.parse(
            "old !!!>first<!!! middle !!!>second<!!! end",
        )

        assertEquals(
            "new !!!>first<!!! middle !!!>second<!!! end",
            CommentText.preserveComments("new middle end", parsed.comments),
        )
    }

    @Test
    fun fileContainingOnlyACommentHasNoSyncText() {
        val parsed = CommentText.parse(" \n!!!>private<!!!\n\t")

        assertTrue(parsed.syncText.isBlank())
        assertEquals(
            "text \n!!!>private<!!!\n\t",
            CommentText.preserveComments("text", parsed.comments),
        )
    }

    @Test
    fun unmatchedCommentMarkersAreRejected() {
        assertThrows(InvalidCommentMarkersException::class.java) {
            CommentText.parse("visible !!!>unfinished")
        }
        assertThrows(InvalidCommentMarkersException::class.java) {
            CommentText.parse("visible <!!!")
        }
    }

    @Test
    fun nestedCommentStartsAreRejected() {
        assertThrows(InvalidCommentMarkersException::class.java) {
            CommentText.parse("!!!>outer !!!>inner<!!!")
        }
    }
}
