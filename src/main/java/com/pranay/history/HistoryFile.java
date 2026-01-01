package com.pranay.history;

import com.pranay.env.Env;
import com.pranay.state.RuntimeState;
import com.pranay.util.IOUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

public final class HistoryFile {
    private static final String ENV_HISTFILE = "HISTFILE";

    private HistoryFile() { }

    public static void loadOnStartup(Env env, HistoryStore store) {
        String raw = System.getenv(ENV_HISTFILE);
        if (raw == null || raw.isEmpty()) {
            RuntimeState.histfileSessionStartIndex = store.size();
            return;
        }

        Path p = Paths.get(raw);
        if (!p.isAbsolute()) p = env.cwd().resolve(p);
        p = p.toAbsolutePath().normalize();

        int before = store.size();
        try (BufferedReader br = Files.newBufferedReader(p)) {
            String line;
            while ((line = br.readLine()) != null) {
                // Ignore empty lines as per stage example.
                if (!line.isEmpty()) store.add(line);
            }
        } catch (IOException ignored) {
            // Intentionally silent for this stage.
        } finally {
            // Everything that existed before user starts typing should not be re-appended on exit.
            RuntimeState.histfileSessionStartIndex = store.size();
            // If load failed, this is still safe: start index == current size.
            if (RuntimeState.histfileSessionStartIndex < before) RuntimeState.histfileSessionStartIndex = before;
        }
    }

    public static void appendOnExit(Env env, HistoryStore store) {
        String raw = System.getenv(ENV_HISTFILE);
        if (raw == null || raw.isEmpty()) return;

        Path p = Paths.get(raw);
        if (!p.isAbsolute()) p = env.cwd().resolve(p);
        p = p.toAbsolutePath().normalize();

        int start = RuntimeState.histfileSessionStartIndex;
        int total = store.size();
        if (start < 0 || start > total) start = 0;

        try {
            ensureTrailingNewlineIfNeeded(p);

            try (BufferedWriter bw = Files.newBufferedWriter(
                    p, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
                for (int i = start; i < total; i++) {
                    bw.write(store.get(i));
                    bw.newLine(); // ensures trailing newline after last entry
                }
            }
        } catch (IOException ignored) {
            // Intentionally silent.
        }
    }

    private static void ensureTrailingNewlineIfNeeded(Path p) throws IOException {
        if (!Files.exists(p)) return;
        long size = Files.size(p);
        if (size <= 0) return;

        try (SeekableByteChannel ch = Files.newByteChannel(p, StandardOpenOption.READ)) {
            ch.position(size - 1);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(1);
            ByteBuffer buf = ByteBuffer.allocate(1);
            int n = ch.read(buf);
            if (n == 1) {
                buf.flip();
                byte last = buf.get();
                if (last != (byte) '\n') {
                    try (OutputStream os = Files.newOutputStream(p, StandardOpenOption.APPEND)) {
                        os.write('\n');
                    }
                }
            }
            IOUtil.noop(baos);
        }
    }
}