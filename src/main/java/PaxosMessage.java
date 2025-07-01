import java.util.concurrent.BlockingQueue;

/**
 * Represents a message used in the Paxos consensus protocol.
 *
 * <p>
 * {@code PaxosMessage} is exchanged between Paxos roles (Proposer, Acceptor, Learner) to coordinate
 * consensus on state-changing operations (e.g., PUT, DELETE) in a distributed system.
 * </p>
 */
public class PaxosMessage {

  public enum Type {PREPARE, PROMISE, ACCEPT, ACCEPTED, COMMIT}

  public Type type;
  public int proposalId;
  public String key;
  public String value;
  public String commandType;

  // Used to return messages back to Proposer
  public int proposerId;
  public String oldValue;
  public BlockingQueue<PaxosMessage> senderQueue;
  // public BlockingQueue<PaxosMessage> proposerCommitQueue;


  /**
   * Constructs a PaxosMessage with the essential fields for Paxos communication.
   *
   * @param type        The message type.
   * @param proposalId  The proposal ID.
   * @param key         The key involved in the proposal.
   * @param value       The value involved in the proposal.
   * @param commandType The type of operation (PUT or DELETE).
   */
  public PaxosMessage(Type type, int proposalId, String key, String value, String commandType) {
    this.type = type;
    this.proposalId = proposalId;
    this.key = key;
    this.value = value;
    this.commandType = commandType;
  }
}
