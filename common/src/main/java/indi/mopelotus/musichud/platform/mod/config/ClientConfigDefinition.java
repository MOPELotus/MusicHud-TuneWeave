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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.List;
import java.util.Map;

public class ClientConfigDefinition implements ClientConfig {
    private static final String FILE_NAME = ProjectIdentity.CONFIG_PREFIX + "-client.toml";
    @Getter
    private static final ClientConfigDefinition instance = new ClientConfigDefinition();

    private boolean enable = getDefaultEnable();
    private volatile boolean enableLyricBlur = getDefaultEnableLyricBlur();
    private boolean showTranslatedCnLyrics = getDefaultShowTranslatedCnLyrics();
    private boolean disableVanillaMusic = getDefaultDisableVanillaMusic();
    private boolean hideHudWhenNotPlaying = getDefaultHideHudWhenNotPlaying();
    private boolean enableHud = getDefaultEnableHud();
    private volatile boolean companionEnabled;
    private volatile int companionFps = 60;
    private volatile int companionScale = 2;
    private boolean enableLyricsSidebar = getDefaultEnableLyricsSidebar();
    private boolean enableMarqueeText = getDefaultEnableMarqueeText();
    private boolean mixWithVanillaSoundVolume = getDefaultMixWithVanillaSoundVolume();
    private boolean muted;
    private int soundVolume = getDefaultSoundVolume();
    private int soundVolumeInterval = getDefaultSoundVolumeInterval();
    private Quality primaryChosenQuality = getDefaultPrimaryChosenQuality();
    private volatile ScrobbleMode scrobbleMode = getDefaultScrobbleMode();
    private volatile AudioOutputMode audioOutputMode = AudioOutputMode.MULTICHANNEL;
    private double mainScreenAdditionalBackgroundDarken = getDefaultMainScreenAdditionalBackgroundDarken();
    private double hudBackgroundMixAlpha = getDefaultHudBackgroundMixAlpha();
    private String hudVerticalPosition = getDefaultHudVerticalPosition();
    private String hudHorizontalPosition = getDefaultHudHorizontalPosition();
    private int hudOffsetX = getDefaultHudOffsetX();
    private int hudOffsetY = getDefaultHudOffsetY();
    private int hudWidth = getDefaultHudWidth();
    private int hudHeight = getDefaultHudHeight();
    private int hudCornerRadius = getDefaultHudCornerRadius();
    private ClientAccountStore privateAccounts;
    private final Supplier<Path> privateAccountsPath;
    private Path boundAccountsFile;
    private boolean privateMigrationPending;
    private volatile String tuneWeaveBaseUrl = "http://127.0.0.1:7832";
    private String defaultMusicPlatform = "netease";
    private boolean enabledInIntegratedServer = getDefaultEnabledInIntegratedServer();
    private boolean enableAutoConnect = getDefaultEnableAutoConnect();
    private boolean enableIsolatedMode = getDefaultEnableIsolatedMode();
    private AutoConnectServerFilterType connectServerFilterType = getDefaultConnectServerFilterType();
    private String autoConnectBlackList = "[]";
    private String autoConnectWhiteList = "[]";
    @Setter
    @Getter
    private boolean configured;

    private ClientConfigDefinition() { this(ClientPrivatePaths::accountsFile); }

    // Test instances never access the active player's private data.
    ClientConfigDefinition(Path privateAccountsFile) { this(() -> privateAccountsFile); }

    ClientConfigDefinition(Supplier<Path> privateAccountsPath) {
        this.privateAccountsPath = Objects.requireNonNull(privateAccountsPath);
    }

    private Path accountsFile() {
        return Objects.requireNonNull(privateAccountsPath.get()).toAbsolutePath().normalize();
    }

