package indi.mopelotus.musichud.interfaces;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.api.AutoConnectServerFilterType;
import indi.mopelotus.musichud.beans.music.Quality;
import indi.mopelotus.musichud.beans.music.ScrobbleMode;
import indi.mopelotus.musichud.beans.user.ProfileConfigData;
import indi.mopelotus.musichud.platform.Environment;

import java.util.List;
import java.util.function.Supplier;

public interface ClientConfig {
    static ClientConfig getInstance() {
        Environment.Platform platform = MusicHud.getCurrentEnvironment().getPlatform();
        Supplier<ClientConfig> supplier = platform.getClientConfigSupplier();
        if (supplier != null) {
            ClientConfig clientConfig = supplier.get();
            if (clientConfig != null) {
                return clientConfig;
            }
        }
        throw new UnsupportedOperationException();
    }

    boolean getEnable();

    void setEnable(boolean enable);

    boolean getShowTranslatedCnLyrics();

    void setShowTranslatedCnLyrics(boolean showTranslatedCnLyrics);

    boolean getDisableVanillaMusic();

    void setDisableVanillaMusic(boolean disableVanillaMusic);

    boolean getHideHudWhenNotPlaying();

    void setHideHudWhenNotPlaying(boolean hideHudWhenNotPlaying);

    boolean getEnableHud();

    void setEnableHud(boolean enableHud);

    Quality getPrimaryChosenQuality();

    void setPrimaryChosenQuality(Quality primaryChosenQuality);

    default indi.mopelotus.musichud.beans.music.AudioOutputMode getAudioOutputMode() { return indi.mopelotus.musichud.beans.music.AudioOutputMode.MULTICHANNEL; }
    default void setAudioOutputMode(indi.mopelotus.musichud.beans.music.AudioOutputMode mode) {}

    ScrobbleMode getScrobbleMode();
    void setScrobbleMode(ScrobbleMode mode);

    String getHudVerticalPosition();

    void setHudVerticalPosition(String hudVerticalPosition);

    boolean getMixWithVanillaSoundVolume();

    void setMixWithVanillaSoundVolume(boolean mixWithVanillaSoundVolume);

    boolean getMuted();

    void setMuted(boolean muted);

    int getSoundVolume();

    void setSoundVolume(int soundVolume);

    void forceSetSoundVolume(int soundVolume);

    int getSoundVolumeInterval();

    void setSoundVolumeInterval(int soundVolumeInterval);

    String getHudHorizontalPosition();

    void setHudHorizontalPosition(String hudHorizontalPosition);

    int getHudOffsetX();

    void setHudOffsetX(int hudOffsetX);

    int getHudOffsetY();

    void setHudOffsetY(int hudOffsetY);

    int getHudWidth();

    void setHudWidth(int hudWidth);

    int getHudHeight();

    void setHudHeight(int hudHeight);

    int getHudCornerRadius();

    void setHudCornerRadius(int hudCornerRadius);

    ProfileConfigData getClientAccountConfig();

    void setClientAccountConfig(ProfileConfigData clientAccountConfig);

    String getTuneWeaveBaseUrl();

    void setTuneWeaveBaseUrl(String baseUrl);

    String getTuneWeaveCredential(String platform);

    void setTuneWeaveCredential(String platform, String credential);

    void clearTuneWeaveCredential(String platform);

    String getDefaultMusicPlatform();

    void setDefaultMusicPlatform(String platform);

    boolean getEnabledInIntegratedServer();

    void setEnabledInIntegratedServer(boolean enabledInIntegratedServer);

    boolean getEnableAutoConnect();

    void setEnableAutoConnect(boolean autoConnect);

    boolean getEnableIsolatedMode();

    void setEnableIsolatedMode(boolean autoConnect);

    AutoConnectServerFilterType getConnectServerFilterType();

    void setConnectServerFilterType(AutoConnectServerFilterType autoConnectServerFilterType);

    List<String> getBlackList();

    void setBlackList(List<String> blackList);

    List<String> getWhiteList();

    void setWhiteList(List<String> whiteList);

    void save();

    boolean isConfigured();

    void setConfigured(boolean configured);

    double getMainScreenAdditionalBackgroundDarken();

    void setMainScreenAdditionalBackgroundDarken(double additionalBackgroundDarken);

    double getHudBackgroundMixAlpha();

    void setHudBackgroundMixAlpha(double hudBackgroundMixAlpha);

    boolean getEnableMarqueeText();

    void setEnableMarqueeText(boolean aBoolean);

    // Reset values are shared by the configuration backend and the upstream preference controls.
    default boolean getDefaultEnable() { return true; }
    default boolean getDefaultShowTranslatedCnLyrics() { return true; }
    default boolean getDefaultDisableVanillaMusic() { return true; }
    default boolean getDefaultHideHudWhenNotPlaying() { return true; }
    default boolean getDefaultEnableHud() { return true; }
    default boolean getDefaultEnableMarqueeText() { return true; }
    default boolean getDefaultEnableLyricsSidebar() { return true; }
    default boolean getDefaultMixWithVanillaSoundVolume() { return true; }
    default int getDefaultSoundVolume() { return 100; }
    default int getDefaultSoundVolumeInterval() { return 10; }
    default Quality getDefaultPrimaryChosenQuality() { return Quality.LOSSLESS; }
    default ScrobbleMode getDefaultScrobbleMode() { return ScrobbleMode.ONLY_SELF; }
    default double getDefaultMainScreenAdditionalBackgroundDarken() { return 0.5; }
    default double getDefaultHudBackgroundMixAlpha() { return 0.5; }
    default String getDefaultHudVerticalPosition() { return "TOP"; }
    default String getDefaultHudHorizontalPosition() { return "LEFT"; }
    default int getDefaultHudOffsetX() { return 16; }
    default int getDefaultHudOffsetY() { return 16; }
    default int getDefaultHudWidth() { return 152; }
    default int getDefaultHudHeight() { return 52; }
    default int getDefaultHudCornerRadius() { return 8; }
    default boolean getDefaultEnabledInIntegratedServer() { return true; }
    default boolean getDefaultEnableAutoConnect() { return true; }
    default boolean getDefaultEnableIsolatedMode() { return true; }
    default AutoConnectServerFilterType getDefaultConnectServerFilterType() { return AutoConnectServerFilterType.BLACK_LIST; }
    default List<String> getDefaultBlackList() { return List.of(); }
    default List<String> getDefaultWhiteList() { return List.of(); }

    boolean getEnableLyricsSidebar();
    void setEnableLyricsSidebar(boolean enabled);
}
