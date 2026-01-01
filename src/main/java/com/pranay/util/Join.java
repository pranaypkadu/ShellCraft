package com.pranay.util;

import java.util.Iterator;

public final class Join {
    private Join() { }

    public static String join(String sep, Iterable<String> it) {
        StringBuilder sb = new StringBuilder();
        Iterator<String> iter = it.iterator();
        boolean first = true;
        while (iter.hasNext()) {
            if (!first) sb.append(sep);
            first = false;
            sb.append(iter.next());
        }
        return sb.toString();
    }
}