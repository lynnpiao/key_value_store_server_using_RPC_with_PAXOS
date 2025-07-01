import java.net.Socket;
import java.rmi.RemoteException;
import java.rmi.server.RemoteServer;
import java.rmi.server.ServerNotActiveException;
import java.rmi.server.UnicastRemoteObject;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import utils.LoggerUtil;
import utils.Request;
import utils.Response;

/**
 * Implementation of a Paxos-based distributed Key-Value Store using Java RMI.
 * <p>
 * This class serves as the replica server that handles client requests in XML format, processes
 * them via Paxos (for PUT and DELETE), or directly accesses the local key-value store (for GET),
 * and returns XML responses. It integrates Paxos roles (Proposer and Acceptor) and coordinates with
 * a shared Learner queue to achieve distributed consensus on state changes.
 * </p>
 * <p>
 * The implementation ensures thread-safety using {@link ReentrantReadWriteLock} per key. Logging is
 * performed to a file and optionally printed to the console for debugging.
 * </p>
 */
public class KeyValueImpWithPaxos extends UnicastRemoteObject implements KeyValueWithPaxos {

  private static final long serialVersionUID = 1L;

  private final Proposer proposer;
  private final Acceptor acceptor;
  private final BlockingQueue<PaxosMessage> learnerQueue;
  private final ConcurrentHashMap<String, String> keyValueMap;
  private final ConcurrentHashMap<String, ReentrantReadWriteLock> locks;
  private final String logFileName;

  public KeyValueImpWithPaxos(Proposer proposer, Acceptor acceptor,
      BlockingQueue<PaxosMessage> learnerQueue,
      ConcurrentHashMap<String, String> keyValueMap,
      ConcurrentHashMap<String, ReentrantReadWriteLock> locks,
      String logFileName) throws RemoteException {
    super();
    this.proposer = proposer;
    this.acceptor = acceptor;
    this.learnerQueue = learnerQueue;
    this.keyValueMap = keyValueMap;
    this.locks = locks;
    this.logFileName = logFileName;
  }

  /**
   * Handles incoming Paxos PREPARE messages from Proposers.
   */
  @Override
  public void receivePrepare(PaxosMessage prepare) throws RemoteException {
    acceptor.receivePrepare(prepare);
  }

  /**
   * Handles incoming Paxos ACCEPT messages from Proposers.
   */
  @Override
  public void receiveAccept(PaxosMessage accept) throws RemoteException {
    acceptor.receiveAccept(accept);
  }


  /**
   * Processes XML-formatted client requests and returns XML-formatted responses.
   * <p>
   * Supports GET, PUT, and DELETE commands. GET is handled locally, while PUT/DELETE are delegated
   * to Paxos through the Proposer.
   * </p>
   *
   * @param xmlRequest The XML request string.
   * @return The XML response string.
   * @throws RemoteException If processing fails.
   */
  @Override
  public String processXML(String xmlRequest) throws RemoteException {
    String clientInfo = getClientInfo();
    // Remove XML header for logging purposes.
    String xmlRequestNoHeader = xmlRequest.replaceFirst("^<\\?xml[^>]*\\?>", "").trim();
    LoggerUtil.logToFile(
        "Received XML utils.Request: " + xmlRequestNoHeader + " from: " + clientInfo,
        logFileName);

    try {
      Request request = deserializeRequest(xmlRequest);
      String msg = handleCommand(request);

      // Package result into XML
      Response response = new Response(msg);
      String xmlResponse = serializeResponse(response);

      LoggerUtil.logToFile("Sending XML utils.Response: " + xmlResponse + " to: " + clientInfo,
          logFileName);
      return xmlResponse;

    } catch (JAXBException e) {
      LoggerUtil.logToFile("Received malformed request of length " + xmlRequest.length()
          + " from: " + clientInfo, logFileName);
      Response errorResp = new Response("XML Error: " + e.getMessage());
      try {
        String xmlErrorResponse = serializeResponse(errorResp);
        LoggerUtil.logToFile(
            "Sending XML Error utils.Response: " + xmlErrorResponse + " to: " + clientInfo,
            logFileName);
        return xmlErrorResponse;
      } catch (JAXBException ex) {
        String fallbackResponse = "<utils.Response><message>Fatal XML Error</message></utils.Response>";
        LoggerUtil.logToFile(
            "Sending fallback XML Error utils.Response: " + fallbackResponse + " to: " + clientInfo,
            logFileName);
        return fallbackResponse;
      }
    } catch (Exception e) {
      LoggerUtil.logToFile("Error processing request from " + clientInfo + ": " + e.getMessage(),
          logFileName);
      throw new RemoteException("Processing error", e);
    }
  }

