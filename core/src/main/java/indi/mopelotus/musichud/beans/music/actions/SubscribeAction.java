package indi.mopelotus.musichud.beans.music.actions;

import indi.mopelotus.musichud.interfaces.IntegerCodeEnum;
import lombok.Getter;

public enum SubscribeAction implements IntegerCodeEnum {
    SUBSCRIBE(1), UNSUBSCRIBE(2);

    @Getter
    private final int code;

    SubscribeAction(int code) {
        this.code = code;
    }
}
