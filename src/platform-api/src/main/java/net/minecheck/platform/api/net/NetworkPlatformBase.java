package net.minecheck.platform.api.net;

import java.util.List;
import java.util.function.Consumer;

/**
 * The shared implementations {@link NetworkPlatform} used to carry as {@code default} methods. See
 * {@code net.minecheck.platform.api.ClientPlatformBase} for why they live in a superclass.
 */
public abstract class NetworkPlatformBase implements NetworkPlatform {

  @Override
  public boolean isAvailable() {
    return false;
  }

  @Override
  public boolean sendTo(String playerName, String name, List<PacketValue> args) {
    return false;
  }

  @Override
  public boolean observeChannel(String channel, Consumer<byte[]> receiver) {
    return false;
  }
}
