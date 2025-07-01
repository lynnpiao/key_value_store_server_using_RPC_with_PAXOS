import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import utils.LoggerUtil;

/**
 * The {@code Learner} is a core component in the Paxos protocol that collects ACCEPTED messages
 * from Acceptors via a shared queue. Once a quorum of ACCEPTED messages is reached for a given
 * (key, proposalId), the Learner commits the operation across all replicas and notifies Proposers.
 * <p>
 * This implementation also supports random fault injection via {@link #shutdown()} and
 * {@link #restart()}.
 */
public class Learner implements FailureRole, Runnable {

  private final BlockingQueue<PaxosMessage> learnerQueue;
  private final List<KeyValueImpWithPaxos> replicas;
  private final List<Proposer> proposers;
  private final String logFileName;
  private final int quorum;
  private final boolean debug;

  private volatile boolean isRunning = true; // for shutdown/restart control

  // Track how many ACCEPTED for each (key, proposalId)
  private final Map<String, Integer> quorumCount = new ConcurrentHashMap<>();
  private final Map<String, PaxosMessage> acceptedMessages = new ConcurrentHashMap<>();

  /**
   * Constructs a new Learner instance.
   *
   * @param learnerQueue The shared queue from which ACCEPTED messages are received.
   * @param replicas     The list of replica servers.
   * @param proposers    The list of Proposers to notify after commits.
   * @param logFileName  The file to log Learner activity.
   * @param quorum       The quorum size for commit decisions.
   * @param debug        Enables debug logging if true.
   */
  public Learner(BlockingQueue<PaxosMessage> learnerQueue,
      List<KeyValueImpWithPaxos> replicas,
      List<Proposer> proposers,
      String logFileName,
      int quorum,
      boolean debug) {
    this.learnerQueue = learnerQueue;
    this.replicas = replicas;
    this.proposers = proposers;
    this.logFileName = logFileName;
    this.quorum = quorum;
    this.debug = debug;
  }

  /**
   * The main loop to process incoming ACCEPTED messages and commit once quorum is reached.
   */
  @Override
  public void run() {
    try {
      while (true) {

        if (!isRunning) {
          Thread.sleep(1000); // Wait in paused mode during shutdown
          continue;
        }

        PaxosMessage msg = learnerQueue.take();

        if (msg.type == PaxosMessage.Type.ACCEPTED) {
          String quorumKey = msg.key + ":" + msg.proposalId;

          quorumCount.merge(quorumKey, 1, Integer::sum);
          int count = quorumCount.get(quorumKey);

          acceptedMessages.putIfAbsent(quorumKey, msg);

          if (count >= quorum) {
            PaxosMessage chosen = acceptedMessages.get(quorumKey);
            broadcastCommit(chosen);

            quorumCount.remove(quorumKey);
            acceptedMessages.remove(quorumKey);

            log("[Learner] COMMITTED key=" + chosen.key + " proposalId=" + chosen.proposalId);
          }
        } else {
          log("[Learner] Ignored non-ACCEPTED message type=" + msg.type +
              " for key=" + msg.key + " proposalId=" + msg.proposalId);
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Broadcasts a commit to all replicas and notifies all Proposers once a value is learned.
   *
   * @param msg The PaxosMessage that has reached quorum and will be committed.
   */
  private void broadcastCommit(PaxosMessage msg) {
    // step 1: notify all replicas to commit put/delete operation
    String oldValue = null;

    for (int i = 0; i < replicas.size(); i++) {
      KeyValueImpWithPaxos replica = replicas.get(i);
      try {
        oldValue = replica.applyKVAndReturnOldValue(msg.key, msg.value, msg.commandType);
        log("[Learner] Applied commit on replica-" + i + " for key=" + msg.key);
      } catch (Exception e) {
        log("[Learner] Failed to apply commit on replica-" + i + ": " + e.getMessage());
      }
    }

    // step 2: broadcast commit to all proposers
    for (int i = 0; i < proposers.size(); i++) {
      Proposer proposer = proposers.get(i);
      try {
        PaxosMessage commit = new PaxosMessage(
            PaxosMessage.Type.COMMIT,
            msg.proposalId,
            msg.key,
            msg.value,
            msg.commandType);
        commit.oldValue = oldValue;

        proposer.receiveCommit(commit);

        log("[Learner] Sent COMMIT msg to proposer-" + i + " for key=" + msg.key);

      } catch (Exception e) {
        log("[Learner] Failed to notify proposer-" + i + ": " + e.getMessage());
      }
    }

  }

  @Override
  public void shutdown() {
    isRunning = false;
    log("[Learner] SHUTDOWN called");
  }

  @Override
  public void restart() {
    isRunning = true;
    log("[Learner] RESTARTED on same instance");
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
