package com.pranay.state;

import com.pranay.env.Env;
import com.pranay.history.HistoryStore;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class RuntimeState {
    private RuntimeState() { }

    public static final Env env = new Env();
    public static final HistoryStore history = new HistoryStore();

    // Per-history-file cursor for "history -a <path>"
    public static final Map<Path, Integer> historyAppendCursor = new HashMap<Path, Integer>();

    // Cursor for HISTFILE append-on-exit (avoid duplicating loaded history).
    public static int histfileSessionStartIndex = 0;
}