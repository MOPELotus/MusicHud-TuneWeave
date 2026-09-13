package indi.mopelotus.musichud.network.payloads.pushMessages.c2s;

import indi.mopelotus.musichud.beans.api.IdlePlaySource;
import indi.mopelotus.musichud.beans.music.PusherInfo;
import indi.mopelotus.musichud.beans.music.MusicCollection;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.interfaces.CommonRegister;
import indi.mopelotus.musichud.interfaces.RegisterMark;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.INetworkRegister;
import indi.mopelotus.musichud.network.payloads.C2SPayload;
import indi.mopelotus.musichud.server.api.MusicPlayerServerService;
import indi.mopelotus.musichud.utils.ServerDataPacketVThreadExecutor;

public record AddToIdlePlaySourceMessage(IdlePlaySource idlePlaySource, MusicCollection collection) implements C2SPayload {
    public AddToIdlePlaySourceMessage {
        if (idlePlaySource == null || collection == null || idlePlaySource.getId() != collection.getId()
                || idlePlaySource.getType() != collection.getClass()) throw new IllegalArgumentException("Idle source identity mismatch");
    }
    public static final ByteBufCodec<AddToIdlePlaySourceMessage> CODEC = new ByteBufCodec<>() {
        @Override public void encode(io.netty.buffer.ByteBuf buffer, AddToIdlePlaySourceMessage value) {
            IdlePlaySource.CODEC.encode(buffer, value.idlePlaySource());
            if (value.collection() instanceof Playlist playlist) Playlist.CODEC.encode(buffer, playlist);
            else Album.CODEC.encode(buffer, (Album) value.collection());
        }
        @Override public AddToIdlePlaySourceMessage decode(io.netty.buffer.ByteBuf buffer) {
            IdlePlaySource source = IdlePlaySource.CODEC.decode(buffer);
            MusicCollection collection = source.getType() == Playlist.class ? Playlist.CODEC.decode(buffer) : Album.CODEC.decode(buffer);
            return new AddToIdlePlaySourceMessage(source, collection);
        }
    };

    @RegisterMark
    public static class RegisterImpl implements CommonRegister {
        public void register() {
            INetworkRegister.getInstance().autoRegisterPayload(
                    AddToIdlePlaySourceMessage.class, CODEC,
                    ServerDataPacketVThreadExecutor.execute((message, player) -> {
                        IdlePlaySource idlePlaySource = message.idlePlaySource;
                        MusicPlayerServerService.getInstance().addIdlePlaySource(message.collection(), idlePlaySource.getMode(), PusherInfo.ofPlayer(player));
                    })
            );
        }
    }
}
