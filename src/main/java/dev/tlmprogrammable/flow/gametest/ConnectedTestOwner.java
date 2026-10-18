package dev.tlmprogrammable.flow.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraftforge.common.util.FakePlayer;

import java.util.UUID;

/** A public-API login with an in-memory transport, restricted to the disposable GameTest server. */
final class ConnectedTestOwner implements AutoCloseable {
    final FakePlayer player;
    private final SilentConnection connection;
    private boolean closed;

    ConnectedTestOwner(GameTestHelper helper) {
        if (!(helper.getLevel().getServer() instanceof GameTestServer)) {
            helper.fail("Connected test players are allowed only in an isolated GameTestServer");
            throw new IllegalStateException("Expected isolated GameTestServer");
        }
        connection = new SilentConnection();
        // Avoid FakePlayerFactory's per-level cache: this test owns and disposes this instance.
        player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), "TlmTestOwner"));
        try {
            helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player);
            helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayer(player.getUUID()) == player,
                    "Test owner must be registered through the real PlayerList login path");
        } catch (RuntimeException | Error exception) {
            close();
            throw exception;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            var players = player.serverLevel().getServer().getPlayerList();
            if (players.getPlayer(player.getUUID()) == player) players.remove(player);
            player.discard();
        } finally {
            connection.memory.finishAndReleaseAll();
        }
    }

    /** No socket, handshake endpoint, or external recipient is created. */
    private static final class SilentConnection extends Connection {
        private final EmbeddedChannel memory = new EmbeddedChannel();

        private SilentConnection() { super(PacketFlow.SERVERBOUND); }

        @Override public Channel channel() { return memory; }
        @Override public boolean isConnected() { return memory.isOpen(); }
        @Override public void send(Packet<?> packet) { }
        @Override public void send(Packet<?> packet, PacketSendListener listener) { }
    }
}

