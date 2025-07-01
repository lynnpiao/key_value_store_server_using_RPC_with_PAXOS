package utils;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * Represents a structured response from the server.
 */
@XmlRootElement(name = "utils.Response")
public class Response {

  private String message;

  // No-argument constructor required for JAXB
  public Response() {
  }

  public Response(String message) {
    this.message = message;
  }

  @XmlElement
  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }
}
