package com.pranay.parser.model;

import java.nio.file.Path;

public final class RSpec {
    private final RStream stream;
    private final Path path;
    private final RMode mode;

    public RSpec(RStream stream, Path path, RMode mode) {
        this.stream = stream;
        this.path = path;
        this.mode = mode;
    }

    public RStream stream() { return stream; }
    public Path path() { return path; }
    public RMode mode() { return mode; }
}