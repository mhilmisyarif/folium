package dev.mangareader.document

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentProgressTest {

    @Test fun pdf_countsTheVisiblePage_lastPageIsComplete() {
        assertEquals(0.1f, documentProgress(DocFormat.PDF, 0, 0f, 10), 1e-6f)
        assertEquals(0.5f, documentProgress(DocFormat.PDF, 4, 0f, 10), 1e-6f)
        assertEquals(1f, documentProgress(DocFormat.PDF, 9, 0f, 10), 1e-6f)
        assertEquals(1f, documentProgress(DocFormat.PDF, 0, 0f, 1), 1e-6f) // single-page PDF
    }

    @Test fun epub_chaptersBeforePlusScrollInside() {
        assertEquals(0f, documentProgress(DocFormat.EPUB, 0, 0f, 4), 1e-6f)
        assertEquals(0.375f, documentProgress(DocFormat.EPUB, 1, 0.5f, 4), 1e-6f)
        assertEquals(1f, documentProgress(DocFormat.EPUB, 3, 1f, 4), 1e-6f)
    }

    @Test fun outOfRangeInput_isClamped() {
        assertEquals(0f, documentProgress(DocFormat.PDF, 3, 0f, 0), 1e-6f)
        assertEquals(1f, documentProgress(DocFormat.PDF, 20, 0f, 10), 1e-6f)
        assertEquals(0.25f, documentProgress(DocFormat.EPUB, 1, -2f, 4), 1e-6f)
        assertEquals(0.5f, documentProgress(DocFormat.EPUB, 1, 5f, 4), 1e-6f)
    }
}
