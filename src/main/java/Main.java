import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        // TODO: Uncomment the code below to pass the first stage
        System.out.print("$ ");
        Scanner sc = new Scanner(System.in);
        String command = sc.nextLine();
        System.out.println(command + ": command not found");
        main(new String[0]);
        sc.close();
    }
}