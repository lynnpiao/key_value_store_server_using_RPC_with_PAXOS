import java.rmi.Remote;
import java.rmi.RemoteException;
import utils.XMLProcessor;

/**
 * RMI interface for Paxos-based Key-Value Store operations.
 */
public interface KeyValueWithPaxos extends Remote, XMLProcessor {

  /**
   * Handles a PREPARE message from a Proposer.
   *
   * @param prepare The PREPARE message.
   * @throws RemoteException RMI communication error.
   */
  void receivePrepare(PaxosMessage prepare) throws RemoteException;

  /**
   * Handles an ACCEPT message from a Proposer.
   *
   * @param accept The ACCEPT message.
   * @throws RemoteException RMI communication error.
   */
  void receiveAccept(PaxosMessage accept) throws RemoteException;
}
