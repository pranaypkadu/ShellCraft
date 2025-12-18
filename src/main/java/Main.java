import java.util.Scanner;

public class Main {

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) continue;

            String[] parts = input.split("\\s+");
            String commandName = parts[0];

            Command cmd = Commands.get(commandName);

            if (cmd != null) {
                cmd.execute(parts);
            } else {
                System.out.println(commandName + ": command not found");
            }
        }
    }
}