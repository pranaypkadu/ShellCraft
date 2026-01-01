package com.pranay.exec;

import com.pranay.parser.model.Redirs;
import com.pranay.util.Lists;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.util.List;

public final class Ctx {
    private final List<String> args;
    private final Redirs redirs;
    private final InputStream in;
    private final PrintStream out;
    private final PrintStream err;

    public Ctx(List<String> args, Redirs redirs, InputStream in, PrintStream out, PrintStream err) {
        this.args = Lists.copyOfNoNulls(args);
        this.redirs = redirs;
        this.in = in;
        this.out = out;
        this.err = err;
    }

    public static Ctx system() {
        return new Ctx(Collections.<String>emptyList(), Redirs.none(), System.in, System.out, System.err);
    }

    public Ctx with(List<String> a, Redirs r) {
        return new Ctx(a, r, in, out, err);
    }

    public List<String> args() { return args; }
    public Redirs redirs() { return redirs; }
    public InputStream in() { return in; }
    public PrintStream out() { return out; }
    public PrintStream err() { return err; }
}