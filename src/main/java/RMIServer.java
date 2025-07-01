import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMISocketFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Custom RMI socket factory to capture client port number.
 */
class KeyValueRMISocketFactory extends RMISocketFactory {

  @Override
  public ServerSocket createServerSocket(int port) throws IOException {
    return new ServerSocket(port) {
      @Override
      public Socket accept() throws IOException {
        Socket socket = super.accept();
        System.out.println(
            "Client connected from: " + socket.getInetAddress() + ":" + socket.getPort());
        ClientConnectionTracker.addClientSocket(socket.getInetAddress().getHostAddress(), socket);
        return socket;
      }
    };
  }

  @Override
  public Socket createSocket(String host, int port) throws IOException {
    return new Socket(host, port);
  }
}

/**
 * Utility class to track client connections and their associated sockets.
 */
class ClientConnectionTracker {

  private static final ConcurrentHashMap<String, Socket> clientSockets = new ConcurrentHashMap<>();

  public static void addClientSocket(String clientHost, Socket socket) {
    clientSockets.put(clientHost, socket);
  }

  public static Socket getClientSocket(String clientHost) {
    return clientSockets.get(clientHost);
  }
}


/**
 * RMIServer with Paxos to handle fault tolerance and achieving consensus of updates amongst
 * replicated state. Usage:
 * <pre>
 *  mvn exec:java -Dexec.mainClass="RMIServer" -Dexec.args="<server_port_list> <log_file_name_list> "
 * </pre>
 */
public class RMIServer {

  private static final int numReplicas = 5;
  private static final List<Acceptor> acceptorStubs = new ArrayList<>();
  private static final List<Proposer> proposerStubs = new ArrayList<>();
  private static final List<KeyValueImpWithPaxos> replicaObjects = new ArrayList<>();
  private static boolean socketFactorySet = false;

  // Static block to set the socket factory only once per JVM
  static {
    try {
      if (!socketFactorySet) {
        // step 1: Set custom socket factory to capture client port
        RMISocketFactory.setSocketFactory(new KeyValueRMISocketFactory());
        socketFactorySet = true;
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  /**
   * The main method parses the command-line arguments, creates an RMIServer instance.
   *
   * @param args command-line arguments: <port-lists> <log_file_name>
   */
  public static void main(String[] args) throws Exception {

    // step 1: arguments check
    if (args.length != 2) {
      System.err.println("Usage: java RMIServer <port-lists> <log_file_name>");
      System.exit(1);
    }

    // Parse port list (for this case, only replica 5)
    String[] portStrings = args[0].split(",");
    if (portStrings.length != 5) {
      System.err.println("Error: Please provide exactly 5 ports separated by commas.");
      System.exit(1);
    }

    // Convert port strings to integers
    List<Integer> ports = Arrays.stream(portStrings).map(String::trim).map(Integer::parseInt)
        .collect(Collectors.toList());

    // Parse log file list
    String[] logFileNames = args[1].split(",");
    if (logFileNames.length != 5) {
      System.err.println("Error: Please provide exactly 5 log files separated by commas.");
      System.exit(1);
    }

    // step 2: run Replicas
    BlockingQueue<PaxosMessage> centralizedLearnerQueue = new LinkedBlockingQueue<>();

    // Pre-create learners + acceptors
    for (int i = 0; i < numReplicas; i++) {
      String logFileName = logFileNames[i].trim();
      Acceptor acceptor = new Acceptor(centralizedLearnerQueue, logFileName, true);
      acceptorStubs.add(acceptor);
    }

    // For each replica, start RMI server with full wiring
    for (int i = 0; i < numReplicas; i++) {
      int port = ports.get(i);
      String logFileName = logFileNames[i].trim();

      ConcurrentHashMap<String, String> keyValueMap = new ConcurrentHashMap<>();
      ConcurrentHashMap<String, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

      // each replica can act as a proposer to initiate the Paxos process
      Proposer proposer = new Proposer(i, acceptorStubs, logFileName, true);
      proposerStubs.add(proposer);

      KeyValueImpWithPaxos replica = new KeyValueImpWithPaxos(proposer, acceptorStubs.get(i),
          centralizedLearnerQueue, keyValueMap, locks, logFileName);
      replicaObjects.add(replica);

      try {
        Registry registry = LocateRegistry.createRegistry(port);
        registry.rebind("KeyValueStore", replica);
        System.out.println("Server Replica " + i + " running on port " + port);
      } catch (RemoteException e) {
        e.printStackTrace();
      }
    }

    // Step 2: Launch centralized learner
    int quorum = (numReplicas / 2) + 1;
    Learner learner = new Learner(
        centralizedLearnerQueue,
        replicaObjects,
        proposerStubs,
        "./logs/CentralizedLearnerLog.txt",
        quorum,
        true);
    new Thread(learner).start();


    // FailureManagers
    // Single Role Failure Test

    // start proposer failure using RoleFailureManager
//    RoleFailureManager<Proposer> proposerFailureManager = new RoleFailureManager<>(
//        proposerStubs, RoleFailureManager.RoleType.PROPOSER);
//    new Thread(proposerFailureManager).start();

    // start acceptor failure using RoleFailureManager
//    RoleFailureManager<Acceptor> acceptorFailureManager = new RoleFailureManager<>(
//        acceptorStubs, RoleFailureManager.RoleType.ACCEPTOR);
//    new Thread(acceptorFailureManager).start();

    // start learner failure using RoleFailureManager
//    RoleFailureManager<Learner> learnerFailureManager = new RoleFailureManager<>(
//        List.of(learner), RoleFailureManager.RoleType.LEARNER);
//    new Thread(learnerFailureManager).start();

    // Multi Role Failure Test
    // start multirole failure using  MultiRoleFailureManager
    MultiRoleFailureManager multiRoleFailureManager = new MultiRoleFailureManager(
        proposerStubs, acceptorStubs, learner);
    new Thread(multiRoleFailureManager).start();

    Thread.sleep(Long.MAX_VALUE);
  }
}
