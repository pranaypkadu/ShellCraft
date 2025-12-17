import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            String command = scanner.nextLine();

            if (command.equals("exit")) break;

            if (command.equals("echo")) {
                System.out.println();
                continue;
            }

            if (command.startsWith("echo ")) {
                String output = command.substring(5).trim().replaceAll("\\s+", " ");
                System.out.println(output);
                continue;
            }

            System.out.println(command + ": command not found");
            System.out.println(command + ": command");
        }
    }
}