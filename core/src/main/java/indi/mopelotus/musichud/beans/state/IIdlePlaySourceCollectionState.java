package indi.mopelotus.musichud.beans.state;

import indi.mopelotus.musichud.interfaces.Unregister;

import java.util.function.Consumer;

public interface IIdlePlaySourceCollectionState {
    long getCollectionId();

    boolean isContained();

    void add();

    void remove();

    Unregister onOthersModify(Consumer<Boolean> listener);

    default void toggle() {
        if (isContained()) {
            remove();
        } else {
            add();
        }
    }
}
