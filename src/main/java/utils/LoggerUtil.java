package utils;

import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Utility class for logging messages to a file with a timestamp.
 * <p>
 * The {@code utils.LoggerUtil} class provides a single static method for logging messages to a specified
 * log file. Each log entry is prepended with a timestamp in the format
 * {@code yyyy-MM-dd HH:mm:ss.SSS}.
 * </p>
 */
public class LoggerUtil {

  /**
   * Logs a message to the specified log file, prepending it with a timestamp.
   *
   * @param message     the message to log
   * @param logFileName the name of the log file where the message will be written
   */
  public static void logToFile(String message, String logFileName) {
    // Format the current timestamp
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault());
    String timestamp = formatter.format(Instant.now());

    // Prepend the timestamp to the message
    String timestampedMessage = timestamp + " " + message;

    try (FileWriter logWriter = new FileWriter(logFileName, true)) {
      logWriter.write(timestampedMessage + System.lineSeparator());
    } catch (IOException e) {
      System.err.println("Error writing to log file: " + e.getMessage());
    }
  }
}
