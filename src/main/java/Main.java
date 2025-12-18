import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

interface Command {
    void execute(String[] args);
}

public class Main {
    private static final Map<String, Command> COMMANDS = new HashMap<>();

    static {
        COMMANDS.put("exit", new Exit());
        COMMANDS.put("echo", new Echo());
        COMMANDS.put("type", new Type());
    }

    private static Command get(String name) {
        return COMMANDS.get(name);
    }

    private static boolean contains(String name) {
        return COMMANDS.containsKey(name);
    }

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) continue;

            String[] parts = input.split("\\s+");
            String commandName = parts[0];

            Command cmd = get(commandName);
            if (cmd != null) {
                cmd.execute(parts);
            } else {
                System.out.println(commandName + ": command not found");
            }
        }
    }

    private static class Exit implements Command {
        @Override
        public void execute(String[] args) {
            int code = 0;
            if (args.length > 1) {
                try {
                    code = Integer.parseInt(args[1]);
                } catch (NumberFormatException ignored) {}
            }
            System.exit(code);
        }
    }

    private static class Echo implements Command {
        @Override
        public void execute(String[] args) {
            if (args.length > 1) {
                System.out.println(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
            } else {
                System.out.println("echo: missing operand");
            }
        }
    }

    private static class Type implements Command {
        @Override
        public void execute(String[] args) {
            if (args.length < 2) {
                System.out.println("type: missing operand");
                return;
            }
            String name = args[1];
            if (contains(name)) {
                System.out.println(name + " is a shell builtin");
            } else {
                System.out.println(name + " not found");
            }
        }
    }
}