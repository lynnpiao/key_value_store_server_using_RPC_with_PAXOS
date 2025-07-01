import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * MultiRoleFailureManager manages the failure injection of Proposers, Acceptors, and Learner within
 * a single thread. It applies role-specific crash strategies for each Paxos component.
 */
public class MultiRoleFailureManager implements Runnable {

  private final List<Proposer> proposerList;
  private final List<Acceptor> acceptorList;
  private final Learner learner;
  private final Random random = new Random();
  private final int proposerLeaderIndex = 0;
  private final int quorumSize;

  /**
   * Constructs a {@code MultiRoleFailureManager} to handle failure injection across all Paxos
   * roles.
   *
   * @param proposerList List of Proposers.
   * @param acceptorList List of Acceptors.
   * @param learner      Learner instance.
   */
  public MultiRoleFailureManager(List<Proposer> proposerList,
      List<Acceptor> acceptorList,
      Learner learner) {
    this.proposerList = proposerList;
    this.acceptorList = acceptorList;
    this.learner = learner;
    this.quorumSize = (acceptorList.size() / 2) + 1;
  }

  /**
   * Periodically triggers crash/restart cycles for Proposers, Acceptors, and the Learner every 5 to
   * 10 seconds.
   */
  @Override
  public void run() {
    while (true) {
      try {
        // try to crash proposer, acceptor, learner; the crash order is random
        List<Runnable> crashTasks = List.of(
            this::crashProposerIfNeeded,
            this::crashAcceptorIfNeeded,
            this::crashLearnerIfNeeded
        );
        Collections.shuffle(crashTasks); // randomize order
        crashTasks.forEach(Runnable::run);

        Thread.sleep(5000 + random.nextInt(5000)); // 5-10 seconds for next round
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
  }

  private void crashProposerIfNeeded() {
    long alive = proposerList.stream().filter(Proposer::isRunning).count();
    if (alive > 1 && random.nextDouble() < 0.7) { // keep leader alive
      int index;
      do {
        index = random.nextInt(proposerList.size());
      } while (index == proposerLeaderIndex || !proposerList.get(index).isRunning());

      Proposer proposer = proposerList.get(index);
      proposer.shutdown();
      System.out.println("[MultiRoleFailureManager] Crashed Proposer-" + index);

      final int idxCopy = index;
      new Thread(() -> restartAfterDelay(proposer, "Proposer-" + idxCopy, 2000, 4000)).start();
    }
  }

  private void crashAcceptorIfNeeded() {
    long alive = acceptorList.stream().filter(Acceptor::isRunning).count();
    if (alive > quorumSize && random.nextDouble() < 0.5) { // decrease the acceptor crash prob &  insure quorumSize
      int index;
      do {
        index = random.nextInt(acceptorList.size());
      } while (!acceptorList.get(index).isRunning());

      Acceptor acceptor = acceptorList.get(index);
      acceptor.shutdown();
      System.out.println("[MultiRoleFailureManager] Crashed Acceptor-" + index);

      final int idxCopy = index;

      new Thread(() -> restartAfterDelay(acceptor, "Acceptor-" + idxCopy, 2000, 4000)).start();
    }
  }

  private void crashLearnerIfNeeded() {
    if (learner.isRunning() && random.nextDouble() < 0.1) { // decrease the learner crash prob
      learner.shutdown();
      System.out.println("[MultiRoleFailureManager] Crashed Learner");

      new Thread(() -> restartAfterDelay(learner, "Learner", 1000, 2000)).start();
    }
  }

  private void restartAfterDelay(FailureRole role, String roleName, int minDelay, int maxDelay) {
    try {
      Thread.sleep(minDelay + random.nextInt(maxDelay - minDelay));
      role.restart();
      System.out.println("[MultiRoleFailureManager] Restarted " + roleName);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
