import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

interface Command {
    void execute(String[] argv, Shell shell);
}

final class Shell {
    private final Map<String, Command> builtins;

    Shell(Map<String, Command> builtins) {
        this.builtins = builtins;
    }

    boolean isBuiltin(String name) {
        return builtins.containsKey(name);
    }

    Command builtin(String name) {
        return builtins.get(name);
    }

    // Shared PATH resolver for both: `type` and running programs
    Optional<Path> findExecutable(String name) {
        // If command contains a path separator, treat it as a path-like invocation.
        if (name.contains("/") || name.contains("\\")) {
            try {
                Path p = Paths.get(name);
                if (Files.isRegularFile(p) && Files.isExecutable(p)) return Optional.of(p);
            } catch (InvalidPathException ignored) {}
            return Optional.empty();
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) return Optional.empty();

        String[] dirs = pathEnv.split(Pattern.quote(java.io.File.pathSeparator), -1);
        for (String dir : dirs) {
            Path base;
            try {
                base = (dir == null || dir.isEmpty()) ? Paths.get(".") : Paths.get(dir);
            } catch (InvalidPathException e) {
                continue;
            }

            Path candidate = base.resolve(name);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    void runExternal(String[] argv) {
        String cmd = argv[0];
        Optional<Path> resolved = findExecutable(cmd);
        if (resolved.isEmpty()) {
            System.out.println(cmd + ": command not found");
            return;
        }

        List<String> command = new ArrayList<>(argv.length);
        command.add(resolved.get().toString());
        for (int i = 1; i < argv.length; i++) command.add(argv[i]);

        try {
            Process p = new ProcessBuilder(command)
                    .inheritIO() // child process uses same stdin/stdout/stderr as this shell
                    .start();
            p.waitFor();
        } catch (IOException e) {
            System.out.println(cmd + ": command not found");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

public class Main {

    private static Map<String, Command> builtins() {
        Map<String, Command> m = new HashMap<>();
        m.put("exit", new Exit());
        m.put("echo", new Echo());
        m.put("type", new Type());
        return Collections.unmodifiableMap(m);
    }

    public static void main(String[] args) throws IOException {
        Shell shell = new Shell(builtins());
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            System.out.print("$ ");
            String line = reader.readLine();
            if (line == null) return; // EOF

            line = line.trim();
            if (line.isEmpty()) continue;

            // Stage ip1 is fine with whitespace tokenization (no quoting required yet).
            String[] argv = line.split("\\s+");
            String cmdName = argv[0];

            Command builtin = shell.builtin(cmdName);
            if (builtin != null) {
                builtin.execute(argv, shell);
            } else {
                shell.runExternal(argv);
            }
        }
    }

    private static final class Exit implements Command {
        @Override
        public void execute(String[] argv, Shell shell) {
            int code = 0;
            if (argv.length > 1) {
                try { code = Integer.parseInt(argv[1]); } catch (NumberFormatException ignored) {}
            }
            System.exit(code);
        }
    }

    private static final class Echo implements Command {
        @Override
        public void execute(String[] argv, Shell shell) {
            // Typical shell echo: prints remaining args separated by spaces (even if none).
            for (int i = 1; i < argv.length; i++) {
                if (i > 1) System.out.print(" ");
                System.out.print(argv[i]);
            }
            System.out.println();
        }
    }

    private static final class Type implements Command {
        @Override
        public void execute(String[] argv, Shell shell) {
            if (argv.length < 2) {
                System.out.println("type: missing operand");
                return;
            }

            String name = argv[1];

            if (shell.isBuiltin(name)) {
                System.out.println(name + " is a shell builtin");
                return;
            }

            Optional<Path> p = shell.findExecutable(name);
            if (p.isPresent()) {
                System.out.println(name + " is " + p.get().toAbsolutePath());
            } else {
                System.out.println(name + ": not found");
            }
        }
    }
}