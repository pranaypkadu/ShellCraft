package com.pranay.input;

import com.pranay.state.RuntimeState;
import com.pranay.util.Strings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.List;

public final class InteractiveInput implements AutoCloseable {
    private static final char BEL = '\u0007';
    private static final int ESC = 27;

    private final InputStream in;
    private final CompletionEngine completer;
    private final TerminalMode tty = new TerminalMode();
    private final String prompt;

    private BufferedReader cooked;
    private boolean rawEnabled;

    // TAB completion state
    private int tabs;
    private String snap;
    private List<String> amb = Collections.emptyList();

    // History navigation state
    private int historyPos = -1;          // -1 = not browsing
    private String historySavedLine = ""; // line buffer before browsing

    public InteractiveInput(InputStream in, CompletionEngine completer, String prompt) {
        this.in = in;
        this.completer = completer;
        this.prompt = prompt;
        try { rawEnabled = tty.enableRawMode(); } catch (Exception ignored) { rawEnabled = false; }
    }

    public String readLine() throws IOException {
        if (!rawEnabled || System.console() == null) {
            // Non-interactive mode: do not echo input, do not interpret escape sequences.
            if (cooked == null) cooked = new BufferedReader(new InputStreamReader(in));
            return cooked.readLine();
        }

        StringBuilder buf = new StringBuilder();

        while (true) {
            int b = in.read();
            if (b == -1) {
                if (buf.length() > 0) {
                    System.out.print("\n");
                    completionReset();
                    historyReset();
                    return buf.toString();
                }
                return null;
            }

            if (b == ESC) {
                if (handleEscapeSequence(buf)) continue;
                completionReset();
                continue;
            }
            char c = (char) b;

            if (c == '\n') {
                System.out.print("\n");
                completionReset();
                historyReset();
                return buf.toString();
            }

            if (c == '\r') {
                if (in.markSupported()) {
                    in.mark(1);
                    int n = in.read();
                    if (n != '\n' && n != -1) in.reset();
                }
                System.out.print("\n");
                completionReset();
                historyReset();
                return buf.toString();
            }

            if (c == '\t') {
                onTab(buf);
                continue;
            }

            if (c == 127 || c == 8) { // backspace
                if (buf.length() > 0) {
                    buf.setLength(buf.length() - 1);
                    System.out.print("\b \b");
                }
                completionReset();
                historyAbortBrowsing();
                continue;
            }

            if (c >= 32) {
                buf.append(c);
                System.out.print(c);
                completionReset();
                historyAbortBrowsing();
            } else {
                completionReset();
            }
        }
    }

    private boolean handleEscapeSequence(StringBuilder buf) throws IOException {
        int b2 = in.read();
        if (b2 == -1) return true;

        if (b2 != '[') return true;

        int b3 = in.read();
        if (b3 == -1) return true;

        if (b3 == 'A') { // UP
            onHistoryUp(buf);
            return true;
        }
        if (b3 == 'B') { // DOWN
            onHistoryDown(buf);
            return true;
        }
        return true;
    }

    private void onHistoryUp(StringBuilder buf) {
        List<String> snap = RuntimeState.history.snapshot();
        if (snap.isEmpty()) { bell(); return; }

        if (historyPos == -1) {
            historySavedLine = buf.toString();
            historyPos = snap.size(); // one-past-end
        }
        if (historyPos <= 0) { bell(); return; }

        historyPos--;
        replaceBufferAndRedraw(buf, snap.get(historyPos));
    }

    private void onHistoryDown(StringBuilder buf) {
        if (historyPos == -1) { bell(); return; }

        List<String> snap = RuntimeState.history.snapshot();
        if (historyPos >= snap.size() - 1) {
            historyPos = -1;
            replaceBufferAndRedraw(buf, historySavedLine);
            return;
        }

        historyPos++;
        replaceBufferAndRedraw(buf, snap.get(historyPos));
    }

    private void replaceBufferAndRedraw(StringBuilder buf, String next) {
        int oldLen = buf.length();
        buf.setLength(0);
        buf.append(next);

        System.out.print("\r");
        System.out.print(prompt);
        System.out.print(next);

        int extra = oldLen - next.length();
        if (extra > 0) {
            System.out.print(Strings.repeat(" ", extra));
            System.out.print("\r");
            System.out.print(prompt);
            System.out.print(next);
        }
        System.out.flush();
        completionReset();
    }

    private void historyAbortBrowsing() {
        if (historyPos != -1) {
            historyPos = -1;
            historySavedLine = "";
        }
    }

    private void historyReset() {
        historyPos = -1;
        historySavedLine = "";
    }

    private void onTab(StringBuilder buf) {
        if (buf.length() == 0) return;
        for (int i = 0; i < buf.length(); i++) {
            if (Character.isWhitespace(buf.charAt(i))) return;
        }

        String cur = buf.toString();
        Completion r = completer.completeFirstWord(cur);

        if (r.kind() == Completion.Kind.SUFFIX) {
            buf.append(r.suffix());
            System.out.print(r.suffix());
            completionReset();
            historyAbortBrowsing();
            return;
        }

        if (r.kind() == Completion.Kind.NO_MATCH) {
            bell();
            completionReset();
            return;
        }

        if (r.kind() == Completion.Kind.AMBIGUOUS) {
            if (tabs == 0 || snap == null || !snap.equals(cur)) {
                bell();
                tabs = 1;
                snap = cur;
                amb = r.matches();
                return;
            }
            if (tabs == 1 && snap.equals(cur)) {
                System.out.print("\n");
                if (!amb.isEmpty()) System.out.print(String.join("  ", amb));
                System.out.print("\n");
                System.out.print(prompt);
                System.out.print(cur);
                System.out.flush();
                completionReset();
                return;
            }
        }

        completionReset();
    }

    private void bell() {
        System.out.print(BEL);
        System.out.flush();
    }

    private void completionReset() {
        tabs = 0;
        snap = null;
        amb = Collections.emptyList();
    }

    @Override
    public void close() {
        try { if (rawEnabled) tty.disableRawMode(); } catch (Exception ignored) { }
    }
}