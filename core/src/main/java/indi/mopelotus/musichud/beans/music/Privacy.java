package indi.mopelotus.musichud.beans.music;

import indi.mopelotus.musichud.interfaces.IntegerCodeEnum;
import lombok.Getter;

public enum Privacy implements IntegerCodeEnum {
    PUBLIC(0), PRIVATE(10);

    @Getter
    final int code;
    Privacy(int i) {
        code = i;
    }
}
