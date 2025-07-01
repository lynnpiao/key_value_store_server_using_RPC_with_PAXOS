package utils;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

/**
 * Represents a request from client (command).
 */
@XmlRootElement(name = "utils.Request")
public class Request {

  private String type;
  private String key;
  private String value;

  // No-argument constructor for XML deserialization
  public Request() {
  }

  public Request(String type, String key, String value) {
    this.type = type;
    this.key = key;
    this.value = value;
  }

  @XmlElement
  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  @XmlElement
  public String getKey() {
    return key;
  }

  public void setKey(String key) {
    this.key = key;
  }

  @XmlElement
  public String getValue() {
    return value;
  }

  public void setValue(String value) {
    this.value = value;
  }
}
