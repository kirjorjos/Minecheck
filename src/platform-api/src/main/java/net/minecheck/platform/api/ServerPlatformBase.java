package net.minecheck.platform.api;

/**
 * The shared implementations {@link ServerPlatform} used to carry as {@code default} methods. See
 * {@link ClientPlatformBase} for why they live in a superclass.
 */
public abstract class ServerPlatformBase implements ServerPlatform {

  @Override
  public boolean isWorldGenSettled() {
    return true;
  }
}
