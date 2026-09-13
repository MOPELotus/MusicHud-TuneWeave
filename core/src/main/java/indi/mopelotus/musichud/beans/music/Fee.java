package indi.mopelotus.musichud.beans.music;

import indi.mopelotus.musichud.interfaces.IntegerCodeEnum;
import lombok.Getter;

public enum Fee implements IntegerCodeEnum {
    FREE(0), VIP(1), SEPARATELY_PURCHASE(4), VIP_FOR_HIGHER_QUALITY(8),UNSET(-1);

    @Getter
    final int code;
    Fee(int code) {
        this.code = code;
    }
}
