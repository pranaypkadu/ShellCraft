package com.pranay.util;

public final class Strings {
    private Strings() { }

    public static String repeat(String s, int count) {
        if (count <= 0) return "";
        StringBuilder sb = new StringBuilder(s.length() * count);
        for (int i = 0; i < count; i++) sb.append(s);
        return sb.toString();
    }
}