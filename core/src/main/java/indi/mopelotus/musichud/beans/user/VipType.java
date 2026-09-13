package indi.mopelotus.musichud.beans.user;

import indi.mopelotus.musichud.interfaces.IntegerCodeEnum;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.Codecs;
import lombok.Getter;

public enum VipType implements IntegerCodeEnum {
    NORMAL(0), ALL_ACCESS(-1/*unknown*/), VIP(11), SVIP(-2/*unknown*/);
    public static final ByteBufCodec<VipType> STREAM_CODEC = Codecs.ofEnum(VipType.class);

    @Getter
    private final int code;
    VipType(int code) {
        this.code = code;
    }
}
