package com.pranay.parser.model;

import com.pranay.util.Lists;

import java.util.List;

public final class CmdLine {
    private final List<String> args;
    private final Redirs redirs;

    public CmdLine(List<String> args, Redirs redirs) {
        this.args = Lists.copyOfNoNulls(args);
        this.redirs = redirs;
    }

    public List<String> args() { return args; }
    public Redirs redirs() { return redirs; }
}