package com.pranay.history;

import com.pranay.util.Lists;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

public final class HistoryStore {
    public static final class View {
        private final int startIndex;
        private final List<String> lines;

        public View(int startIndex, List<String> lines) {
            this.startIndex = startIndex;
            this.lines = lines;
        }

        public int startIndex() { return startIndex; }
        public List<String> lines() { return lines; }
    }

    private final List<String> entries = new ArrayList<String>();

    public void add(String line) { entries.add(line); }

    public int size() { return entries.size(); }

    public String get(int idx) { return entries.get(idx); }

    public List<String> snapshot() { return Lists.copyOfNoNulls(entries); }

    public View view(OptionalInt limitOpt) {
        int total = entries.size();
        int start = 0;
        if (limitOpt != null && limitOpt.isPresent()) {
            int n = limitOpt.getAsInt();
            if (n <= 0) return new View(total, new ArrayList<String>());
            if (n < total) start = total - n;
        }

        return new View(start, Lists.copyOfNoNulls(entries.subList(start, total)));
    }
}