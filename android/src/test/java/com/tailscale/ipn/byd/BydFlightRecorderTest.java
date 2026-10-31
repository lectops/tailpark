// Copyright (c) Tailpark contributors
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.byd;

import static org.junit.Assert.*;
import com.tailscale.ipn.byd.BydFlightRecorder.Code;
import com.tailscale.ipn.byd.BydFlightRecorder.Event;
import com.tailscale.ipn.byd.BydFlightRecorder.Source;
import com.tailscale.ipn.byd.BydFlightRecorder.Value;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BydFlightRecorderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private BydFlightRecorder create(File file, Source source, long boot, long wall, long elapsed)
            throws Exception {
        return new BydFlightRecorder(file, source, "1.104.4-byd.6", boot, wall, elapsed);
    }

    @Test public void steadyStateDoesNotWriteAnotherRecord() throws Exception {
        File file = new File(temporary.getRoot(), "events.log");
        BydFlightRecorder recorder = create(file, Source.HELPER, 7, 1000, 100);
        recorder.observe(Code.ACC, Value.OFF, 1010, 110);
        long size = file.length();
        for (int i = 0; i < 10000; i++) recorder.observe(Code.ACC, Value.OFF, 1020+i, 120+i);
        assertEquals(size, file.length());
        assertEquals(2, recorder.snapshot().size());
        recorder.observe(Code.ACC, Value.ON, 12000, 11000);
        assertEquals(3, recorder.snapshot().size());
    }

    @Test public void rotatingHistoryAndPagesRemainBounded() throws Exception {
        File file = new File(temporary.getRoot(), "events.log");
        BydFlightRecorder recorder = create(file, Source.HELPER, 7, 1000, 100);
        for (int i = 0; i < 2000; i++) recorder.record(Code.RECOVERY, Value.ATTEMPT, 1010+i, 110+i);
        assertTrue(file.length() <= BydFlightRecorder.MAX_BYTES);
        assertTrue(recorder.snapshot().size() <= BydFlightRecorder.MAX_EVENTS);
        assertTrue(recorder.snapshot().get(0).sequence > 1);
        List<Event> page = recorder.after(0, 10);
        assertEquals(10, page.size());
        assertTrue(page.stream().mapToInt(e -> e.encode().length() + 4).sum() < 3500);
        assertEquals(page.get(9).sequence+1, recorder.after(page.get(9).sequence, 10).get(0).sequence);
        assertEquals(2001, recorder.lastSequence());
    }

    @Test public void abruptRestartPreservesOldSessionAndSequence() throws Exception {
        File file = new File(temporary.getRoot(), "events.log");
        BydFlightRecorder first = create(file, Source.HELPER, 7, 1000, 100);
        first.record(Code.RECOVERY, Value.ATTEMPT, 1100, 200);
        String firstSession = first.snapshot().get(0).session;
        BydFlightRecorder second = create(file, Source.HELPER, 7, 1200, 300);
        assertEquals(3, second.snapshot().size());
        assertNotEquals(firstSession, second.snapshot().get(2).session);
        assertEquals(3, second.lastSequence());
        assertEquals(first.journalId(), second.journalId());
        assertFalse(second.snapshot().stream().anyMatch(e -> e.code == Code.BOOT_OBSERVED));
        assertFalse(second.snapshot().stream().anyMatch(e -> e.code == Code.SESSION_STOPPED));
    }

    @Test public void onlyChangedKnownBootCountEstablishesAnotherBoot() throws Exception {
        File file = new File(temporary.getRoot(), "events.log");
        create(file, Source.HELPER, 7, 1000, 100);
        BydFlightRecorder next = create(file, Source.HELPER, 8, 1500, 10);
        assertEquals(Code.BOOT_OBSERVED, next.snapshot().get(1).code);
        File unknown = new File(temporary.getRoot(), "unknown.log");
        create(unknown, Source.APP, -1, 1000, 100);
        assertFalse(create(unknown, Source.APP, -1, 900, 10).snapshot().stream()
                .anyMatch(e -> e.code == Code.BOOT_OBSERVED));
    }

    @Test public void tornAppendIsDiscardedBeforeWritingNewSession() throws Exception {
        File file = new File(temporary.getRoot(), "events.log");
        create(file, Source.HELPER, 7, 1000, 100);
        Files.write(file.toPath(), "2|partial".getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.APPEND);
        BydFlightRecorder resumed = create(file, Source.HELPER, 7, 1200, 300);
        assertEquals(2, resumed.snapshot().size());
        for (String line : Files.readAllLines(file.toPath())) assertNotNull(Event.parse(line));
    }

    @Test public void corruptOversizedFileDoesNotAllocateUnboundedHistory() throws Exception {
        File file = new File(temporary.getRoot(), "events.log");
        Files.write(file.toPath(), new byte[BydFlightRecorder.MAX_BYTES * 2]);
        BydFlightRecorder recorder = create(file, Source.APP, 7, 1000, 100);
        assertEquals(1, recorder.snapshot().size());
        assertTrue(file.length() <= BydFlightRecorder.MAX_BYTES);
    }

    @Test public void helperImportsAreIdempotentAndKeepTheirOwnTiming() throws Exception {
        BydFlightRecorder helper = create(new File(temporary.getRoot(), "helper.log"), Source.HELPER, 7, 1000, 100);
        helper.record(Code.NETWORK, Value.NO_NETWORK, 900, 200); // Wall clock moved backwards.
        BydFlightRecorder app = create(new File(temporary.getRoot(), "app.log"), Source.APP, 7, 1200, 300);
        for (Event event : helper.snapshot()) { app.importEvent(event); app.importEvent(event); }
        assertEquals(3, app.snapshot().size());
        Event imported = app.snapshot().get(2);
        assertEquals(900, imported.wallMs);
        assertEquals(200, imported.elapsedMs);
        assertEquals(Source.HELPER, imported.source);
    }

    @Test public void clearKeepsCursorMonotonicAndChangesSession() throws Exception {
        File file = new File(temporary.getRoot(), "events.log");
        BydFlightRecorder recorder = create(file, Source.HELPER, 7, 1000, 100);
        String previousSession = recorder.snapshot().get(0).session;
        long cursor = recorder.lastSequence();
        recorder.clear(1100, 200);
        assertEquals(1, recorder.snapshot().size());
        assertEquals(cursor+1, recorder.lastSequence());
        assertNotEquals(previousSession, recorder.snapshot().get(0).session);
        assertEquals(Value.HISTORY_CLEARED, recorder.snapshot().get(0).value);
        assertEquals(cursor+1, recorder.after(cursor, 10).get(0).sequence);
        assertEquals(cursor+2, create(file, Source.HELPER, 7, 1200, 300).lastSequence());
        assertEquals(recorder.journalId(), create(file, Source.HELPER, 7, 1300, 400).journalId());
    }

    @Test public void removedHelperDirectoryHasDetectableCursorReset() throws Exception {
        File file = new File(temporary.getRoot(), "events.log");
        BydFlightRecorder recorder = create(file, Source.HELPER, 7, 1000, 100);
        recorder.record(Code.RECOVERY, Value.ATTEMPT, 1100, 200);
        long oldCursor = recorder.lastSequence();
        String oldIdentity = recorder.snapshot().get(0).identity();
        Files.delete(file.toPath());
        BydFlightRecorder recreated = create(file, Source.HELPER, 7, 1200, 300);
        assertTrue(recreated.lastSequence() < oldCursor);
        assertNotEquals(recorder.journalId(), recreated.journalId());
        assertNotEquals(oldIdentity, recreated.after(0, 10).get(0).identity());
    }

    @Test public void importedStringsCannotIntroduceSecretsOrUnrecognizedFields() throws Exception {
        BydFlightRecorder helper = create(new File(temporary.getRoot(), "helper.log"), Source.HELPER, 7, 1000, 100);
        String valid = helper.snapshot().get(0).encode();
        assertNotNull(Event.parse(valid));
        assertNull(Event.parse(valid + "|helper_token=secret"));
        assertNull(Event.parse(valid.replace("STARTED|", "100.64.1.2|")));
        assertNull(Event.parse(valid.replace("1.104.4-byd.6", "token=secret")));
        assertNull(Event.parse("x".repeat(300)));
        assertNull(Event.parse(valid.replace("|100|7|", "|-1|7|")));
    }
}
