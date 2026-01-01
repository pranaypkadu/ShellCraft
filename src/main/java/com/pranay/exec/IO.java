
package com.pranay.exec;

import com.pranay.parser.model.RMode;
import com.pranay.parser.model.RSpec;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

public final class IO {
    private IO() { }

    public static void touch(RSpec s) throws IOException {
        if (s.mode() == RMode.APPEND) {
            OutputStream os = Files.newOutputStream(
                    s.path(), StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
            try {
                // touch
            } finally {
                os.close();
            }
        } else {
            OutputStream os = Files.newOutputStream(
                    s.path(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                // touch
            } finally {
                os.close();
            }
        }
    }

    public static OutTarget out(Optional<RSpec> redir, final PrintStream def) throws IOException {
        if (!redir.isPresent()) {
            return new OutTarget() {
                @Override public PrintStream ps() { return def; }
                @Override public void close() { }
            };
        }

        RSpec s = redir.get();
        OutputStream os = (s.mode() == RMode.APPEND)
                ? Files.newOutputStream(s.path(), StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)
                : Files.newOutputStream(s.path(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

        final PrintStream ps = new PrintStream(os);
        return new OutTarget() {
            @Override public PrintStream ps() { return ps; }
            @Override public void close() { ps.close(); }
        };
    }
}