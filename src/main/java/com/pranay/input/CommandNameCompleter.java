package com.pranay.input;

import com.pranay.command.BuiltinRegistry;
import com.pranay.env.PathResolver;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

public final class CommandNameCompleter implements CompletionEngine {
    private final BuiltinRegistry builtins;
    private final PathResolver resolver;

    public CommandNameCompleter(BuiltinRegistry builtins, PathResolver resolver) {
        this.builtins = builtins;
        this.resolver = resolver;
    }

    @Override
    public Completion completeFirstWord(String cur) {
        if (cur == null || cur.isEmpty()) return Completion.of(Completion.Kind.NOT_APPLICABLE);

        Set<String> matches = new LinkedHashSet<String>();
        for (String b : builtins.names()) if (b.startsWith(cur)) matches.add(b);
        matches.addAll(resolver.execNamesByPrefix(cur));

        if (matches.isEmpty()) return Completion.of(Completion.Kind.NO_MATCH);

        if (matches.size() == 1) {
            String only = matches.iterator().next();
            if (only.equals(cur)) return Completion.of(Completion.Kind.ALREADY_COMPLETE);
            return Completion.suffix(only.substring(cur.length()) + " ");
        }

        TreeSet<String> sorted = new TreeSet<String>(matches);
        String lcp = lcp(sorted);
        if (lcp.length() > cur.length()) return Completion.suffix(lcp.substring(cur.length()));
        return Completion.amb(new ArrayList<String>(sorted));
    }

    private static String lcp(Iterable<String> it) {
        String first = null;
        for (String s : it) { first = s; break; }
        if (first == null || first.isEmpty()) return "";
        int end = first.length();
        for (String s : it) {
            int max = Math.min(end, s.length());
            int i = 0;
            while (i < max && first.charAt(i) == s.charAt(i)) i++;
            end = i;
            if (end == 0) return "";
        }
        return first.substring(0, end);
    }
}