package com.pranay.parser;

import java.util.ArrayList;
import java.util.List;

public final class Tokenizer {
    private Tokenizer() { }

    private enum S { D, ESC, SQ, DQ, DQESC }

    public static List<String> tokenize(String in) {
        ArrayList<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        S st = S.D;
        boolean inTok = false;

        for (int i = 0, n = in.length(); i < n; i++) {
            char c = in.charAt(i);
            switch (st) {
                case D:
                    if (Character.isWhitespace(c)) {
                        if (inTok) {
                            out.add(cur.toString());
                            cur.setLength(0);
                            inTok = false;
                        }
                    } else if (c == '\\') {
                        st = S.ESC;
                        inTok = true;
                    } else if (c == '\'') {
                        st = S.SQ;
                        inTok = true;
                    } else if (c == '"') {
                        st = S.DQ;
                        inTok = true;
                    } else {
                        cur.append(c);
                        inTok = true;
                    }
                    break;

                case ESC:
                    cur.append(c);
                    st = S.D;
                    break;

                case SQ:
                    if (c == '\'') st = S.D;
                    else cur.append(c);
                    break;

                case DQ:
                    if (c == '"') st = S.D;
                    else if (c == '\\') st = S.DQESC;
                    else cur.append(c);
                    break;

                case DQESC:
                    if (c == '\\' || c == '"') cur.append(c);
                    else {
                        cur.append('\\');
                        cur.append(c);
                    }
                    st = S.DQ;
                    break;

                default:
                    break;
            }
        }

        if (inTok) out.add(cur.toString());
        return out;
    }
}