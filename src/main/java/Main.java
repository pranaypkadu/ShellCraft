import java.util.Arrays;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        while (true) {
            System.out.print("$ ");
            Scanner scanner = new Scanner(System.in);
            String command = scanner.nextLine();

            if ("exit".equals(command)) break;

            // echo builtin: prints arguments separated by spaces + newline
            if (command.startsWith("echo")) {
                String[] parts = command.trim().split("\\s+");
                if (parts.length <= 1) {
                    System.out.println();
                } else {
                    System.out.println(String.join(" ", Arrays.copyOfRange(parts, 1, parts.length)));
                }
                continue;
            }

            System.out.println(command + ": command not found");
        }
    }
}