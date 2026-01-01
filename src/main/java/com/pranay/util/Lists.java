package com.pranay.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public final class Lists {
    private Lists() { }

    public static <T> List<T> copyOfNoNulls(Collection<? extends T> src) {
        if (src == null) throw new NullPointerException();
        ArrayList<T> out = new ArrayList<T>(src.size());
        for (T t : src) {
            if (t == null) throw new NullPointerException();
            out.add(t);
        }
        return Collections.unmodifiableList(out);
    }

    public static <T> List<T> copyOfNoNulls(List<? extends T> src) {
        if (src == null) throw new NullPointerException();
        ArrayList<T> out = new ArrayList<T>(src.size());
        for (int i = 0; i < src.size(); i++) {
            T t = src.get(i);
            if (t == null) throw new NullPointerException();
            out.add(t);
        }
        return Collections.unmodifiableList(out);
    }
}