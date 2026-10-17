package indi.mopelotus.musichud.client.ui.pages;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.mc.ConfigItem;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.mc.ui.PreferencesFragment;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.*;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.api.AutoConnectServerFilterType;
import indi.mopelotus.musichud.beans.music.Quality;
import indi.mopelotus.musichud.beans.music.ScrobbleMode;
import indi.mopelotus.musichud.beans.music.AudioOutputMode;
import indi.mopelotus.musichud.client.services.ConnectionManager;
import indi.mopelotus.musichud.client.services.LoginService;
import indi.mopelotus.musichud.client.network.vanilla.VanillaPlayerProxy;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.ToastUtil;
import indi.mopelotus.musichud.client.ui.components.Modal;
import indi.mopelotus.musichud.client.ui.components.DynamicIntegerOption;
import indi.mopelotus.musichud.client.ui.components.LyricLineView;
import indi.mopelotus.musichud.client.ui.components.SignedIntegerOption;
import indi.mopelotus.musichud.client.ui.components.StaggeredLyricScrollView;
import indi.mopelotus.musichud.client.ui.hud.HudRendererManager;
import indi.mopelotus.musichud.client.ui.hud.metadata.HorizontalAlign;
import indi.mopelotus.musichud.client.ui.hud.metadata.VerticalAlign;
import indi.mopelotus.musichud.client.ui.screen.MainFragment;
import indi.mopelotus.musichud.client.ui.screen.MusicHudScreen;
import indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.interfaces.IClientLoginService;
import indi.mopelotus.musichud.interfaces.ServerConfig;
import indi.mopelotus.musichud.server.api.*;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient;
import indi.mopelotus.musichud.server.ServerPlayerRegistry;
import lombok.Getter;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import org.apache.commons.lang3.Range;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

public class ConfigView extends LinearLayout {
    private static final ClientConfig clientConfig = ClientConfig.getInstance();
    private static final ServerConfig serverConfig = ServerConfig.getInstance();
    @Getter
    static volatile ConfigView instance;
    private final IClientLoginService clientLoginService = LoginService.getInstance();
    private final ConnectionManager connectionManager = ConnectionManager.getInstance();

