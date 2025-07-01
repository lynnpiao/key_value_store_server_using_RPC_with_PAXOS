import java.io.StringWriter;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import java.util.concurrent.*;
import utils.ClientCommandProcessor;
import utils.LoggerUtil;
import utils.Request;
import utils.Response;

/**
 * RMIClient for interacting with a Key-Value Store Server. Use {@link ClientCommandProcessor} for
 * handling command input. Usage:
 * <pre>
 *  mvn exec:java -Dexec.mainClass="RMIClient" -Dexec.args="<server_name> <server_port>
 *    <log_file_name> [command_file_name]"
 * </pre>
 */
public class RMIClient {

  private static String serverHost;
  private static List<Integer> serverPorts;
  private static String logFileName;
  private static boolean readFromFile;
  private static String commandFileName;
  private static final Random random = new Random();
  private static int leaderPort = -1;

  public static void main(String[] args) {
    if (args.length != 3 && args.length != 4) {
      System.err.println(
          "Usage: java RMIClient <server_host> <server_port_list> <log_file_name> [command_file_name]");
      return;
    }

    serverHost = args[0];
    // server port lists
    String[] portArray = args[1].split(",");
    serverPorts = new ArrayList<>();
    for (String port : portArray) {
      serverPorts.add(Integer.parseInt(port.trim()));
    }
    logFileName = args[2];
    readFromFile = (args.length == 4);
    commandFileName = readFromFile ? args[3] : null;

    electLeader();

    try {

      // Process commands (using a command processor that calls processXMLCommand for each command)
      ClientCommandProcessor.processCommands(
          readFromFile,
          commandFileName,
          logFileName,
          (command, lf) -> processXMLCommand(serverHost, serverPorts, command, lf)
      );

    } catch (Exception e) {
      LoggerUtil.logToFile("Client exception: " + e.getMessage(), logFileName);
    }
  }


  private static void electLeader() {
    leaderPort = Collections.min(serverPorts);
  }


  /**
   * Converts a user text command to a utils.Request object, marshals it to XML, sends it to the server,
   * and prints/unmarshals the response. Implements a timeout mechanism to handle an unresponsive
   * server.
   */
  private static void processXMLCommand(String serverHost, List<Integer> serverPorts,
      String command,
      String logFileName) {
    try {
      // Randomly select a replica port for each request
      int randomPort = serverPorts.get(random.nextInt(serverPorts.size()));

//      utils.LoggerUtil.logToFile("Attempting to connect to server at port: " + randomPort, logFileName);
//
//      Registry registry = LocateRegistry.getRegistry(serverHost, randomPort);
//      KeyValueWithPaxos remoteStore = (KeyValueWithPaxos) registry.lookup("KeyValueStore");
//
//      utils.LoggerUtil.logToFile("Successfully connected to server at " + serverHost + ":" + randomPort,
//          logFileName);

      // step 1 :  Convert user input into a utils.Request object.
      Request req = parseCommand(command, logFileName);

      // step 2 : Serialize the utils.Request into XML.
      String requestXML = serializeRequest(req);

      int targetPort = req.getType().equalsIgnoreCase("GET") ? randomPort : leaderPort;

      LoggerUtil.logToFile("Attempting to connect to server at port: " + targetPort, logFileName);

      Registry registry = LocateRegistry.getRegistry(serverHost, targetPort);
      KeyValueWithPaxos remoteStore = (KeyValueWithPaxos) registry.lookup("KeyValueStore");

      LoggerUtil.logToFile("Successfully connected to server at " + serverHost + ":" + targetPort,
          logFileName);

      // Create an ExecutorService to manage the remote call with a timeout.
      ExecutorService executor = Executors.newSingleThreadExecutor();
      Future<String> future = executor.submit(() -> remoteStore.processXML(requestXML));

      String responseXML = null;
      try {
        // Wait up to 3 seconds for the server's response.
        responseXML = future.get(3, TimeUnit.SECONDS);
      } catch (TimeoutException te) {
        LoggerUtil.logToFile(
            "Timeout: Server did not respond within 3 seconds for request: " + requestXML,
            logFileName);
        System.out.println("Error: Server did not respond in a timely manner.");
        future.cancel(true);
        return; // Skip further processing for this command.
      } finally {
        executor.shutdownNow();
      }

      //step 3 : Unmarshal the response XML into a utils.Response object.
      Response resp = deserializeResponse(responseXML);

      String responseXMLNoHeader = responseXML.replaceFirst("^<\\?xml[^>]*\\?>",
          "").trim();
      //step 4 : Log and print both the request and the response.
      LoggerUtil.logToFile("XML utils.Request: " + requestXML, logFileName);
      LoggerUtil.logToFile("XML utils.Response: " + responseXMLNoHeader, logFileName);
      System.out.println("Server utils.Response: " + resp.getMessage());

    } catch (Exception e) {
      LoggerUtil.logToFile("Error in command: " + command + ". " + e.getMessage(),
          logFileName);
      System.out.println("Error: " + e.getMessage());
    }
  }

  /**
   * Parses a plain text command into a {@link Request} object.
   *
   * @param command The command string (e.g., "PUT key value").
   * @return The {@link Request} object.
   */
  private static Request parseCommand(String command, String logFileName) {
    String[] parts = command.split(" ");
    if (parts.length > 3) {
//      utils.LoggerUtil.logToFile("Too many arguments in command (not send to server): " + command
//          + ". Expected format: <TYPE> <KEY> [<VALUE>]", logFileName);
      throw new IllegalArgumentException("Too many arguments in command: " + command
          + ". Expected format: <TYPE> <KEY> [<VALUE>]");
    }
    // parse commands
    String type = parts[0].toUpperCase();
    String key = (parts.length > 1) ? parts[1] : null;
    String value = (parts.length > 2) ? parts[2] : null;

    return new Request(type, key, value);
  }

  /**
   * Serializes a {@link Request} object to an XML string.
   *
   * @param request The command to serialize.
   * @return The XML string representation of the command.
   * @throws JAXBException If an error occurs during serialization.
   */
  private static String serializeRequest(Request request) throws JAXBException {
    JAXBContext jaxbContext = JAXBContext.newInstance(Request.class);
    Marshaller marshaller = jaxbContext.createMarshaller();
    marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.FALSE);
    marshaller.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE); // Omit the XML declaration

    StringWriter sw = new StringWriter();
    marshaller.marshal(request, sw);
    return sw.toString();
  }

  /**
   * Deserializes an XML string to a {@link Response} object.
   *
   * @param xml The XML string to deserialize.
   * @return The deserialized {@link Response} object.
   * @throws JAXBException If an error occurs during deserialization.
   */
  private static Response deserializeResponse(String xml) throws JAXBException {
    JAXBContext jaxbContext = JAXBContext.newInstance(Response.class);
    Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
    return (Response) unmarshaller.unmarshal(new java.io.StringReader(xml));
  }
}

