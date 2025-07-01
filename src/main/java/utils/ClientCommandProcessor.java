package utils;

import java.io.*;
import java.util.Scanner;

/**
 * Utility class for processing client commands in a Key-Value Store Client.
 * <p>
 * Provides methods to handle commands from a file or terminal and process them using a functional
 * interface.
 * </p>
 */
public class ClientCommandProcessor {

  /**
   * Reads and processes commands from a file or terminal.
   *
   * @param readFromFile    Whether commands are read from a file.
   * @param commandFileName The name of the file containing commands (if applicable).
   * @param logFileName     The name of the log file to log messages.
   * @param processCommand  A functional interface for processing each command.
   * @throws IOException If an I/O error occurs while reading commands.
   */
  public static void processCommands(boolean readFromFile, String commandFileName,
      String logFileName, CommandProcessor processCommand) throws IOException {
    LoggerUtil.logToFile("Key-Value Store Client started. Type 'exit' to quit.",
        logFileName);

    if (readFromFile) {
      // Read commands from file
      try (BufferedReader br = new BufferedReader(new FileReader(commandFileName))) {
        String command;
        while ((command = br.readLine()) != null) {
          processCommand.process(command, logFileName);
        }
      } catch (IOException e) {
        LoggerUtil.logToFile("Error reading command file: " + e.getMessage(), logFileName);
      }
    } else {
      // Read commands interactively from terminal
      Scanner scanner = new Scanner(System.in);
      while (true) {
        System.out.print("Please Enter command (PUT key value | GET key | DELETE key): ");
        String userInput = scanner.nextLine();

        if (userInput.equalsIgnoreCase("exit")) {
          LoggerUtil.logToFile("Client exited.", logFileName);
          break;
        }
        processCommand.process(userInput, logFileName);
      }
      scanner.close();
    }
  }

  /**
   * Functional interface for processing a single command.
   */
  @FunctionalInterface
  public interface CommandProcessor {

    /**
     * Processes a single command.
     *
     * @param command     The command to process (e.g., "PUT key value").
     * @param logFileName The log file for logging results or errors.
     * @throws IOException If an error occurs during command processing.
     */
    void process(String command, String logFileName) throws IOException;
  }
}