    public ConfigView(Context context) {
        super(context);
        try {
            instance = this;

            var baseParams = new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT);
            setLayoutParams(baseParams);

            var scrollView = new ScrollView(context);
            scrollView.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
            scrollView.setFillViewport(true);
            addView(scrollView, new LayoutParams(MATCH_PARENT, MATCH_PARENT));

            LinearLayout view = new LinearLayout(context);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setGravity(Gravity.CENTER_HORIZONTAL);
            scrollView.addView(view, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            view.addView(new View(context), new LayoutParams(MATCH_PARENT, dp(32)));

            Button updateButton = new Button(context);
            updateButton.setText(indi.mopelotus.musichud.client.update.ClientUpdateUi.tr("check"));
            updateButton.setTextColor(Theme.PRIMARY_COLOR);
            updateButton.setOnClickListener(v -> indi.mopelotus.musichud.client.update.ClientUpdateUi.manualCheck());
            view.addView(updateButton, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

            HudRendererManager hudRendererManager = HudRendererManager.getInstance();

            var commonCategory = PreferencesFragment.createCategoryList(view, I18n.get(MusicHud.MOD_ID + ".config.category.common"));
            PreferencesFragment.BooleanOption booleanOption = new PreferencesFragment.BooleanOption(context,
                    I18n.get(MusicHud.MOD_ID + ".config.common.enable"),
                    clientConfig::getEnable,
                    clientConfig::setEnable);
            booleanOption.create(commonCategory);
            booleanOption.setOnChanged(() -> {
                MuiModApi.postToUiThread(MainFragment::refresh);
                if (clientConfig.getEnable()) {
                    connectionManager.connectAsPrevious();
                } else {
                    connectionManager.disconnect();
                }
            });
            new PreferencesFragment.BooleanOption(context,
                    I18n.get(MusicHud.MOD_ID + ".config.common.enableLyricBlur"),
                    clientConfig::getEnableLyricBlur, clientConfig::setEnableLyricBlur)
                    .setDefaultValue(clientConfig.getDefaultEnableLyricBlur())
                    .setOnChanged(MainFragment::refreshLyricViews)
                    .create(commonCategory);
            PreferencesFragment.BooleanOption translatedLyricOption = new PreferencesFragment.BooleanOption(context,
                    I18n.get(MusicHud.MOD_ID + ".config.common.showTranslatedCnLyrics"),
                    clientConfig::getShowTranslatedCnLyrics,
                    clientConfig::setShowTranslatedCnLyrics);
            translatedLyricOption.create(commonCategory);
            translatedLyricOption.setDefaultValue(clientConfig.getDefaultShowTranslatedCnLyrics());
            translatedLyricOption.setOnChanged(MainFragment::refreshLyricViews);
            new PreferencesFragment.BooleanOption(context,
                    I18n.get(MusicHud.MOD_ID + ".config.common.enableLyricsSidebar"),
                    clientConfig::getEnableLyricsSidebar, clientConfig::setEnableLyricsSidebar)
                    .setDefaultValue(clientConfig.getDefaultEnableLyricsSidebar())
                    .setOnChanged(MainFragment::refreshLyricsSidebarVisibility)
                    .create(commonCategory);
            new PreferencesFragment.BooleanOption(context,
                    I18n.get(MusicHud.MOD_ID + ".config.common.disableVanillaMusicWhilePlaying"),
                    clientConfig::getDisableVanillaMusic,
                    clientConfig::setDisableVanillaMusic)
                    .setDefaultValue(clientConfig.getDefaultDisableVanillaMusic())
                    .create(commonCategory);
            new PreferencesFragment.BooleanOption(context,
                    I18n.get(MusicHud.MOD_ID + ".config.common.mixWithVanillaSoundVolume"),
                    clientConfig::getMixWithVanillaSoundVolume,
                    clientConfig::setMixWithVanillaSoundVolume)
                    .setDefaultValue(clientConfig.getDefaultMixWithVanillaSoundVolume())
                    .setOnChanged(MainFragment::refreshCoverScale)
                    .create(commonCategory);
            new PreferencesFragment.IntegerOption(
                    context,
                    I18n.get(MusicHud.MOD_ID + ".config.common.soundVolume"),
                    clientConfig::getSoundVolume,
                    clientConfig::setSoundVolume)
                    .setRange(0, 100)
                    .setDefaultValue(clientConfig.getDefaultSoundVolume())
                    .setOnChanged(MainFragment::refreshCoverScale)
                    .create(commonCategory);
            new PreferencesFragment.IntegerOption(
                    context,
                    I18n.get(MusicHud.MOD_ID + ".config.common.soundVolumeInterval"),
                    clientConfig::getSoundVolumeInterval,
                    clientConfig::setSoundVolumeInterval)
                    .setRange(1, 100)
                    .setDefaultValue(10)
                    .create(commonCategory);
            Quality[] qualities = {Quality.STANDARD, Quality.EX_HIGH, Quality.LOSSLESS, Quality.HIRES, Quality.JY_EFFECT, Quality.DOLBY, Quality.JY_MASTER, Quality.SKY};
            String[] outputLabels = Arrays.stream(AudioOutputMode.values())
                    .map(mode -> I18n.get(MusicHud.MOD_ID + ".config.common.audioOutputMode." + mode.name()))
                    .toArray(String[]::new);
            List<String> outputOptions = Arrays.asList(outputLabels);
            new PreferencesFragment.DropDownOption<>(context,
                    I18n.get(MusicHud.MOD_ID + ".config.common.audioOutputMode"), outputLabels,
                    outputOptions::indexOf,
                    () -> outputLabels[clientConfig.getAudioOutputMode().ordinal()],
                    label -> clientConfig.setAudioOutputMode(AudioOutputMode.values()[outputOptions.indexOf(label)]))
                    .create(commonCategory);
            String[] scrobbleLabels = Arrays.stream(ScrobbleMode.values())
                    .map(mode -> I18n.get(MusicHud.MOD_ID + ".config.common.scrobbleMode." + mode.name()))
                    .toArray(String[]::new);
            List<String> scrobbleOptions = Arrays.asList(scrobbleLabels);
            new PreferencesFragment.DropDownOption<>(context,
                    I18n.get(MusicHud.MOD_ID + ".config.common.scrobbleMode"), scrobbleLabels,
                    scrobbleOptions::indexOf,
                    () -> scrobbleLabels[clientConfig.getScrobbleMode().ordinal()],
                    label -> clientConfig.setScrobbleMode(ScrobbleMode.values()[scrobbleOptions.indexOf(label)]))
                    .create(commonCategory);
            List<Quality> qualitiesList = Arrays.stream(qualities).toList();
            new PreferencesFragment.DropDownOption<>(
                    context,
                    I18n.get(MusicHud.MOD_ID + ".config.common.primaryChosenQuality"),
                    qualities,
                    qualitiesList::indexOf,
                    clientConfig::getPrimaryChosenQuality,
                    clientConfig::setPrimaryChosenQuality)
                    .setDefaultValue(Quality.LOSSLESS)
                    .create(commonCategory);
            new PreferencesFragment.FloatOption(
                    context,
                    I18n.get(MusicHud.MOD_ID + ".config.common.mainScreenAdditionalBackgroundDarken"),
                    clientConfig::getMainScreenAdditionalBackgroundDarken,
                    clientConfig::setMainScreenAdditionalBackgroundDarken)
                    .setRange(0, 1)
                    .setOnChanged(() -> {
                        MusicHudScreen.setDarken(clientConfig.getMainScreenAdditionalBackgroundDarken());
                    })
                    .setDefaultValue(0.5)
                    .create(commonCategory);
            Button editHud = new Button(context);
            editHud.setText(I18n.get(MusicHud.MOD_ID + ".config.openHudConfig"));
            editHud.setTextColor(Theme.PRIMARY_COLOR);
            editHud.setTextSize(14);
            InsetBackgroundFactory.builder().inset(0).cornerRadius(dp(8)).build().applyBackgroundTo(editHud);
            editHud.setOnClickListener(button -> Minecraft.getInstance().execute(() -> {
                var minecraft = Minecraft.getInstance();
                minecraft.setScreen(new indi.mopelotus.musichud.client.ui.screen.HudLayoutEditorScreen(minecraft.screen));
            }));
            commonCategory.addView(editHud, new LayoutParams(MATCH_PARENT, dp(40)));
            view.addView(commonCategory);

            var multiplayerCategory = PreferencesFragment.createCategoryList(view, I18n.get(MusicHud.MOD_ID + ".config.category.externalServer"));
            PreferencesFragment.BooleanOption autoConnectToServerOption = new PreferencesFragment.BooleanOption(
                    context,
                    I18n.get(MusicHud.MOD_ID + ".config.externalServer.autoConnect"),
                    clientConfig::getEnableAutoConnect,
                    clientConfig::setEnableAutoConnect)
                    .setDefaultValue(true);
            autoConnectToServerOption.create(multiplayerCategory);

            PreferencesFragment.BooleanOption enableIsolatedMode = new PreferencesFragment.BooleanOption(
                    context,
                    I18n.get(MusicHud.MOD_ID + ".config.externalServer.enableIsolatedMode"),
                    clientConfig::getEnableIsolatedMode,
                    clientConfig::setEnableIsolatedMode)
                    .setDefaultValue(true);
            enableIsolatedMode.setOnChanged(() -> {
                if (MusicHud.getConnectStatus() != MusicHud.ConnectStatus.CONNECTED) {
                    if (clientConfig.getEnableIsolatedMode()) {
                        connectionManager.switchToIsolate();
                    } else {
                        connectionManager.disconnect();
                    }
                    MainFragment.refresh();
                }
            });
            enableIsolatedMode.create(multiplayerCategory);

            AutoConnectServerFilterType[] filterTypes = {AutoConnectServerFilterType.BLACK_LIST, AutoConnectServerFilterType.WHITE_LIST};
            List<AutoConnectServerFilterType> filterTypeList = Arrays.stream(filterTypes).toList();
            new PreferencesFragment.DropDownOption<>(
                    context,
                    I18n.get(MusicHud.MOD_ID + ".config.externalServer.serverFilterType"),
                    filterTypes,
                    filterTypeList::indexOf,
                    clientConfig::getConnectServerFilterType,
                    clientConfig::setConnectServerFilterType)
                    .setDefaultValue(AutoConnectServerFilterType.BLACK_LIST)
                    .create(multiplayerCategory);

            LinearLayout blackList = PreferencesFragment.createStringListOption(
                    context,
                    MusicHud.MOD_ID + ".config.externalServer.blackList",
                    new ConfigItem<>() {
                        @Override
                        public List<String> getPath() {
                            return List.of(MusicHud.MOD_ID, "config", "externalServer", "blackList");
                        }

                        @Override
                        public void set(List<? extends String> value) {
                            //noinspection unchecked
                            clientConfig.setBlackList((List<String>) value);
                        }

                        @Override
                        public List<? extends String> getDefault() {
                            return List.of();
                        }

                        @Override
                        public @Nullable Range<List<? extends String>> getRange() {
                            return null;
                        }

                        @Override
                        public List<? extends String> get() {
                            return clientConfig.getBlackList();
                        }
                    }, clientConfig::save);
            LinearLayout whiteList = PreferencesFragment.createStringListOption(
                    context,
                    MusicHud.MOD_ID + ".config.externalServer.whiteList",
                    new ConfigItem<>() {
                        @Override
                        public List<String> getPath() {
                            return List.of(MusicHud.MOD_ID, "config", "externalServer", "whiteList");
                        }

                        @Override
                        public void set(List<? extends String> value) {
                            //noinspection unchecked
                            clientConfig.setWhiteList((List<String>) value);
                        }

                        @Override
                        public List<? extends String> getDefault() {
                            return List.of();
                        }

                        @Override
                        public @Nullable Range<List<? extends String>> getRange() {
                            return null;
                        }

                        @Override
                        public List<? extends String> get() {
                            return clientConfig.getWhiteList();
                        }
                    }, clientConfig::save);
            multiplayerCategory.addView(blackList);
            multiplayerCategory.addView(whiteList);
            view.addView(multiplayerCategory);

            var integratedServerCategory = PreferencesFragment.createCategoryList(view, I18n.get(MusicHud.MOD_ID + ".config.category.integratedServer"));
            view.addView(integratedServerCategory, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

            PreferencesFragment.BooleanOption enableInIntegratedServerOption = new PreferencesFragment.BooleanOption(
                    context,
                    I18n.get(MusicHud.MOD_ID + ".config.integratedServer.enable"),
                    clientConfig::getEnabledInIntegratedServer,
                    clientConfig::setEnabledInIntegratedServer)
                    .setDefaultValue(true);
            enableInIntegratedServerOption.create(integratedServerCategory);
            enableInIntegratedServerOption.setOnChanged(() -> {
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.player == null) return;
                var player = VanillaPlayerProxy.ofPlayer(minecraft.player);
                if (clientConfig.getEnabledInIntegratedServer()) {
                    ServerPlayerRegistry.getInstance().join(player);
                } else {
                    ServerPlayerRegistry.getInstance().leave(player);
                    MusicPlayerServerService.getInstance().reset();
                }
            });

            PreferencesFragment.FloatOption pusherVoteAdditionalRateOption = new PreferencesFragment.FloatOption(
                    context,
                    I18n.get(MusicHud.MOD_ID + ".config.integratedServer.pusherVoteAdditionalRate"),
                    serverConfig::getPusherVoteAdditionalRate,
                    serverConfig::setPusherVoteAdditionalRate)
                    .setRange(0, 1)
                    .setDefaultValue(0.5);
            pusherVoteAdditionalRateOption.create(integratedServerCategory);

            ApiSettingsUi.create(context, this, view, serverConfig);
            addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {}
                @Override public void onViewDetachedFromWindow(View v) {
                    Util.ioPool().execute(() -> { clientConfig.save(); serverConfig.save(); });
                }
            });
        } catch (Exception e) {
            instance = null;
            throw e;
        }
    }

    static void updateTuneWeaveVersionLabel(TextView label, String template) {
        String loading = I18n.get(MusicHud.MOD_ID + ".text.tuneWeaveVersion.loading");
        String unavailable = I18n.get(MusicHud.MOD_ID + ".text.tuneWeaveVersion.unavailable");
        label.setText(template.replace("{}", loading));
        CompletableFuture.supplyAsync(
                () -> TuneWeaveApiClient.serverVersionAt(clientConfig.getTuneWeaveBaseUrl()).orElse(unavailable),
                MusicHud.EXECUTOR
        ).thenAccept(version -> MuiModApi.postToUiThread(
                () -> label.setText(template.replace("{}", version))));
    }

    static LinearLayout createFullWidthInputBox(Context context, String title) {
        LinearLayout row = PreferencesFragment.createInputBox(context, title);
        row.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
            params.width = MATCH_PARENT;
            params.weight = 0;
            child.setLayoutParams(params);
        }
        EditText input = row.findViewById(R.id.input);
        input.setMinimumWidth(0);
        input.setSingleLine(true);
        return row;
    }

}
