package com.termux.terminal;

import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class TerminalRowTest {
    @Test
    public void combiningCharactersHaveABoundedCellStorage() {
        TerminalRow row = new TerminalRow(2, TextStyle.NORMAL);
        row.setChar(0, 'A', TextStyle.NORMAL);

        for (int i = 0; i < 10_000; i++) {
            row.setChar(0, 0x0301, TextStyle.NORMAL);
        }

        // The second column still occupies one char; the first cell keeps its base plus the cap.
        assertTrue(row.getSpaceUsed() <= 2 + 16);
        assertTrue(row.mText.length <= 2 + 16 + 2);
    }

    @Test
    public void replacingACappedCellCanStillShrinkBackToNormalSize() {
        TerminalRow row = new TerminalRow(2, TextStyle.NORMAL);
        row.setChar(0, 'A', TextStyle.NORMAL);
        for (int i = 0; i < 100; i++) row.setChar(0, 0x0301, TextStyle.NORMAL);

        row.setChar(0, 'B', TextStyle.NORMAL);

        assertTrue(row.getSpaceUsed() <= 2);
        assertTrue(row.mText[0] == 'B');
    }
}