  /**
   * Helper method for handle operation PUT/DELETE.
   */
  private String handleCommand(Request req) {
    String type = (req.getType() != null) ? req.getType().toUpperCase() : "";
    String key = req.getKey();
    String value = req.getValue();

    try {
      switch (type) {
        case "GET":
          return handleGet(key);
        case "PUT":
        case "DELETE":
          return proposer.propose(key, value, type);
        default:
          return "ERROR: Unknown command type: " + req.getType();
      }
    } catch (Exception e) {
      return "ERROR: Paxos Handling Failed: " + e.getMessage();
    }
  }

  /**
   * Helper method for handle operation GET.
   */
  private String handleGet(String key) {
    if (key == null) {
      return "ERROR: GET requires a key.";
    }

    ReentrantReadWriteLock readWriteLock = locks.computeIfAbsent(key,
        k -> new ReentrantReadWriteLock());
    boolean lockAcquired = false;

    try {
      lockAcquired = readWriteLock.readLock().tryLock(5, TimeUnit.SECONDS);
      if (lockAcquired) {
        String val = keyValueMap.get(key);
        return (val != null) ? "FOUND: (KEY= " + key + ", VALUE= " + val + ")"
            : "NOT FOUND: " + key;
      } else {
        return "ERROR: GET operation timed out";
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "ERROR: GET operation interrupted";
    } finally {
      if (lockAcquired) {
        readWriteLock.readLock().unlock();
      }
    }
  }

  /**
   * Helper method to commit put and delete operation for learner broadcast.
   */
  public String applyKVAndReturnOldValue(String key, String value, String command) {
    ReentrantReadWriteLock lock = locks.computeIfAbsent(key, k -> new ReentrantReadWriteLock());
    lock.writeLock().lock();
    try {
      if ("PUT".equalsIgnoreCase(command)) {
        String oldValue = keyValueMap.get(key);
        keyValueMap.put(key, value);
        LoggerUtil.logToFile("[Replica] COMMIT_PUT | Key: " + key + " | Value: " + value,
            logFileName);
        System.out.println("[Replica] COMMIT_PUT key=" + key + ", value=" + value);
        return oldValue;
      } else if ("DELETE".equalsIgnoreCase(command)) {
        String oldValue = keyValueMap.get(key);
        keyValueMap.remove(key);
        LoggerUtil.logToFile("[Replica] COMMIT_DELETE | Key: " + key, logFileName);
        System.out.println("[Replica] COMMIT_DELETE key=" + key);
        return oldValue;
      } else {
        LoggerUtil.logToFile("[Replica] UNKNOWN_COMMIT | Key: " + key + " | Command: " + command,
            logFileName);
        System.out.println("[Replica] Unknown command type during commit: " + command);
        return null;
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

//  public boolean isProposer(int proposerId) {
//    return this.proposer.getProposerId() == proposerId;
//  }

  /**
   * Helper method to obtain client information (InetAddress and port placeholder).
   */
  private String getClientInfo() {
    String clientHost;
    int clientPort = -1;
    try {
      clientHost = RemoteServer.getClientHost();
      Socket socket = ClientConnectionTracker.getClientSocket(clientHost);
      if (socket != null) {
        clientPort = socket.getPort();
      }
    } catch (ServerNotActiveException e) {
      clientHost = "unknown";
    }
    return clientHost + ":" + (clientPort == -1 ? "N/A" : clientPort);
  }

  /**
   * Deserializes an XML string to a {@link Request} object.
   *
   * @param xml The XML string to deserialize.
   * @return The deserialized {@link Request} object.
   * @throws JAXBException If an error occurs during deserialization.
   */
  private Request deserializeRequest(String xml) throws JAXBException {
    JAXBContext jaxbContext = JAXBContext.newInstance(Request.class);
    Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
    return (Request) unmarshaller.unmarshal(new StringReader(xml));
  }

  /**
   * Serializes a {@link Response} object to an XML string.
   *
   * @param response The command to serialize.
   * @return The XML string representation of the response.
   * @throws JAXBException If an error occurs during serialization.
   */
  private String serializeResponse(Response response) throws JAXBException {
    JAXBContext jaxbContext = JAXBContext.newInstance(Response.class);
    Marshaller marshaller = jaxbContext.createMarshaller();
    marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.FALSE);
    marshaller.setProperty(Marshaller.JAXB_FRAGMENT, Boolean.TRUE);

    StringWriter sw = new StringWriter();
    marshaller.marshal(response, sw);
    return sw.toString();
  }


  private void logTransaction(String action, String key, String value) {
    String logEntry = action + " | Key: " + key + " | Value: " + value;
    LoggerUtil.logToFile(logEntry, logFileName);
  }


}
