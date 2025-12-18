import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

interface Command {
    void execute(String[] args, Shell shell);
}

final class Shell {
    private final Map<String, Command> builtins;

    Shell(Map<String, Command> builtins) {
        this.builtins = builtins;
    }

    boolean isBuiltin(String name) {
        return builtins.containsKey(name);
    }

    Command getCommand(String name) {
        return builtins.get(name);
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
            if (line == null) return;              // EOF
            line = line.trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+");
            String cmdName = parts[0];

            Command cmd = shell.getCommand(cmdName);
            if (cmd != null) {
                cmd.execute(parts, shell);
            } else {
                System.out.println(cmdName + ": command not found");
            }
        }
    }

    private static final class Exit implements Command {
        @Override
        public void execute(String[] args, Shell shell) {
            int code = 0;
            if (args.length > 1) {
                try { code = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
            }
            System.exit(code);
        }
    }

    private static final class Echo implements Command {
        @Override
        public void execute(String[] args, Shell shell) {
            if (args.length <= 1) {
                System.out.println("echo: missing operand");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                if (i > 1) sb.append(' ');
                sb.append(args[i]);
            }
            System.out.println(sb);
        }
    }

    private static final class Type implements Command {
        @Override
        public void execute(String[] args, Shell shell) {
            if (args.length < 2) {
                System.out.println("type: missing operand");
                return;
            }

            String name = args[1];

            // 1) Builtin check
            if (shell.isBuiltin(name)) {
                System.out.println(name + " is a shell builtin");
                return;
            }

            // 2) PATH search
            String pathEnv = System.getenv("PATH");
            if (pathEnv == null || pathEnv.isEmpty()) {
                System.out.println(name + ": not found");
                return;
            }

            String[] dirs = pathEnv.split(Pattern.quote(java.io.File.pathSeparator), -1);

            for (String dir : dirs) {
                // On Unix, empty PATH entries mean "current directory"
                Path base;
                try {
                    base = (dir == null || dir.isEmpty()) ? Paths.get(".") : Paths.get(dir);
                } catch (InvalidPathException e) {
                    continue; // Skip malformed PATH entries gracefully
                }

                Path candidate = base.resolve(name);

                // Must exist and be executable; skip non-executable hits and continue
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    System.out.println(name + " is " + candidate.toAbsolutePath());
                    return;
                }
            }

            System.out.println(name + ": not found");
        }
    }
}