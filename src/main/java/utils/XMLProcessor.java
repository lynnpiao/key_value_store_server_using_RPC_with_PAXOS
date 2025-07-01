package utils;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Remote interface for processing XML requests.
 */
public interface XMLProcessor extends Remote {

  /**
   * Processes an XML request and returns an XML response.
   *
   * @param xmlRequest the XML request string.
   * @return the XML response string.
   * @throws RemoteException if a remote communication error occurs.
   */
  String processXML(String xmlRequest) throws RemoteException;
}
