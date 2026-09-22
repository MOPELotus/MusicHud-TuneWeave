package indi.mopelotus.musichud.client.services.music.states;

import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.beans.music.MusicCollection;
import indi.mopelotus.musichud.beans.music.Playlist;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ExternalIdlePlaySourceState extends AbstractIdlePlaySourceLayerState {
    private final java.util.function.Supplier<java.util.UUID> localPlayer;

    public ExternalIdlePlaySourceState() {
        this(() -> {
            Player player = Minecraft.getInstance().player;
            return player == null ? null : player.getUUID();
        });
    }

    ExternalIdlePlaySourceState(java.util.function.Supplier<java.util.UUID> localPlayer) {
        this.localPlayer = localPlayer;
    }

    @Override
    public synchronized void updateAll(List<Playlist> playlistSources, List<Album> albumSources) {
        java.util.UUID self = localPlayer.get();
        var fresh = new java.util.ArrayList<MusicCollection>();
        fresh.addAll(playlistSources); fresh.addAll(albumSources);
        fresh.removeIf(source -> source.getPusherInfo() == null
                || source.getPusherInfo().equals(indi.mopelotus.musichud.beans.music.PusherInfo.EMPTY)
                || source.getPusherInfo().getPlayerUUID().equals(self));
        Set<MusicCollection> previous = Set.copyOf(sources);
        Set<MusicCollection> toRemove = new HashSet<>();
        Set<MusicCollection> toAdd = new HashSet<>();
        for (MusicCollection old : previous) {
            if (fresh.stream().noneMatch(next -> sameSource(old, next))) toRemove.add(old);
        }
        for (MusicCollection next : fresh) {
            if (previous.stream().noneMatch(old -> sameSource(old, next))) toAdd.add(next);
        }
        sources.removeAll(toRemove);
        sources.addAll(toAdd);
        toRemove.forEach(musicCollection -> {
            notifyChange(musicCollection);
            notifyRemove(musicCollection);
        });
        toAdd.forEach(musicCollection -> {
            notifyChange(musicCollection);
            notifyAdd(musicCollection);
        });
    }

    private static boolean sameSource(MusicCollection a, MusicCollection b) {
        return a.equalsLoose(b) && java.util.Objects.equals(a.getPusherInfo(), b.getPusherInfo())
                && java.util.Objects.equals(a.getPusherInfo().getPlayerName(), b.getPusherInfo().getPlayerName());
    }

    @Override
    public synchronized void reset() {
        Set<MusicCollection> previous = Set.copyOf(sources);
        sources.clear();
        previous.forEach(collection -> {
            notifyChange(collection);
            notifyRemove(collection);
        });
    }
}
