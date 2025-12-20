import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

interface Command {
    void execute(List<String> argv, Shell shell);
}

final class Shell {
    private static final String PATH_SPLIT_REGEX = Pattern.quote(java.io.File.pathSeparator);

    private final Map<String, Command> builtins;

    Shell(Map<String, Command> builtins) {
        this.builtins = builtins;
    }

    Command builtin(String name) {
        return builtins.get(name);
    }

    boolean isBuiltin(String name) {
        return builtins.containsKey(name);
    }

    Optional<Path> findExecutableOnPath(String name) {
        // If the user provided a path (absolute or relative), don't consult PATH.
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            try {
                Path p = Paths.get(name);
                if (Files.isRegularFile(p) && Files.isExecutable(p)) return Optional.of(p);
            } catch (InvalidPathException ignored) {}
            return Optional.empty();
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) return Optional.empty();

        String[] dirs = pathEnv.split(PATH_SPLIT_REGEX, -1);
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

    void runExternal(List<String> argv) {
        String cmd = argv.get(0);

        // Validate existence like the challenge asks, but DO NOT replace argv[0] with full path.
        if (findExecutableOnPath(cmd).isEmpty()) {
            System.out.println(cmd + ": command not found");
            return;
        }

        try {
            Process p = new ProcessBuilder(argv)
                    .inheritIO()
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

    public static void main(String[] args) throws IOException {
        Shell shell = new Shell(builtins());
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            System.out.print("$ ");
            String line = reader.readLine();
            if (line == null) return; // EOF

            List<String> argv = tokenize(line);
            if (argv.isEmpty()) continue;

            String cmd = argv.get(0);
            Command builtin = shell.builtin(cmd);

            if (builtin != null) {
                builtin.execute(argv, shell);
            } else {
                shell.runExternal(argv);
            }
        }
    }

    private static Map<String, Command> builtins() {
        Map<String, Command> m = new HashMap<>();
        m.put("exit", new Exit());
        m.put("echo", new Echo());
        m.put("type", new Type());
        m.put("pwd", new Pwd());
        return Collections.unmodifiableMap(m);
    }

    // Fast whitespace tokenization for this stage (no quoting rules yet).
    // Uses StringTokenizer directly (it already treats whitespace as delimiters). [web:32]
    private static List<String> tokenize(String line) {
        StringTokenizer st = new StringTokenizer(line);
        if (!st.hasMoreTokens()) return Collections.emptyList();

        List<String> out = new ArrayList<>();
        while (st.hasMoreTokens()) out.add(st.nextToken());
        return out;
    }

    private static final class Exit implements Command {
        @Override
        public void execute(List<String> argv, Shell shell) {
            int code = 0;
            if (argv.size() > 1) {
                try { code = Integer.parseInt(argv.get(1)); } catch (NumberFormatException ignored) {}
            }
            System.exit(code);
        }
    }

    private static final class Echo implements Command {
        @Override
        public void execute(List<String> argv, Shell shell) {
            for (int i = 1; i < argv.size(); i++) {
                if (i > 1) System.out.print(" ");
                System.out.print(argv.get(i));
            }
            System.out.println();
        }
    }

    private static final class Type implements Command {
        @Override
        public void execute(List<String> argv, Shell shell) {
            if (argv.size() < 2) {
                System.out.println("type: missing operand");
                return;
            }

            String name = argv.get(1);

            if (shell.isBuiltin(name)) {
                System.out.println(name + " is a shell builtin");
                return;
            }

            Optional<Path> p = shell.findExecutableOnPath(name);
            if (p.isPresent()) {
                System.out.println(name + " is " + p.get().toAbsolutePath());
            } else {
                System.out.println(name + ": not found");
            }
        }
    }

    private static final class Pwd implements Command {
        @Override
        public void execute(List<String> argv, Shell shell) {
            String dir = System.getProperty("user.dir"); // working directory path [web:10]
            if (dir == null || dir.isEmpty()) {
                System.out.println();
                return;
            }
            System.out.println(Paths.get(dir).toAbsolutePath().normalize());
        }
    }
}