package com.pranay.builtin;

import com.pranay.command.Builtin;
import com.pranay.exec.Ctx;
import com.pranay.history.HistoryStore;
import com.pranay.state.RuntimeState;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.OptionalInt;

public final class History extends Builtin {
    @Override
    protected void run(Ctx ctx, PrintStream out) {
        List<String> argv = ctx.args();

        // history -r <path>  => append file lines into history, no output on success.
        if (argv.size() == 3 && "-r".equals(argv.get(1))) {
            String token = argv.get(2);
            Path p = Paths.get(token);
            if (!p.isAbsolute()) p = RuntimeState.env.cwd().resolve(p).normalize();

            try (BufferedReader br = Files.newBufferedReader(p)) {
                String line;
                while ((line = br.readLine()) != null) {
                    // Match the stage example: ignore empty lines in the history file.
                    if (!line.isEmpty()) RuntimeState.history.add(line);
                }
            } catch (IOException ignored) {
                // Intentionally no output.
            }
            return;
        }

        // history -w <path>  => write in-memory history to file, no output on success.
        if (argv.size() == 3 && "-w".equals(argv.get(1))) {
            String token = argv.get(2);

            Path p = Paths.get(token);
            if (!p.isAbsolute()) p = RuntimeState.env.cwd().resolve(p).normalize();

            try (BufferedWriter bw = Files.newBufferedWriter(
                    p, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                for (String line : RuntimeState.history.snapshot()) {
                    bw.write(line);
                    bw.newLine();
                }
            } catch (IOException ignored) {
                // Intentionally no output.
            }
            return;
        }

        // history -a <path>  => append only new in-memory history since last -a for this path.
        if (argv.size() == 3 && "-a".equals(argv.get(1))) {
            String token = argv.get(2);

            Path p = Paths.get(token);
            if (!p.isAbsolute()) p = RuntimeState.env.cwd().resolve(p);
            Path key = p.toAbsolutePath().normalize();

            int total = RuntimeState.history.size();
            int start = 0;
            Integer v = RuntimeState.historyAppendCursor.get(key);
            if (v != null) start = v.intValue();
            if (start < 0 || start > total) start = 0;

            if (start < total) {
                try (BufferedWriter bw = Files.newBufferedWriter(
                        key, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
                    for (int i = start; i < total; i++) {
                        bw.write(RuntimeState.history.get(i));
                        bw.newLine();
                    }
                } catch (IOException ignored) { }
            }

            RuntimeState.historyAppendCursor.put(key, Integer.valueOf(total));
            return;
        }

        // Existing behavior: history [N]
        OptionalInt limit = parseLimit(argv);
        HistoryStore.View v = RuntimeState.history.view(limit);
        List<String> list = v.lines();
        int baseIndex = v.startIndex(); // 0-based in the full history
        for (int i = 0; i < list.size(); i++) {
            out.printf("%5d  %s%n", baseIndex + i + 1, list.get(i));
        }
    }

    private static OptionalInt parseLimit(List<String> argv) {
        if (argv.size() != 2) return OptionalInt.empty();
        try {
            return OptionalInt.of(Integer.parseInt(argv.get(1)));
        } catch (NumberFormatException ignored) {
            return OptionalInt.empty();
        }
    }
}