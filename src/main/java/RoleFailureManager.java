import java.util.List;
import java.util.Random;

/**
 * {@code RoleFailureManager} is a generic failure injection tool that simulates random crashes and
 * restarts of Paxos roles (Proposers, Acceptors, or Learner) based on role-specific strategies.
 *
 * <p>
 * This class supports testing system resilience by selectively crashing roles while ensuring quorum
 * or leader availability where required.
 * </p>
 *
 * @param <T> A Paxos role implementing {@link FailureRole} (e.g., Proposer, Acceptor, Learner).
 */
public class RoleFailureManager<T extends FailureRole> implements Runnable {

  private final List<T> roleList;
  private final RoleType roleType;
  private final Random random = new Random();
  private final int quorumSize;
  private final int leaderIndex;

  // Role type
  public enum RoleType {
    PROPOSER, ACCEPTOR, LEARNER
  }

  /**
   * Constructs a {@code RoleFailureManager} for a specific Paxos role type.
   *
   * @param roleList The list of role instances.
   * @param roleType The type of Paxos role being managed.
   */
  public RoleFailureManager(List<T> roleList, RoleType roleType) {
    this.roleList = roleList;
    this.roleType = roleType;
    this.quorumSize = (roleList.size() / 2) + 1;
    this.leaderIndex = 0; // default leader for proposer
  }

  /**
   * Main loop that periodically crashes and asynchronously restarts role instances. Crash
   * strategies are determined by the role type (e.g., Proposer leader is protected).
   */
  @Override
  public void run() {
    while (true) {
      try {
        long aliveCount = roleList.stream().filter(FailureRole::isRunning).count();

        if (shouldCrash(aliveCount)) {
          int index;
          T role;

          // Proposer rule: should not crash leader
          do {
            index = random.nextInt(roleList.size());
            role = roleList.get(index);
          } while (!role.isRunning() || (roleType == RoleType.PROPOSER && index == leaderIndex));

          System.out.println("[" + roleType + "FailureManager] Crashing " + roleType + "-" + index);
          role.shutdown();

          // asyn restart
          final int idxCopy = index;
          final T roleCopy = role;
          new Thread(() -> {
            try {
              Thread.sleep(getCrashDuration());
              roleCopy.restart();
              System.out.println(
                  "[" + roleType + "FailureManager] Restarted " + roleType + "-" + idxCopy);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }).start();
        }

        Thread.sleep(5000 + random.nextInt(5000)); // 5-10 秒执行下一轮
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
  }

  // crash strategy for diff role
  private boolean shouldCrash(long aliveCount) {
    if (roleType == RoleType.ACCEPTOR) {
      return aliveCount > quorumSize; // keep quorumSize fulfill
    } else if (roleType == RoleType.PROPOSER) {
      return aliveCount > 1; // keep leader proposer
    } else {
      return aliveCount >= 1; // Learner one instance can crash
    }
  }

  // crash duration for diff role
  private long getCrashDuration() {
    if (roleType == RoleType.LEARNER) {
      return 1000 + random.nextInt(1000); // Learner crash 1-2 seconds
    } else {
      return 2000 + random.nextInt(2000); // others 2-4 seconds
    }
  }
}
