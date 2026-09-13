package indi.mopelotus.musichud.platform.mod.config;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.ProjectIdentity;
import indi.mopelotus.musichud.beans.api.AutoConnectServerFilterType;
import indi.mopelotus.musichud.beans.music.Quality;
import indi.mopelotus.musichud.beans.music.ScrobbleMode;
import indi.mopelotus.musichud.beans.music.AudioOutputMode;
import indi.mopelotus.musichud.beans.user.ProfileConfigData;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.utils.JsonUtil;
import lombok.Getter;
import lombok.Setter;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClientConfigDefinition implements ClientConfig {
    private static final String FILE_NAME = ProjectIdentity.CONFIG_PREFIX + "-client.toml";
    @Getter
    private static final ClientConfigDefinition instance = new ClientConfigDefinition();

    private boolean enable = true;
    private boolean showTranslatedCnLyrics = true;
    private boolean disableVanillaMusic = true;
    private boolean hideHudWhenNotPlaying = true;
    private boolean enableHud = true;
    private boolean enableMarqueeText = true;
    private boolean mixWithVanillaSoundVolume = true;
    private boolean muted;
    private int soundVolume = 100;
    private int soundVolumeInterval = 10;
    private Quality primaryChosenQuality = Quality.LOSSLESS;
    private volatile ScrobbleMode scrobbleMode = ScrobbleMode.ONLY_SELF;
    private volatile AudioOutputMode audioOutputMode = AudioOutputMode.MULTICHANNEL;
    private double mainScreenAdditionalBackgroundDarken = 0.5;
    private double hudBackgroundMixAlpha = 0.5;
    private String hudVerticalPosition = "TOP";
    private String hudHorizontalPosition = "LEFT";
    private int hudOffsetX = 16;
    private int hudOffsetY = 16;
    private int hudWidth = 152;
    private int hudHeight = 52;
    private int hudCornerRadius = 8;
    private String clientAccountConfig = "";
    private String tuneWeaveBaseUrl = "http://127.0.0.1:7832";
    private String tuneWeaveCredentials = "{}";
    private String defaultMusicPlatform = "netease";
    private boolean enabledInIntegratedServer = true;
    private boolean enableAutoConnect = true;
    private boolean enableIsolatedMode = true;
    private AutoConnectServerFilterType connectServerFilterType = AutoConnectServerFilterType.BLACK_LIST;
    private String autoConnectBlackList = "[]";
    private String autoConnectWhiteList = "[]";
    @Setter
    @Getter
    private boolean configured;

    private ClientConfigDefinition() {
    }

    public synchronized void load() {
        Path path = SimpleTomlConfig.path(FILE_NAME);
        Map<String, String> values = SimpleTomlConfig.read(path);
        enable = SimpleTomlConfig.getBoolean(values, "enable", enable);
        showTranslatedCnLyrics = SimpleTomlConfig.getBoolean(values, "showTranslatedCnLyrics", showTranslatedCnLyrics);
        disableVanillaMusic = SimpleTomlConfig.getBoolean(values, "disableVanillaMusic", disableVanillaMusic);
        hideHudWhenNotPlaying = SimpleTomlConfig.getBoolean(values, "hideHudWhenNotPlaying", hideHudWhenNotPlaying);
        enableHud = SimpleTomlConfig.getBoolean(values, "enableHud", enableHud);
        enableMarqueeText = SimpleTomlConfig.getBoolean(values, "enableMarqueeText", enableMarqueeText);
        mixWithVanillaSoundVolume = SimpleTomlConfig.getBoolean(values, "mixWithVanillaSoundVolume", mixWithVanillaSoundVolume);
        muted = SimpleTomlConfig.getBoolean(values, "muted", SimpleTomlConfig.getBoolean(values, "Muted", muted));
        soundVolume = SimpleTomlConfig.getInt(values, "soundVolume", soundVolume);
        soundVolumeInterval = SimpleTomlConfig.getInt(values, "soundVolumeInterval", soundVolumeInterval);
        primaryChosenQuality = SimpleTomlConfig.getEnum(values, "primaryChosenQuality", Quality.class, primaryChosenQuality);
        scrobbleMode = ScrobbleMode.parse(SimpleTomlConfig.getString(values, "scrobbleMode", scrobbleMode.name()));
        audioOutputMode = AudioOutputMode.parse(SimpleTomlConfig.getString(values, "audioOutputMode", audioOutputMode.name()));
        mainScreenAdditionalBackgroundDarken = SimpleTomlConfig.getDouble(values, "mainScreenAdditionalBackgroundDarken", mainScreenAdditionalBackgroundDarken);
        hudBackgroundMixAlpha = SimpleTomlConfig.getDouble(values, "hudBackgroundMixAlpha", hudBackgroundMixAlpha);
        hudVerticalPosition = SimpleTomlConfig.getString(values, "verticalPosition", hudVerticalPosition);
        hudHorizontalPosition = SimpleTomlConfig.getString(values, "horizontalPosition", hudHorizontalPosition);
        hudOffsetX = SimpleTomlConfig.getInt(values, "hudOffsetX", hudOffsetX);
        hudOffsetY = SimpleTomlConfig.getInt(values, "hudOffsetY", hudOffsetY);
        hudWidth = SimpleTomlConfig.getInt(values, "hudWidth", hudWidth);
        hudHeight = SimpleTomlConfig.getInt(values, "hudHeight", hudHeight);
        hudCornerRadius = SimpleTomlConfig.getInt(values, "hudCornerRadius", hudCornerRadius);
        clientAccountConfig = SimpleTomlConfig.getString(values, "clientAccountConfig", clientAccountConfig);
        tuneWeaveBaseUrl = normalizeTuneWeaveBaseUrl(SimpleTomlConfig.getString(
                values, "tuneWeaveBaseUrl", tuneWeaveBaseUrl));
        tuneWeaveCredentials = SimpleTomlConfig.getString(values, "tuneWeaveCredentials", tuneWeaveCredentials);
        defaultMusicPlatform = normalizePlatform(SimpleTomlConfig.getString(
                values, "defaultMusicPlatform", defaultMusicPlatform));
        enabledInIntegratedServer = SimpleTomlConfig.getBoolean(
                values,
                "enabledInIntegratedServer",
                SimpleTomlConfig.getBoolean(values, "enableEmbeddedServer", enabledInIntegratedServer)
        );
        enableAutoConnect = SimpleTomlConfig.getBoolean(values, "enableAutoConnect", enableAutoConnect);
        enableIsolatedMode = SimpleTomlConfig.getBoolean(values, "enableClientOnlyMode", enableIsolatedMode);
        connectServerFilterType = SimpleTomlConfig.getEnum(values, "autoConnectServerFilterType", AutoConnectServerFilterType.class, connectServerFilterType);
        autoConnectBlackList = SimpleTomlConfig.getString(values, "autoConnectBlackList", autoConnectBlackList);
        autoConnectWhiteList = SimpleTomlConfig.getString(values, "autoConnectWhiteList", autoConnectWhiteList);
        migrateEmptyWhiteListDefault(values);
        configured = true;
        save();
    }

    @Override
    public void setEnable(boolean enable) {
        this.enable = enable;
    }

    @Override
    public void setShowTranslatedCnLyrics(boolean showTranslatedCnLyrics) {
        this.showTranslatedCnLyrics = showTranslatedCnLyrics;
    }

    @Override
    public void setDisableVanillaMusic(boolean disableVanillaMusic) {
        this.disableVanillaMusic = disableVanillaMusic;
    }

    @Override
    public void setHideHudWhenNotPlaying(boolean hideHudWhenNotPlaying) {
        this.hideHudWhenNotPlaying = hideHudWhenNotPlaying;
    }

    @Override
    public void setEnableHud(boolean enableHud) {
        this.enableHud = enableHud;
    }

    @Override
    public void setMixWithVanillaSoundVolume(boolean mixWithVanillaSoundVolume) {
        this.mixWithVanillaSoundVolume = mixWithVanillaSoundVolume;
    }

    @Override
    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    @Override
    public void setSoundVolume(int soundVolume) {
        if (soundVolume == 0) {
            muted = true;
            return;
        }
        muted = false;
        this.soundVolume = soundVolume;
    }

    @Override
    public void forceSetSoundVolume(int soundVolume) {
        muted = soundVolume == 0;
        this.soundVolume = soundVolume;
    }

    @Override
    public void setSoundVolumeInterval(int soundVolumeInterval) {
        this.soundVolumeInterval = soundVolumeInterval;
    }

    @Override
    public void setPrimaryChosenQuality(Quality primaryChosenQuality) {
        this.primaryChosenQuality = primaryChosenQuality == null ? Quality.LOSSLESS : primaryChosenQuality;
    }

    @Override
    public void setMainScreenAdditionalBackgroundDarken(double additionalBackgroundDarken) {
        mainScreenAdditionalBackgroundDarken = additionalBackgroundDarken;
    }

    @Override
    public void setHudBackgroundMixAlpha(double hudBackgroundMixAlpha) {
        this.hudBackgroundMixAlpha = hudBackgroundMixAlpha;
    }

    @Override
    public void setHudVerticalPosition(String hudVerticalPosition) {
        this.hudVerticalPosition = hudVerticalPosition == null || hudVerticalPosition.isBlank() ? "TOP" : hudVerticalPosition;
    }

    @Override
    public void setHudHorizontalPosition(String hudHorizontalPosition) {
        this.hudHorizontalPosition = hudHorizontalPosition == null || hudHorizontalPosition.isBlank() ? "LEFT" : hudHorizontalPosition;
    }

    @Override
    public void setHudOffsetX(int hudOffsetX) {
        this.hudOffsetX = hudOffsetX;
    }

    @Override
    public void setHudOffsetY(int hudOffsetY) {
        this.hudOffsetY = hudOffsetY;
    }

    @Override
    public void setHudWidth(int hudWidth) {
        this.hudWidth = hudWidth;
    }

    @Override
    public void setHudHeight(int hudHeight) {
        this.hudHeight = hudHeight;
    }

    @Override
    public void setHudCornerRadius(int hudCornerRadius) {
        this.hudCornerRadius = hudCornerRadius;
    }

    @Override
    public void setClientAccountConfig(ProfileConfigData clientAccountConfig) {
        this.clientAccountConfig = clientAccountConfig == null ? "" : JsonUtil.gson.toJson(clientAccountConfig);
    }

    @Override
    public void setTuneWeaveBaseUrl(String baseUrl) {
        tuneWeaveBaseUrl = normalizeTuneWeaveBaseUrl(baseUrl);
    }

    @Override
    public synchronized void setTuneWeaveCredential(String platform, String credential) {
        String key = normalizePlatform(platform);
        Map<String, String> credentials = parseStringMap(tuneWeaveCredentials);
        if (credential == null || credential.isBlank()) {
            credentials.remove(key);
        } else {
            credentials.put(key, credential.trim());
        }
        tuneWeaveCredentials = JsonUtil.gson.toJson(credentials);
    }

    @Override
    public synchronized void clearTuneWeaveCredential(String platform) {
        setTuneWeaveCredential(platform, null);
    }

    @Override
    public void setDefaultMusicPlatform(String platform) {
        defaultMusicPlatform = normalizePlatform(platform);
    }

    @Override
    public void setEnabledInIntegratedServer(boolean enabledInIntegratedServer) {
        this.enabledInIntegratedServer = enabledInIntegratedServer;
    }

    @Override
    public void setEnableAutoConnect(boolean autoConnect) {
        enableAutoConnect = autoConnect;
    }

    @Override
    public void setEnableIsolatedMode(boolean autoConnect) {
        enableIsolatedMode = autoConnect;
    }

    @Override
    public void setConnectServerFilterType(AutoConnectServerFilterType autoConnectServerFilterType) {
        connectServerFilterType = autoConnectServerFilterType == null ? AutoConnectServerFilterType.BLACK_LIST : autoConnectServerFilterType;
    }

    @Override
    public void setBlackList(List<String> blackList) {
        autoConnectBlackList = JsonUtil.gson.toJson(blackList == null ? List.of() : blackList);
    }

    @Override
    public void setWhiteList(List<String> whiteList) {
        autoConnectWhiteList = JsonUtil.gson.toJson(whiteList == null ? List.of() : whiteList);
    }

    @Override
    public void setEnableMarqueeText(boolean enableMarqueeText) {
        this.enableMarqueeText = enableMarqueeText;
    }

    @Override
    public boolean getEnable() {
        return enable;
    }

    @Override
    public boolean getShowTranslatedCnLyrics() {
        return showTranslatedCnLyrics;
    }

    @Override
    public boolean getDisableVanillaMusic() {
        return disableVanillaMusic;
    }

    @Override
    public boolean getHideHudWhenNotPlaying() {
        return hideHudWhenNotPlaying;
    }

    @Override
    public boolean getEnableHud() {
        return enableHud;
    }

    @Override
    public boolean getMixWithVanillaSoundVolume() {
        return mixWithVanillaSoundVolume;
    }

    @Override
    public boolean getMuted() {
        return muted;
    }

    @Override
    public int getSoundVolume() {
        return soundVolume;
    }

    @Override
    public int getSoundVolumeInterval() {
        return soundVolumeInterval;
    }

    @Override
    public Quality getPrimaryChosenQuality() {
        return primaryChosenQuality;
    }

    @Override public AudioOutputMode getAudioOutputMode() { return audioOutputMode; }
    @Override public void setAudioOutputMode(AudioOutputMode mode) { audioOutputMode = mode == null ? AudioOutputMode.MULTICHANNEL : mode; }

    @Override public ScrobbleMode getScrobbleMode() { return scrobbleMode; }
    @Override public void setScrobbleMode(ScrobbleMode mode) {
        scrobbleMode = mode == null ? ScrobbleMode.NONE : mode;
    }

    @Override
    public double getMainScreenAdditionalBackgroundDarken() {
        return mainScreenAdditionalBackgroundDarken;
    }

    @Override
    public double getHudBackgroundMixAlpha() {
        return hudBackgroundMixAlpha;
    }

    @Override
    public String getHudVerticalPosition() {
        return hudVerticalPosition;
    }

    @Override
    public String getHudHorizontalPosition() {
        return hudHorizontalPosition;
    }

    @Override
    public int getHudOffsetX() {
        return hudOffsetX;
    }

    @Override
    public int getHudOffsetY() {
        return hudOffsetY;
    }

    @Override
    public int getHudWidth() {
        return hudWidth;
    }

    @Override
    public int getHudHeight() {
        return hudHeight;
    }

    @Override
    public int getHudCornerRadius() {
        return hudCornerRadius;
    }

    @Override
    public ProfileConfigData getClientAccountConfig() {
        return parseJson(clientAccountConfig, ProfileConfigData.class);
    }

    @Override
    public String getTuneWeaveBaseUrl() {
        return tuneWeaveBaseUrl;
    }

    @Override
    public synchronized String getTuneWeaveCredential(String platform) {
        return parseStringMap(tuneWeaveCredentials).getOrDefault(normalizePlatform(platform), "");
    }

    @Override
    public String getDefaultMusicPlatform() {
        return defaultMusicPlatform;
    }

    @Override
    public boolean getEnabledInIntegratedServer() {
        return enabledInIntegratedServer;
    }

    @Override
    public boolean getEnableAutoConnect() {
        return enableAutoConnect;
    }

    @Override
    public boolean getEnableIsolatedMode() {
        return enableIsolatedMode;
    }

    @Override
    public AutoConnectServerFilterType getConnectServerFilterType() {
        return connectServerFilterType;
    }

    @Override
    public List<String> getBlackList() {
        return parseList(autoConnectBlackList);
    }

    @Override
    public List<String> getWhiteList() {
        return parseList(autoConnectWhiteList);
    }

    @Override
    public boolean getEnableMarqueeText() {
        return enableMarqueeText;
    }

    @Override
    public synchronized void save() {
        SimpleTomlConfig.write(SimpleTomlConfig.path(FILE_NAME), List.of(
                new SimpleTomlConfig.Entry("enable", "Enable MusicHud TuneWeave functions", enable),
                new SimpleTomlConfig.Entry("showTranslatedCnLyrics", "Show translated Chinese lyrics", showTranslatedCnLyrics),
                new SimpleTomlConfig.Entry("disableVanillaMusic", "Disable vanilla game music", disableVanillaMusic),
                new SimpleTomlConfig.Entry("hideHudWhenNotPlaying", "Hide HUD when not playing music", hideHudWhenNotPlaying),
                new SimpleTomlConfig.Entry("enableHud", "Enable HUD", enableHud),
                new SimpleTomlConfig.Entry("enableMarqueeText", "Enable marquee animation on overflow text", enableMarqueeText),
                new SimpleTomlConfig.Entry("mixWithVanillaSoundVolume", "Mix MusicHud TuneWeave volume with vanilla music volume", mixWithVanillaSoundVolume),
                new SimpleTomlConfig.Entry("muted", "Record muted switch", muted),
                new SimpleTomlConfig.Entry("soundVolume", "Sound volume for MusicHud TuneWeave audio", soundVolume),
                new SimpleTomlConfig.Entry("soundVolumeInterval", "Sound volume interval for hot key adjustment", soundVolumeInterval),
                new SimpleTomlConfig.Entry("audioOutputMode", "Device output: MULTICHANNEL or STEREO downmix", audioOutputMode.name()),
                new SimpleTomlConfig.Entry("primaryChosenQuality", "Primary chosen quality", primaryChosenQuality.name()),
                new SimpleTomlConfig.Entry("scrobbleMode", "Listening history after 30 seconds: NONE|ONLY_SELF|ALL", scrobbleMode.name()),
                new SimpleTomlConfig.Entry("mainScreenAdditionalBackgroundDarken", "Main screen additional background darken rate", mainScreenAdditionalBackgroundDarken),
                new SimpleTomlConfig.Entry("hudBackgroundMixAlpha", "HUD background mix alpha", hudBackgroundMixAlpha),
                new SimpleTomlConfig.Entry("verticalPosition", "Vertical position (TOP|CENTER|BOTTOM)", hudVerticalPosition),
                new SimpleTomlConfig.Entry("horizontalPosition", "Horizontal position (LEFT|CENTER|RIGHT)", hudHorizontalPosition),
                new SimpleTomlConfig.Entry("hudOffsetX", "HUD offset x", hudOffsetX),
                new SimpleTomlConfig.Entry("hudOffsetY", "HUD offset y", hudOffsetY),
                new SimpleTomlConfig.Entry("hudWidth", "HUD width", hudWidth),
                new SimpleTomlConfig.Entry("hudHeight", "HUD height", hudHeight),
                new SimpleTomlConfig.Entry("hudCornerRadius", "HUD rounded corner radius", hudCornerRadius),
                new SimpleTomlConfig.Entry("clientAccountConfig", "Client account config json", clientAccountConfig),
                new SimpleTomlConfig.Entry("tuneWeaveBaseUrl", "TuneWeave API URL used directly by this client", tuneWeaveBaseUrl),
                new SimpleTomlConfig.Entry("tuneWeaveCredentials", "Client-owned TuneWeave credentials by platform (private)", tuneWeaveCredentials),
                new SimpleTomlConfig.Entry("defaultMusicPlatform", "Default TuneWeave platform (netease|qq|bilibili)", defaultMusicPlatform),
                new SimpleTomlConfig.Entry("enabledInIntegratedServer", "Enable embedded server for singleplayer or LAN multiplayer", enabledInIntegratedServer),
                new SimpleTomlConfig.Entry("enableAutoConnect", "Enable auto connect", enableAutoConnect),
                new SimpleTomlConfig.Entry("enableClientOnlyMode", "Enable client-only isolated mode", enableIsolatedMode),
                new SimpleTomlConfig.Entry("autoConnectServerFilterType", "Auto connecting servers filter type (WHITE_LIST|BLACK_LIST)", connectServerFilterType.name()),
                new SimpleTomlConfig.Entry("autoConnectBlackList", "Auto connecting servers black list JSON", autoConnectBlackList),
                new SimpleTomlConfig.Entry("autoConnectWhiteList", "Auto connecting servers white list JSON", autoConnectWhiteList)
        ));
    }

    private static <T> T parseJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonUtil.gson.fromJson(json, type);
        } catch (RuntimeException e) {
            MusicHud.LOGGER.warn("Failed to parse client config JSON for {}", type.getSimpleName(), e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = JsonUtil.gson.fromJson(json, List.class);
            return values == null ? List.of() : values;
        } catch (RuntimeException e) {
            MusicHud.LOGGER.warn("Failed to parse client config list", e);
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseStringMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, String> values = JsonUtil.gson.fromJson(json, LinkedHashMap.class);
            return values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
        } catch (RuntimeException e) {
            MusicHud.LOGGER.warn("Failed to parse TuneWeave client credentials");
            return new LinkedHashMap<>();
        }
    }

    private static String normalizeTuneWeaveBaseUrl(String value) {
        String result = value == null || value.isBlank() ? "http://127.0.0.1:7832" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String normalizePlatform(String platform) {
        if (platform == null) {
            return "netease";
        }
        return switch (platform.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "qq", "tencent" -> "qq";
            case "bilibili", "bili" -> "bilibili";
            default -> "netease";
        };
    }

    private void migrateEmptyWhiteListDefault(Map<String, String> values) {
        String configuredFilterType = values.get("autoConnectServerFilterType");
        if (!enableAutoConnect
                || configuredFilterType == null
                || !configuredFilterType.equalsIgnoreCase(AutoConnectServerFilterType.WHITE_LIST.name())
                || !parseList(autoConnectBlackList).isEmpty()
                || !parseList(autoConnectWhiteList).isEmpty()) {
            return;
        }

        connectServerFilterType = AutoConnectServerFilterType.BLACK_LIST;
    }
}

