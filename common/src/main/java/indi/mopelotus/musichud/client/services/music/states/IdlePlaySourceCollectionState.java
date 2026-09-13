package indi.mopelotus.musichud.client.services.music.states;

import indi.mopelotus.musichud.beans.music.MusicCollection;
import indi.mopelotus.musichud.beans.state.IIdlePlaySourceCollectionState;
import indi.mopelotus.musichud.beans.state.IIdlePlaySourceLayerState;
import indi.mopelotus.musichud.interfaces.Unregister;

import java.util.function.Consumer;

public class IdlePlaySourceCollectionState implements IIdlePlaySourceCollectionState {
    private final IIdlePlaySourceLayerState layer;
    private final MusicCollection collection;

    public IdlePlaySourceCollectionState(IIdlePlaySourceLayerState layer, MusicCollection collection) {
        this.layer = layer;
        this.collection = collection;
    }

    @Override
    public long getCollectionId() {
        return collection.getId();
    }

    @Override
    public boolean isContained() {
        return layer.getSources().stream().anyMatch(c -> c.getId() == collection.getId());
    }

    @Override
    public void add() {
        layer.add(collection);
    }

    @Override
    public void remove() {
        layer.remove(collection);
    }

    @Override
    public Unregister onOthersModify(Consumer<Boolean> listener) {
        return layer.onChange(c -> {
            if (c.getId() == collection.getId()) {
                listener.accept(isContained());
            }
        });
    }
}