    public synchronized void load() {
        Path path = SimpleTomlConfig.path(FILE_NAME);
        Map<String, String> values;
        try { values = SimpleTomlConfig.readChecked(path); }
        catch (java.io.IOException | RuntimeException error) {
            // Reading failure is never evidence that an old account field was empty.
            privateMigrationPending = true;
            privateAccounts = null;
            configured = true;
            MusicHud.LOGGER.error("Cannot read client configuration; existing configuration and account files were kept");
            return;
        }
        enable = SimpleTomlConfig.getBoolean(values, "enable", enable);
        enableLyricBlur = SimpleTomlConfig.getBoolean(values, "enableLyricBlur", getDefaultEnableLyricBlur());
        showTranslatedCnLyrics = SimpleTomlConfig.getBoolean(values, "showTranslatedCnLyrics", showTranslatedCnLyrics);
        disableVanillaMusic = SimpleTomlConfig.getBoolean(values, "disableVanillaMusic", disableVanillaMusic);
        hideHudWhenNotPlaying = SimpleTomlConfig.getBoolean(values, "hideHudWhenNotPlaying", hideHudWhenNotPlaying);
        enableHud = SimpleTomlConfig.getBoolean(values, "enableHud", enableHud);
        companionEnabled = SimpleTomlConfig.getBoolean(values, "companionEnabled", false);
        int savedCompanionFps = SimpleTomlConfig.getInt(values, "companionFps", 60);
        companionFps = savedCompanionFps == 30 || savedCompanionFps == 60 ? savedCompanionFps : 60;
        int savedCompanionScale = SimpleTomlConfig.getInt(values, "companionScale", 2);
        companionScale = savedCompanionScale >= 1 && savedCompanionScale <= 4 ? savedCompanionScale : 2;
        enableLyricsSidebar = SimpleTomlConfig.getBoolean(values, "enableLyricsSidebar", getDefaultEnableLyricsSidebar());
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
        String legacyProfile = SimpleTomlConfig.getString(values, "clientAccountConfig", "");
        String legacyCredentials = SimpleTomlConfig.getString(values, "tuneWeaveCredentials", "{}");
        tuneWeaveBaseUrl = normalizeTuneWeaveBaseUrl(SimpleTomlConfig.getString(
                values, "tuneWeaveBaseUrl", tuneWeaveBaseUrl));
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
        privateMigrationPending = true;
        try {
            privateAccounts = null;
            boundAccountsFile = accountsFile();
            privateAccounts = new ClientAccountStore(boundAccountsFile);
            privateAccounts.migrate(legacyCredentials, legacyProfile);
            privateMigrationPending = false;
            save();
        } catch (RuntimeException error) {
            MusicHud.LOGGER.error("Cannot migrate private client accounts; existing configuration and account files were kept");
        }
    }

    @Override
    public void setEnable(boolean enable) {
        this.enable = enable;
    }

    @Override public boolean getEnableLyricBlur() { return enableLyricBlur; }
    @Override public void setEnableLyricBlur(boolean enabled) { enableLyricBlur = enabled; save(); }

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
    public boolean getEnableLyricsSidebar() { return enableLyricsSidebar; }

    @Override
    public void setEnableLyricsSidebar(boolean enabled) { enableLyricsSidebar = enabled; }

    @Override public boolean getCompanionEnabled() { return companionEnabled; }
    @Override public void setCompanionEnabled(boolean enabled) { companionEnabled = enabled; save(); }
    @Override public int getCompanionFps() { return companionFps; }
    @Override public void setCompanionFps(int value) { companionFps = value <= 30 ? 30 : 60; save(); }
    @Override public int getCompanionScale() { return companionScale; }
    @Override public void setCompanionScale(int value) { companionScale = Math.clamp(value, 1, 4); save(); }

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
    public synchronized void setClientAccountConfig(ProfileConfigData clientAccountConfig) {
        accountStore().profileData(JsonUtil.gson.toJsonTree(clientAccountConfig));
    }

    @Override
    public void setTuneWeaveBaseUrl(String baseUrl) {
        tuneWeaveBaseUrl = normalizeTuneWeaveBaseUrl(baseUrl);
    }

