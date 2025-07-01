import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import utils.LoggerUtil;

/**
 * The Acceptor class is a key component in the Paxos consensus protocol. It is responsible for
 * handling PREPARE and ACCEPT messages from Proposers, maintaining promise history per key, and
 * sending ACCEPTED messages to the Learner.
 * <p>
 * The Acceptor supports fault-injection scenarios via shutdown and restart methods to simulate node
 * crashes and recoveries.
 */
public class Acceptor implements FailureRole{

  // track promisedID per key
  private final Map<String, Integer> promisedIds = new ConcurrentHashMap<>();
  private final BlockingQueue<PaxosMessage> learnerQueue;
  private final String logFileName;
  private final boolean debug;

  // for fail & restart
  private volatile boolean isRunning = true;


  /**
   * Constructs a new Acceptor.
   *
   * @param learnerQueue BlockingQueue used to send messages to the Learner.
   * @param logFileName  The log file used for logging Acceptor actions.
   * @param debug        Enables console debug output if true.
   */
  public Acceptor(BlockingQueue<PaxosMessage> learnerQueue, String logFileName, boolean debug) {
    this.learnerQueue = learnerQueue;
    this.logFileName = logFileName;
    this.debug = debug;
  }

  /**
   * Handles an incoming PREPARE message from a Proposer. If the proposal ID is higher than any
   * previously promised ID for the given key, the Acceptor promises not to accept lower proposal
   * IDs and replies with a PROMISE.
   *
   * @param prepare The incoming PREPARE message from a Proposer.
   */
  public void receivePrepare(PaxosMessage prepare) {
    if (!isRunning) {
      return;
    } // shutdown check

    int currentPromised = promisedIds.getOrDefault(prepare.key, -1);
    System.out.println("[current proposalId--Promise] : " + prepare.proposalId);
    System.out.println("[local  promisedId--Promise] : " + currentPromised);
    if (prepare.proposalId > currentPromised) {

      promisedIds.put(prepare.key, prepare.proposalId);

      PaxosMessage promise = new PaxosMessage(PaxosMessage.Type.PROMISE, prepare.proposalId,
          prepare.key, prepare.value, prepare.commandType);

      //promise.proposerCommitQueue = prepare.proposerCommitQueue;

      // Send PROMISE back to Proposer's senderQueue
      if (prepare.senderQueue != null) {
        prepare.senderQueue.offer(promise);
        log("[Acceptor] PROMISE sent for key=" + prepare.key + " with proposalId="
            + prepare.proposalId);
      }
    } else {
      log("[Acceptor] REJECTED PREPARE for key=" + prepare.key + " with proposalId="
          + prepare.proposalId);
    }
  }

  /**
   * Handles an incoming ACCEPT message from a Proposer. If the proposal ID is at least as large as
   * the current promised ID for the key, the Acceptor accepts the proposal and forwards an ACCEPTED
   * message to the Learner.
   *
   * @param accept The incoming ACCEPT message from a Proposer.
   */
  public void receiveAccept(PaxosMessage accept) {

    if (!isRunning) {
      return; // shutdown check
    }

    int currentPromised = promisedIds.getOrDefault(accept.key, -1);

    System.out.println("[current proposalId--Accept] : " + accept.proposalId);
    System.out.println("[local  promisedId--Accept] : " + currentPromised);

    if (accept.proposalId >= currentPromised) {
      promisedIds.put(accept.key, accept.proposalId);
      PaxosMessage accepted = new PaxosMessage(PaxosMessage.Type.ACCEPTED, accept.proposalId,
          accept.key, accept.value, accept.commandType);

      //accepted.proposerCommitQueue = accept.proposerCommitQueue;

      learnerQueue.offer(accepted);
      log("[Acceptor] ACCEPTED sent for key=" + accept.key + " with proposalId="
          + accept.proposalId);
    } else {
      log("[Acceptor] REJECTED accept for key=" + accept.key + " due to lower proposalId="
          + accept.proposalId);
    }
  }

  // for periodically shutdown and restart
  @Override
  public void shutdown() {
    isRunning = false;
    log("[Acceptor] SHUTDOWN called");
  }

  @Override
  public void restart() {
    promisedIds.clear();
    isRunning = true;
    //    Acceptor restarted = new Acceptor(this.learnerQueue, this.logFileName, this.debug);
    log("[Acceptor] RESTARTED on same instance");
  }

  @Override
  public boolean isRunning() {
    return isRunning;
  }


  private void log(String msg) {
    LoggerUtil.logToFile(msg, logFileName);
    if (debug) {
      System.out.println(msg);
    }
  }
}
