

public interface FailureRole {

  void shutdown();

  void restart();

  boolean isRunning();
}