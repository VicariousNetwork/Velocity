/*
 * Copyright (C) 2018 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.connection.backend;

import com.velocitypowered.api.event.player.ServerLoginPluginMessageEvent;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.api.util.UuidUtils;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.PlayerInfoForwarding;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.ConnectionTypes;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.VelocityConstants;
import com.velocitypowered.proxy.connection.client.ClientTransitionSessionHandler;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.connection.forge.modern.ModernForgeConstants;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults;
import com.velocitypowered.proxy.connection.util.ConnectionRequestResults.Impl;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.packet.Disconnect;
import com.velocitypowered.proxy.protocol.packet.EncryptionRequest;
import com.velocitypowered.proxy.protocol.packet.LoginPluginMessage;
import com.velocitypowered.proxy.protocol.packet.LoginPluginResponse;
import com.velocitypowered.proxy.protocol.packet.ServerLoginSuccess;
import com.velocitypowered.proxy.protocol.packet.SetCompression;
import com.velocitypowered.proxy.util.except.QuietRuntimeException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import net.kyori.adventure.text.Component;

public class LoginSessionHandler implements MinecraftSessionHandler {

  private static final Component MODERN_IP_FORWARDING_FAILURE = Component
      .translatable("velocity.error.modern-forwarding-failed");

  private final VelocityServer server;
  private final VelocityServerConnection serverConn;
  private final CompletableFuture<Impl> resultFuture;
  private boolean informationForwarded;

  LoginSessionHandler(VelocityServer server, VelocityServerConnection serverConn,
      CompletableFuture<Impl> resultFuture) {
    this.server = server;
    this.serverConn = serverConn;
    this.resultFuture = resultFuture;
  }

  @Override
  public void activated() {
    /**
     * The following logic is used for handling the reset packet sent to the player when they transfer servers
     * and are on a modern forge (1.13+) environment.
     * During this time the client has to renegotiate the connection with the server as to ensure all the client side
     * registries (blocks, items etc.) are reset and then re-sent by the server. This ensures that all the registries
     * are correctly sync'd wit the server and as such all blocks placed, items used, (etc) will match the server
     * (when not matching this typically results in cases such as someone attempting to place mod A's block A but the
     * server thinking they attempted to place mod B's block B or something to this effect)
     * If there is an error by the server during this period then it is unrecoverable and as such the user should be
     * disconnected.
     */
    if (serverConn.getPlayer().getConnection().getType() == ConnectionTypes.MODERN_FORGE) {
      VelocityServerConnection existingConnection = serverConn.getPlayer().getConnectedServer();
      if (existingConnection != null && existingConnection != serverConn) {
        existingConnection.getPhase().onDepartForNewServer(existingConnection, serverConn.getPlayer());

        // Shut down the existing server connection.
        serverConn.getPlayer().setConnectedServer(null);
        existingConnection.disconnect();
      } else {
        serverConn.getPlayer().getPhase().resetConnectionPhase(serverConn.getPlayer());
      }

      serverConn.getPlayer().getConnection().setSessionHandler(
          new ClientTransitionSessionHandler(serverConn.getPlayer()));
    }
  }

  @Override
  public boolean handle(EncryptionRequest packet) {
    throw new IllegalStateException("Backend server is online-mode!");
  }

  @Override
  public boolean handle(LoginPluginMessage packet) {
    MinecraftConnection mc = serverConn.ensureConnected();
    VelocityConfiguration configuration = server.getConfiguration();
    if (configuration.getPlayerInfoForwardingMode() == PlayerInfoForwarding.MODERN
        && packet.getChannel().equals(VelocityConstants.VELOCITY_IP_FORWARDING_CHANNEL)) {
      ByteBuf forwardingData = createForwardingData(configuration.getForwardingSecret(),
          serverConn.getPlayerRemoteAddressAsString(),
          serverConn.getPlayer().getGameProfile());
      LoginPluginResponse response = new LoginPluginResponse(packet.getId(), true, forwardingData);
      mc.write(response);
      informationForwarded = true;
    } else if (packet.getChannel().equals(ModernForgeConstants.LOGIN_WRAPPER_CHANNEL)) {
      if (serverConn.getPhase().handle(serverConn, serverConn.getPlayer(), packet)) {
        return true;
      }
    } else {
      // Don't understand, fire event if we have subscribers
      if (!this.server.getEventManager().hasSubscribers(ServerLoginPluginMessageEvent.class)) {
        mc.write(new LoginPluginResponse(packet.getId(), false, Unpooled.EMPTY_BUFFER));
        return true;
      }

      final byte[] contents = ByteBufUtil.getBytes(packet.content());
      final MinecraftChannelIdentifier identifier = MinecraftChannelIdentifier
          .from(packet.getChannel());
      this.server.getEventManager().fire(new ServerLoginPluginMessageEvent(serverConn, identifier,
          contents, packet.getId()))
          .thenAcceptAsync(event -> {
            if (event.getResult().isAllowed()) {
              mc.write(new LoginPluginResponse(packet.getId(), true, Unpooled
                  .wrappedBuffer(event.getResult().getResponse())));
            } else {
              mc.write(new LoginPluginResponse(packet.getId(), false, Unpooled.EMPTY_BUFFER));
            }
          }, mc.eventLoop());
    }
    return true;
  }

  @Override
  public boolean handle(Disconnect packet) {
    resultFuture.complete(ConnectionRequestResults.forDisconnect(packet, serverConn.getServer()));
    serverConn.disconnect();
    return true;
  }

  @Override
  public boolean handle(SetCompression packet) {
    serverConn.ensureConnected().setCompressionThreshold(packet.getThreshold());
    return true;
  }

  @Override
  public boolean handle(ServerLoginSuccess packet) {
    if (server.getConfiguration().getPlayerInfoForwardingMode() == PlayerInfoForwarding.MODERN
        && !informationForwarded) {
      resultFuture.complete(ConnectionRequestResults.forDisconnect(MODERN_IP_FORWARDING_FAILURE,
          serverConn.getServer()));
      serverConn.disconnect();
      return true;
    }

    // The player has been logged on to the backend server, but we're not done yet. There could be
    // other problems that could arise before we get a JoinGame packet from the server.

    // Move into the PLAY phase.
    MinecraftConnection smc = serverConn.ensureConnected();
    smc.setState(StateRegistry.PLAY);

    ConnectedPlayer player = serverConn.getPlayer();
    MinecraftConnection pmc = player.getConnection();

    serverConn.getPhase().onLoginSuccess(serverConn, player);

    if (pmc.getState() == StateRegistry.LOGIN) {
      VelocityConfiguration configuration = server.getConfiguration();
      UUID playerUniqueId = player.getUniqueId();
      if (configuration.getPlayerInfoForwardingMode() == PlayerInfoForwarding.NONE) {
        playerUniqueId = UuidUtils.generateOfflinePlayerUuid(player.getUsername());
      }
      ServerLoginSuccess success = new ServerLoginSuccess();
      success.setUsername(player.getUsername());
      success.setUuid(playerUniqueId);
      pmc.write(success);

      pmc.setState(StateRegistry.PLAY);
    }

    // Switch to the transition handler.
    smc.setSessionHandler(new BackendTransitionSessionHandler(server, serverConn, resultFuture));
    return true;
  }

  @Override
  public void exception(Throwable throwable) {
    resultFuture.completeExceptionally(throwable);
  }

  @Override
  public void disconnected() {
    if (server.getConfiguration().getPlayerInfoForwardingMode() == PlayerInfoForwarding.LEGACY) {
      resultFuture.completeExceptionally(
          new QuietRuntimeException("The connection to the remote server was unexpectedly closed.\n"
              + "This is usually because the remote server does not have BungeeCord IP forwarding "
              + "correctly enabled.\nSee https://velocitypowered.com/wiki/users/forwarding/ "
              + "for instructions on how to configure player info forwarding correctly.")
      );
    } else {
      resultFuture.completeExceptionally(
          new QuietRuntimeException("The connection to the remote server was unexpectedly closed.")
      );
    }
  }

  private static ByteBuf createForwardingData(byte[] hmacSecret, String address,
      GameProfile profile) {
    ByteBuf forwarded = Unpooled.buffer(2048);
    try {
      ProtocolUtils.writeVarInt(forwarded, VelocityConstants.FORWARDING_VERSION);
      ProtocolUtils.writeString(forwarded, address);
      ProtocolUtils.writeUuid(forwarded, profile.getId());
      ProtocolUtils.writeString(forwarded, profile.getName());
      ProtocolUtils.writeProperties(forwarded, profile.getProperties());

      SecretKey key = new SecretKeySpec(hmacSecret, "HmacSHA256");
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      mac.update(forwarded.array(), forwarded.arrayOffset(), forwarded.readableBytes());
      byte[] sig = mac.doFinal();

      return Unpooled.wrappedBuffer(Unpooled.wrappedBuffer(sig), forwarded);
    } catch (InvalidKeyException e) {
      forwarded.release();
      throw new RuntimeException("Unable to authenticate data", e);
    } catch (NoSuchAlgorithmException e) {
      // Should never happen
      forwarded.release();
      throw new AssertionError(e);
    }
  }
}
