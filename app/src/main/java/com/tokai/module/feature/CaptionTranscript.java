package com.tokai.module.feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Immutable caption timeline shared by recognition and playback. */
final class CaptionTranscript {
    private static final int MAX_TEXT_LENGTH = 2000;
    private final List<Cue> cues;
    private final long endMillis;

    static final class Cue {
        final long startMillis;
        final long endMillis;
        final String text;

        Cue(long startMillis, long endMillis, String text) {
            if (startMillis < 0 || endMillis <= startMillis || text == null
                    || text.trim().isEmpty() || text.length() > MAX_TEXT_LENGTH) {
                throw new IllegalArgumentException("Invalid caption cue");
            }
            this.startMillis = startMillis;
            this.endMillis = endMillis;
            this.text = text;
        }
    }

    CaptionTranscript(List<Cue> cues) {
        if (cues == null || cues.size() > 5000) {
            throw new IllegalArgumentException("Invalid caption track");
        }
        List<Cue> sorted = new ArrayList<>(cues);
        for (Cue cue : sorted) {
            if (cue == null) throw new IllegalArgumentException("Missing caption cue");
        }
        sorted.sort(Comparator.comparingLong((Cue cue) -> cue.startMillis)
                .thenComparingLong(cue -> cue.endMillis).thenComparing(cue -> cue.text));
        List<Cue> unique = new ArrayList<>(sorted.size());
        Cue previous = null;
        long lastEnd = 0;
        for (Cue cue : sorted) {
            if (previous != null && previous.startMillis == cue.startMillis
                    && previous.endMillis == cue.endMillis && previous.text.equals(cue.text)) continue;
            unique.add(cue);
            previous = cue;
            lastEnd = Math.max(lastEnd, cue.endMillis);
        }
        this.cues = Collections.unmodifiableList(unique);
        this.endMillis = lastEnd;
    }

    List<Cue> cues() {
        return cues;
    }

    String textAt(long positionMillis) {
        StringBuilder active = new StringBuilder();
        for (Cue cue : cues) {
            if (cue.startMillis > positionMillis) break;
            if (cue.endMillis <= positionMillis) continue;
            if (active.length() > 0) active.append('\n');
            active.append(cue.text);
            int newline = active.length();
            for (int line = 0; line < 3; line++) {
                newline = active.lastIndexOf("\n", newline - 1);
                if (newline < 0) break;
            }
            int first = Math.max(newline + 1, active.length() - MAX_TEXT_LENGTH);
            if (first > 0) active.delete(0, first);
        }
        return active.toString();
    }

    long endMillis() {
        return endMillis;
    }
}
