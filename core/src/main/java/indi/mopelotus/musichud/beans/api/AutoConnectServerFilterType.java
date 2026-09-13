package indi.mopelotus.musichud.beans.api;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.platform.Environment;
import indi.mopelotus.musichud.utils.IClientDistUtil;

public enum AutoConnectServerFilterType{
    BLACK_LIST, WHITE_LIST;

    @Override
    public String toString() {
        Environment.Side side = MusicHud.getCurrentEnvironment().getSide();
        if (side == Environment.Side.CLIENT) {
            return IClientDistUtil.getInstance().getI18n(MusicHud.MOD_ID + ".config.externalServer.serverFilterType." + this.name());
        } else {
            return this.name();
        }
    }
}
