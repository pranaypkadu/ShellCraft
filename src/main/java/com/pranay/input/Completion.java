package com.pranay.input;

import com.pranay.util.Lists;

import java.util.Collections;
import java.util.List;

public final class Completion {
    public enum Kind { SUFFIX, NO_MATCH, AMBIGUOUS, ALREADY_COMPLETE, NOT_APPLICABLE }

    private final Kind kind;
    private final String suffix;
    private final List<String> matches;

    public Completion(Kind kind, String suffix, List<String> matches) {
        this.kind = kind;
        this.suffix = suffix;
        this.matches = (matches == null) ? Collections.<String>emptyList() : Lists.copyOfNoNulls(matches);
    }

    public Kind kind() { return kind; }
    public String suffix() { return suffix; }
    public List<String> matches() { return matches; }

    public static Completion suffix(String s) { return new Completion(Kind.SUFFIX, s, Collections.<String>emptyList()); }
    public static Completion amb(List<String> m) { return new Completion(Kind.AMBIGUOUS, null, m); }
    public static Completion of(Kind k) { return new Completion(k, null, Collections.<String>emptyList()); }
}