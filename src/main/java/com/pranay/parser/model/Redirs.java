package com.pranay.parser.model;

import java.util.Optional;

public final class Redirs {
    private final Optional<RSpec> out;
    private final Optional<RSpec> err;

    public Redirs(Optional<RSpec> out, Optional<RSpec> err) {
        this.out = out;
        this.err = err;
    }

    public static Redirs none() {
        return new Redirs(Optional.<RSpec>empty(), Optional.<RSpec>empty());
    }

    public Optional<RSpec> out() { return out; }
    public Optional<RSpec> err() { return err; }
}