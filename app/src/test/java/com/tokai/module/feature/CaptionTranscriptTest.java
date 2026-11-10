package com.tokai.module.feature;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public final class CaptionTranscriptTest {
    @Test public void showsCueAtStartAndClearsAtExclusiveEndAndGaps() {
        CaptionTranscript transcript = transcript(cue(100, 200, "first"), cue(300, 400, "second"));

        assertEquals("", transcript.textAt(-1));
        assertEquals("", transcript.textAt(99));
        assertEquals("first", transcript.textAt(100));
        assertEquals("first", transcript.textAt(199));
        assertEquals("", transcript.textAt(200));
        assertEquals("", transcript.textAt(299));
        assertEquals("second", transcript.textAt(300));
        assertEquals("", transcript.textAt(400));
        assertEquals("", transcript.textAt(Long.MAX_VALUE));
    }

    @Test public void adjacentCuesNeverShowThePreviousCueAtTheNextStart() {
        CaptionTranscript transcript = transcript(cue(0, 100, "first"), cue(100, 200, "second"));

        assertEquals("first", transcript.textAt(99));
        assertEquals("second", transcript.textAt(100));
    }

    @Test public void seekingBackAndForwardUsesOnlyTheRequestedPosition() {
        CaptionTranscript transcript = transcript(cue(50, 100, "early"), cue(150, 200, "late"));

        assertEquals("late", transcript.textAt(180));
        assertEquals("early", transcript.textAt(60));
        assertEquals("", transcript.textAt(125));
        assertEquals("late", transcript.textAt(180));
        assertEquals("", transcript.textAt(500));
        assertEquals("early", transcript.textAt(50));
    }

    @Test public void overlappingCuesShowAllActiveTextAndDropOnlyExpiredCues() {
        CaptionTranscript transcript = transcript(cue(0, 500, "long"), cue(100, 200, "short"),
                cue(150, 300, "third"));

        assertEquals("long\nshort", transcript.textAt(100));
        assertEquals("long\nshort\nthird", transcript.textAt(150));
        assertEquals("long\nthird", transcript.textAt(200));
        assertEquals("long", transcript.textAt(300));
        assertEquals("", transcript.textAt(500));
        assertEquals(500, transcript.endMillis());
    }

    @Test public void overlappingTextKeepsTheLastThreeLinesWithoutRevivingExpiredText() {
        CaptionTranscript transcript = transcript(cue(0, 100, "expired"), cue(10, 400, "first"),
                cue(20, 400, "second"), cue(30, 400, "third"), cue(40, 200, "fourth"));

        assertEquals("second\nthird\nfourth", transcript.textAt(100));
        assertEquals("first\nsecond\nthird", transcript.textAt(200));
        assertEquals("", transcript.textAt(400));
    }

    @Test public void lineLimitAlsoAppliesInsideASingleCue() {
        CaptionTranscript transcript = transcript(cue(0, 100, "one\ntwo\nthree\nfour"));

        assertEquals("two\nthree\nfour", transcript.textAt(50));
    }

    @Test public void overlappingTextHasAnOverallCharacterBudget() {
        CaptionTranscript transcript = transcript(cue(0, 500, "a".repeat(1500)),
                cue(100, 400, "b".repeat(1500)));

        assertEquals("a".repeat(499) + "\n" + "b".repeat(1500), transcript.textAt(100));
        assertEquals("a".repeat(1500), transcript.textAt(400));
    }

    @Test public void sortsUnorderedInputAndRemovesOnlyIdenticalCues() {
        CaptionTranscript.Cue early = cue(10, 20, "repeat");
        CaptionTranscript.Cue late = cue(30, 40, "repeat");
        CaptionTranscript transcript = transcript(late, cue(10, 20, "repeat"), early,
                cue(15, 25, "different"));

        assertEquals(3, transcript.cues().size());
        assertEquals(10, transcript.cues().get(0).startMillis);
        assertEquals(15, transcript.cues().get(1).startMillis);
        assertEquals(30, transcript.cues().get(2).startMillis);
        assertEquals("repeat\ndifferent", transcript.textAt(15));
        assertEquals("repeat", transcript.textAt(35));
        assertEquals(40, transcript.endMillis());
    }

    @Test public void inputMutationAndReturnedListMutationCannotChangeTheTimeline() {
        List<CaptionTranscript.Cue> source = new ArrayList<>();
        source.add(cue(0, 100, "original"));
        CaptionTranscript transcript = new CaptionTranscript(source);
        source.clear();
        source.add(cue(0, 100, "replacement"));

        assertEquals("original", transcript.textAt(50));
        assertThrows(UnsupportedOperationException.class, () -> transcript.cues().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> transcript.cues().set(0, cue(0, 100, "replacement")));
        assertEquals("original", transcript.textAt(50));
    }

    @Test public void emptyTrackIsAValidSilentRecognitionResult() {
        CaptionTranscript transcript = new CaptionTranscript(Collections.emptyList());

        assertTrue(transcript.cues().isEmpty());
        assertEquals(0, transcript.endMillis());
        assertEquals("", transcript.textAt(0));
        assertEquals("", transcript.textAt(100_000));
    }

    @Test public void rejectsInvalidTimesAndEmptyOrOversizedText() {
        assertThrows(IllegalArgumentException.class, () -> cue(-1, 100, "bad"));
        assertThrows(IllegalArgumentException.class, () -> cue(0, -1, "bad"));
        assertThrows(IllegalArgumentException.class, () -> cue(100, 100, "bad"));
        assertThrows(IllegalArgumentException.class, () -> cue(101, 100, "bad"));
        assertThrows(IllegalArgumentException.class, () -> cue(0, 100, null));
        assertThrows(IllegalArgumentException.class, () -> cue(0, 100, ""));
        assertThrows(IllegalArgumentException.class, () -> cue(0, 100, " \t\n"));
        assertThrows(IllegalArgumentException.class, () -> cue(0, 100, "x".repeat(2001)));
    }

    @Test public void rejectsMissingTrackAndNullEntries() {
        assertThrows(IllegalArgumentException.class, () -> new CaptionTranscript(null));
        assertThrows(IllegalArgumentException.class,
                () -> new CaptionTranscript(Arrays.asList(cue(0, 100, "valid"), null)));
    }

    @Test public void acceptsExactCueAndTrackLimitsButRejectsAnExtraCue() {
        List<CaptionTranscript.Cue> cues = new ArrayList<>();
        for (int index = 0; index < 5000; index++) {
            cues.add(cue(index * 2L, index * 2L + 1, "cue " + index));
        }
        CaptionTranscript transcript = new CaptionTranscript(cues);

        assertEquals(5000, transcript.cues().size());
        assertEquals("cue 4999", transcript.textAt(9998));
        assertEquals("", transcript.textAt(9999));
        assertEquals(9999, transcript.endMillis());
        assertEquals("x".repeat(2000), transcript(cue(0, 1, "x".repeat(2000))).textAt(0));

        cues.add(cue(10_000, 10_001, "overflow"));
        assertThrows(IllegalArgumentException.class, () -> new CaptionTranscript(cues));
    }

    @Test public void supportsLargeTimestampsWithoutOverflow() {
        CaptionTranscript transcript = transcript(cue(Long.MAX_VALUE - 2, Long.MAX_VALUE, "late"));

        assertEquals("", transcript.textAt(0));
        assertEquals("late", transcript.textAt(Long.MAX_VALUE - 1));
        assertEquals("", transcript.textAt(Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, transcript.endMillis());
    }

    private static CaptionTranscript transcript(CaptionTranscript.Cue... cues) {
        return new CaptionTranscript(Arrays.asList(cues));
    }

    private static CaptionTranscript.Cue cue(long start, long end, String text) {
        return new CaptionTranscript.Cue(start, end, text);
    }
}
