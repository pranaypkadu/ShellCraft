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
    private static final char SLASH = '/';
    private static final char BACKSLASH = '\\';

    private final Map<String, Command> builtins;
    private Path currentDir;

    Shell(Map<String, Command> builtins) {
        this.builtins = Objects.requireNonNull(builtins);
        this.currentDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    Command builtin(String name) {
        return builtins.get(name);
    }

    boolean isBuiltin(String name) {
        return builtins.containsKey(name);
    }

    Optional<Path> findExecutableOnPath(String name) {
        if (containsPathSeparator(name)) {
            try {
                Path p = Paths.get(name);
                return Files.isRegularFile(p) && Files.isExecutable(p) ? Optional.of(p) : Optional.empty();
            } catch (InvalidPathException ignored) {
                return Optional.empty();
            }
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) {
            return Optional.empty();
        }

        for (String dir : pathEnv.split(PATH_SPLIT_REGEX, -1)) {
            try {
                Path candidate = (dir == null || dir.isEmpty() ? Paths.get(".") : Paths.get(dir)).resolve(name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return Optional.of(candidate);
                }
            } catch (InvalidPathException ignored) {
                // Continue to next directory
            }
        }
        return Optional.empty();
    }

    void runExternal(List<String> argv) {
        String cmd = argv.get(0);
        if (findExecutableOnPath(cmd).isEmpty()) {
            System.out.println(cmd + ": command not found");
            return;
        }

        try {
            new ProcessBuilder(argv).inheritIO().start().waitFor();
        } catch (IOException | InterruptedException e) {
            System.out.println(cmd + ": command not found");
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    Path getCurrentDir() {
        return currentDir;
    }

    void setCurrentDir(Path dir) {
        this.currentDir = Objects.requireNonNull(dir).normalize();
    }

    private static boolean containsPathSeparator(String name) {
        return indexOf(name, SLASH) >= 0 || indexOf(name, BACKSLASH) >= 0;
    }

    private static int indexOf(String s, char c) {
        return s.indexOf(c);
    }
}

public class Main {
    private static final Shell SHELL = new Shell(builtins());

    public static void main(String[] args) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                processLine(line);
            }
        }
    }

    private static void processLine(String line) {
        List<String> argv = tokenize(line);
        if (argv.isEmpty()) {
            return;
        }

        String cmd = argv.get(0);
        Command builtin = SHELL.builtin(cmd);
        if (builtin != null) {
            builtin.execute(argv, SHELL);
        } else {
            SHELL.runExternal(argv);
        }
    }

    private static Map<String, Command> builtins() {
        Map<String, Command> builtins = new HashMap<>();
        builtins.put("exit", new Exit());
        builtins.put("echo", new Echo());
        builtins.put("type", new Type());
        builtins.put("pwd", new Pwd());
        builtins.put("cd", new Cd());
        return Collections.unmodifiableMap(builtins);
    }

    private static List<String> tokenize(String line) {
        StringTokenizer st = new StringTokenizer(line);
        if (!st.hasMoreTokens()) {
            return Collections.emptyList();
        }

        List<String> tokens = new ArrayList<>();
        while (st.hasMoreTokens()) {
            tokens.add(st.nextToken());
        }
        return tokens;
    }

    private static final class Exit implements Command {
        @Override
        public void execute(List<String> argv, Shell shell) {
            System.exit(parseExitCode(argv));
        }

        private static int parseExitCode(List<String> argv) {
            if (argv.size() <= 1) {
                return 0;
            }
            try {
                return Integer.parseInt(argv.get(1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
    }

    private static final class Echo implements Command {
        @Override
        public void execute(List<String> argv, Shell shell) {
            for (int i = 1; i < argv.size(); i++) {
                if (i > 1) {
                    System.out.print(" ");
                }
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

            shell.findExecutableOnPath(name).ifPresentOrElse(
                    p -> System.out.println(name + " is " + p.toAbsolutePath()),
                    () -> System.out.println(name + ": not found")
            );
        }
    }

    private static final class Pwd implements Command {
        @Override
        public void execute(List<String> argv, Shell shell) {
            System.out.println(shell.getCurrentDir());
        }
    }

    private static final class Cd implements Command {
        @Override
        public void execute(List<String> argv, Shell shell) {
            if (argv.size() < 2) {
                return;
            }

            String target = argv.get(1);
            if (!isAbsolutePath(target)) {
                return;
            }

            Path targetPath;
            try {
                targetPath = Paths.get(target).toAbsolutePath().normalize();
            } catch (InvalidPathException e) {
                printError(target);
                return;
            }

            if (Files.isDirectory(targetPath)) {
                shell.setCurrentDir(targetPath);
            } else {
                printError(target);
            }
        }

        private static boolean isAbsolutePath(String path) {
            return path.charAt(0) == '/';
        }

        private static void printError(String target) {
            System.out.println("cd: " + target + ": No such file or directory");
        }
    }
}