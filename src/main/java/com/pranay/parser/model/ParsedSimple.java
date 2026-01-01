package com.pranay.parser.model;

public final class ParsedSimple implements Parsed {
    private final CmdLine line;

    public ParsedSimple(CmdLine line) {
        this.line = line;
    }

    public CmdLine line() {
        return line;
    }
}