    @Override
    public synchronized void setTuneWeaveCredential(String platform, String credential) {
        accountStore().setCredential(normalizePlatform(platform), credential, false);
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
    public synchronized ProfileConfigData getClientAccountConfig() {
        return privateAccounts == null ? null : JsonUtil.gson.fromJson(privateAccounts.profileData(), ProfileConfigData.class);
    }

    @Override
    public String getTuneWeaveBaseUrl() {
        return tuneWeaveBaseUrl;
    }

    @Override
    public synchronized String getTuneWeaveCredential(String platform) {
        return privateAccounts == null ? "" : privateAccounts.credential(normalizePlatform(platform));
    }

    private synchronized ClientAccountStore accountStore() {
        if (privateMigrationPending) throw new IllegalStateException("Private account storage is unavailable");
        if (privateAccounts == null) throw new IllegalStateException("Private account storage is unavailable");
        Path current = accountsFile();
        if (!current.equals(boundAccountsFile)) {
            // A fresh installation may choose its API folder after config loading, before the first login.
            // Existing data always requires an explicit move and restart; never merge or overwrite stores.
            if (!privateAccounts.isPristine()
                    || !Files.notExists(boundAccountsFile, LinkOption.NOFOLLOW_LINKS)
                    || !Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) throw accountDirectoryChanged();
            ClientAccountStore candidate = new ClientAccountStore(current);
            if (!candidate.isPristine() || !Files.notExists(current, LinkOption.NOFOLLOW_LINKS))
                throw accountDirectoryChanged();
            privateAccounts = candidate;
            boundAccountsFile = current;
        }
        return privateAccounts;
    }

    private static IllegalStateException accountDirectoryChanged() {
        return new IllegalStateException("API directory changed; move the account file and restart");
    }

    @Override public synchronized java.util.List<indi.mopelotus.musichud.beans.user.SavedAccount> getSavedAccounts(String platform) {
        return privateAccounts == null ? java.util.List.of() : privateAccounts.accounts(normalizePlatform(platform));
    }
    @Override public synchronized void selectSavedAccount(String platform, String id) { accountStore().select(normalizePlatform(platform), id); }
    @Override public synchronized void storeLoginCredential(String platform, String credential) { accountStore().setCredential(normalizePlatform(platform), credential, true); }
    @Override public synchronized void storeLoginAccount(String platform, String credential, String userId, String nickname, String avatarUrl) {
        accountStore().loginAccount(normalizePlatform(platform), credential, userId, nickname, avatarUrl);
    }
    @Override public synchronized void storeAccountProfile(String platform, String userId, String nickname, String avatarUrl) {
        accountStore().profile(normalizePlatform(platform), userId, nickname, avatarUrl);
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
        // Never erase the only copy of an old credential after a failed migration.
        if (privateMigrationPending) return;
        SimpleTomlConfig.write(SimpleTomlConfig.path(FILE_NAME), List.of(
                new SimpleTomlConfig.Entry("enable", "Enable MusicHud TuneWeave functions", enable),
                new SimpleTomlConfig.Entry("enableLyricBlur", "Blur non-highlighted lyric lines", enableLyricBlur),
                new SimpleTomlConfig.Entry("showTranslatedCnLyrics", "Show translated Chinese lyrics", showTranslatedCnLyrics),
                new SimpleTomlConfig.Entry("disableVanillaMusic", "Disable vanilla game music", disableVanillaMusic),
                new SimpleTomlConfig.Entry("hideHudWhenNotPlaying", "Hide HUD when not playing music", hideHudWhenNotPlaying),
                new SimpleTomlConfig.Entry("enableHud", "Enable HUD", enableHud),
                new SimpleTomlConfig.Entry("companionEnabled", "Enable desktop HUD and restore it on the next game launch", companionEnabled),
                new SimpleTomlConfig.Entry("companionFps", "Desktop HUD frame limit (30|60)", companionFps),
                new SimpleTomlConfig.Entry("companionScale", "Desktop HUD scale (1..4)", companionScale),
                new SimpleTomlConfig.Entry("enableLyricsSidebar", "Show lyrics alongside other pages", enableLyricsSidebar),
                new SimpleTomlConfig.Entry("enableMarqueeText", "Enable marquee animation on overflow text", enableMarqueeText),
                new SimpleTomlConfig.Entry("mixWithVanillaSoundVolume", "Mix MusicHud TuneWeave volume with vanilla music volume", mixWithVanillaSoundVolume),
                new SimpleTomlConfig.Entry("muted", "Record muted switch", muted),
                new SimpleTomlConfig.Entry("soundVolume", "Sound volume for MusicHud TuneWeave audio", soundVolume),
                new SimpleTomlConfig.Entry("soundVolumeInterval", "Sound volume interval for hot key adjustment", soundVolumeInterval),
                new SimpleTomlConfig.Entry("audioOutputMode", "Device output: MULTICHANNEL, STEREO downmix, or DISCRETE_ONLY without stereo fallback", audioOutputMode.name()),
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
                new SimpleTomlConfig.Entry("tuneWeaveBaseUrl", "TuneWeave API URL used directly by this client", tuneWeaveBaseUrl),
                new SimpleTomlConfig.Entry("defaultMusicPlatform", "Default TuneWeave platform (netease|qq|bilibili)", defaultMusicPlatform),
                new SimpleTomlConfig.Entry("enabledInIntegratedServer", "Enable embedded server for singleplayer or LAN multiplayer", enabledInIntegratedServer),
                new SimpleTomlConfig.Entry("enableAutoConnect", "Enable auto connect", enableAutoConnect),
                new SimpleTomlConfig.Entry("enableClientOnlyMode", "Enable client-only isolated mode", enableIsolatedMode),
                new SimpleTomlConfig.Entry("autoConnectServerFilterType", "Auto connecting servers filter type (WHITE_LIST|BLACK_LIST)", connectServerFilterType.name()),
                new SimpleTomlConfig.Entry("autoConnectBlackList", "Auto connecting servers black list JSON", autoConnectBlackList),
                new SimpleTomlConfig.Entry("autoConnectWhiteList", "Auto connecting servers white list JSON", autoConnectWhiteList)
        ));
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
            case "soda", "kugou", "kuwo", "migu" -> platform.trim().toLowerCase(java.util.Locale.ROOT);
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
