import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import utils.LoggerUtil;

/**
 * The {@code Proposer} is a Paxos role responsible for initiating consensus rounds by sending
 * PREPARE and ACCEPT messages to Acceptors and waiting for quorum responses.
 *
 * <p>
 * The Proposer handles state-changing operations (PUT/DELETE) requested by clients and waits for a
 * COMMIT message after quorum is achieved and the Learner broadcasts the commit.
 * </p>
 *
 * <p>
 * The Proposer also supports simulated crashes via {@link #shutdown()} and {@link #restart()}
 * methods.
 * </p>
 */
public class Proposer implements FailureRole{

  private final int proposerId; // unique proposer ID
  private final List<Acceptor> acceptors; // list of remote Paxos replicas
  private final BlockingQueue<PaxosMessage> proposerInbox; // collects PROMISE messages
  private final BlockingQueue<PaxosMessage> commitInbox; // collects COMMIT messages
  private final String logFileName;
  private final boolean debug;

  // periodically shutdown and restart
  private volatile boolean isRunning = true;


  // proposalID local increasing
  private final AtomicInteger localProposalCounter = new AtomicInteger();

  /**
   * Constructs a new Proposer instance.
   *
   * @param proposerId  The unique ID for this Proposer.
   * @param acceptors   The list of remote Acceptors.
   * @param logFileName The log file name.
   * @param debug       Enables console debug output if true.
   */
  public Proposer(int proposerId, List<Acceptor> acceptors, String logFileName, boolean debug) {
    this.proposerId = proposerId;
    this.acceptors = acceptors;
    this.proposerInbox = new LinkedBlockingQueue<>();
    this.commitInbox = new LinkedBlockingQueue<>();
    this.logFileName = logFileName;
    this.debug = debug;
  }

  public String propose(String key, String value, String commandType) {

    if (!isRunning) {
      return "[ERROR] Proposer is not running";
    }

    int proposalId = generateProposalId();

    // Phase 1: PREPARE
    PaxosMessage prepare = new PaxosMessage(PaxosMessage.Type.PREPARE, proposalId, key, value,
        commandType);
    prepare.senderQueue = proposerInbox;
    // prepare.proposerCommitQueue = commitInbox;

    for (int i = 0; i < acceptors.size(); i++) {
      Acceptor acceptor = acceptors.get(i);
      try {
        acceptor.receivePrepare(prepare);
      } catch (Exception e) {
        log("[Proposer] : " + proposerId + " failed PREPARE to Acceptor-" + i + ": "
            + e.getMessage());
      }
    }

    int promiseCount = waitForPromises(proposalId);
    if (promiseCount < quorumSize()) {
      log("[Proposer] : " + proposerId + " failed to gather quorum PROMISEs.");
      return "ERROR: Paxos phase1 failed (PROMISE quorum not reached) to " + commandType
          + "for key=" + key;
    }

    log("[Proposer] :" + proposerId + " quorum reached (" + promiseCount + "/" + quorumSize()
        + "), sending ACCEPTs.");

    // Phase 2: ACCEPT
    PaxosMessage accept = new PaxosMessage(PaxosMessage.Type.ACCEPT, proposalId, key, value,
        commandType);
    accept.proposerId = proposerId;
//    accept.proposerCommitQueue = commitInbox;

    for (int i = 0; i < acceptors.size(); i++) {
      Acceptor acceptor = acceptors.get(i);
      try {
        acceptor.receiveAccept(accept);
      } catch (Exception e) {
        log("[Proposer] : " + proposerId + " failed ACCEPT to Acceptor-" + i + ": "
            + e.getMessage());
        return "ERROR: Paxos phase2 failed (Acceptor not accepted) to " + commandType
            + "for key=" + key;
      }
    }
    return waitForCommit(key, proposalId, commandType);

  }

  /**
   * Helper method to wait for all Promises to finish.
   */
  private int waitForPromises(int proposalId) {
    int count = 0;
    long deadline = System.currentTimeMillis() + 3000;
    try {
      while (count < quorumSize() && System.currentTimeMillis() < deadline) {
        PaxosMessage msg = proposerInbox.poll(3000, TimeUnit.MILLISECONDS);
        if (msg != null && msg.type == PaxosMessage.Type.PROMISE && msg.proposalId == proposalId) {
          count++;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return count;
  }


  public void receiveCommit(PaxosMessage commitMsg) {
    if (commitMsg != null && commitMsg.type == PaxosMessage.Type.COMMIT) {
      commitInbox.offer(commitMsg);
      log("[Proposer] : " + proposerId + " received COMMIT for key=" + commitMsg.key);
    }
  }

  /**
   * Helper method to wait for all commit to finish.
   */
  private String waitForCommit(String key, int proposalId, String commandType) {
    long deadline = System.currentTimeMillis() + 1000;
    try {
      while (System.currentTimeMillis() < deadline) {

        PaxosMessage commitMsg = commitInbox.poll(500, TimeUnit.MILLISECONDS); // shorter poll
        if (commitMsg == null) {
          continue; // keep waiting
        }

        if (commitMsg.type != PaxosMessage.Type.COMMIT) {
          continue; // ignore non-commit
        }

//        System.out.println("CommitMessage key: " + commitMsg.key);
//        System.out.println("Original key: " + key);
//
//        System.out.println("CommitMessage proposalId: " + commitMsg.proposalId);
//        System.out.println("Original proposalId: " + proposalId);
//
//        System.out.println("CommitMessage commandType: " + commitMsg.commandType);
//        System.out.println("Original commandType: " + commandType);

        // Match proposalId, key, commandType
        if (commitMsg.key.equals(key)
            && commitMsg.proposalId == proposalId
            && commitMsg.commandType.equalsIgnoreCase(commandType)) {

          // matched!
          switch (commitMsg.commandType.toUpperCase()) {
            case "PUT":
              if (commitMsg.oldValue == null) {
                return "PUT: Inserted (KEY=" + key + ", VALUE=" + commitMsg.value + ")";
              } else {
                return "PUT: Updated (KEY=" + key + ", VALUE=" + commitMsg.value + "), OLD VALUE="
                    + commitMsg.oldValue;
              }
            case "DELETE":
              if (commitMsg.oldValue != null) {
                return "DELETED: (KEY=" + key + ", VALUE=" + commitMsg.oldValue + ")";
              } else {
                return "DELETE: Key not found (KEY=" + key + ")";
              }
            default:
              return "ERROR: Unknown commit command type for key=" + key;
          }
        } else {
          continue; // skip non-matching commitMsg, keep polling
        }
      }
      return "ERROR: Commit phase timeout for key=" + key;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "ERROR: Interrupted while waiting for commit on key=" + key;
    }
  }

  @Override
  public void shutdown() {
    isRunning = false;
    log("[Proposer] SHUTDOWN called");
  }

  @Override
  public void restart() {
    isRunning = true;
    localProposalCounter.set(0);
    log("[Proposer] RESTARTED");
  }

  @Override
  public boolean isRunning() {
    return isRunning;
  }

  /**
   * Generates a unique proposal ID using timestamp and a local counter.
   */
  private int generateProposalId() {
    long timestamp = System.currentTimeMillis();
    return (int) ((timestamp % Integer.MAX_VALUE) + localProposalCounter.getAndIncrement());
  }

  private int quorumSize() {
    return (acceptors.size() / 2) + 1;
  }

  private void log(String msg) {
    LoggerUtil.logToFile(msg, logFileName);
    if (debug) {
      System.out.println(msg);
    }
  }
}
