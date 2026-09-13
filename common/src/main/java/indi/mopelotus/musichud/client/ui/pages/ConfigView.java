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
import indi.mopelotus.musichud.client.utils.ui.ButtonInsetBackgroundFactory;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.interfaces.IClientLoginService;
import indi.mopelotus.musichud.interfaces.ServerConfig;
import indi.mopelotus.musichud.server.api.*;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient;
import indi.mopelotus.musichud.server.ServerPlayerRegistry;
import lombok.Getter;
import net.minecraft.Util;
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
            LayoutParams params = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            params.setMargins(0, dp(32), 0, 0);
            scrollView.addView(view, params);

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
            PreferencesFragment.BooleanOption translatedLyricOption = new PreferencesFragment.BooleanOption(context,
                    I18n.get(MusicHud.MOD_ID + ".config.common.showTranslatedCnLyrics"),
                    clientConfig::getShowTranslatedCnLyrics,
                    clientConfig::setShowTranslatedCnLyrics);
            translatedLyricOption.create(commonCategory);
            translatedLyricOption.setOnChanged(() -> {
                HomeView homeView = HomeView.getInstance();
                if (homeView != null) {
                    StaggeredLyricScrollView staggeredLyricScrollView = homeView.getStaggeredLyricScrollView();
                    if (staggeredLyricScrollView != null) {
                        MuiModApi.postToUiThread(() -> {
                            staggeredLyricScrollView.getLyricLineViewList().forEach(LyricLineView::refreshSubLyricLine);
                        });
                    }
                }
            });
            new PreferencesFragment.BooleanOption(context,
                    I18n.get(MusicHud.MOD_ID + ".config.common.disableVanillaMusicWhilePlaying"),
                    clientConfig::getDisableVanillaMusic,
                    clientConfig::setDisableVanillaMusic)
                    .create(commonCategory);
            new PreferencesFragment.BooleanOption(context,
                    I18n.get(MusicHud.MOD_ID + ".config.common.enableHud"),
                    clientConfig::getEnableHud,
                    clientConfig::setEnableHud)
                    .create(commonCategory);
            new PreferencesFragment.BooleanOption(context,
                    I18n.get(MusicHud.MOD_ID + ".config.common.autoHide"),
                    clientConfig::getHideHudWhenNotPlaying,
                    clientConfig::setHideHudWhenNotPlaying)
                    .create(commonCategory);
            new PreferencesFragment.BooleanOption(context,
                    I18n.get(MusicHud.MOD_ID + ".config.common.enableMarqueeText"),
                    clientConfig::getEnableMarqueeText,
                    clientConfig::setEnableMarqueeText)
                    .create(commonCategory);
            new PreferencesFragment.BooleanOption(context,
                    I18n.get(MusicHud.MOD_ID + ".config.common.mixWithVanillaSoundVolume"),
                    clientConfig::getMixWithVanillaSoundVolume,
                    clientConfig::setMixWithVanillaSoundVolume)
                    .create(commonCategory);
            new PreferencesFragment.IntegerOption(
                    context,
                    I18n.get(MusicHud.MOD_ID + ".config.common.soundVolume"),
                    clientConfig::getSoundVolume,
                    clientConfig::setSoundVolume)
                    .setRange(0, 100)
                    .setDefaultValue(100)
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
            new PreferencesFragment.FloatOption(
                    context,
                    I18n.get(MusicHud.MOD_ID + ".config.common.hudBackgroundMixAlpha"),
                    clientConfig::getHudBackgroundMixAlpha,
                    clientConfig::setHudBackgroundMixAlpha)
                    .setRange(0, 1)
                    .setDefaultValue(0.5)
                    .create(commonCategory);
            view.addView(commonCategory);

            var positionCategory = PreferencesFragment.createCategoryList(view, I18n.get(MusicHud.MOD_ID + ".config.category.layout"));
            new PreferencesFragment.DropDownOption<>(
                    context,
                    I18n.get(MusicHud.MOD_ID + ".config.layout.verticalAlign"),
                    VerticalAlign.values(),
                    VerticalAlign::ordinal,
                    () -> VerticalAlign.valueOf(VerticalAlign.class, clientConfig.getHudVerticalPosition()),
                    (vp) -> clientConfig.setHudVerticalPosition(vp.name()))
                    .setOnChanged(() -> {
                        hudRendererManager.updateLayoutFromConfig();
                        hudRendererManager.refreshStyle();
                    })
                    .setDefaultValue(VerticalAlign.TOP)
                    .create(positionCategory);
            new PreferencesFragment.DropDownOption<>(
                    context,
                    I18n.get(MusicHud.MOD_ID + ".config.layout.horizontalAlign"),
                    HorizontalAlign.values(),
                    HorizontalAlign::ordinal,
                    () -> HorizontalAlign.valueOf(HorizontalAlign.class, clientConfig.getHudHorizontalPosition()),
                    (hp) -> clientConfig.setHudHorizontalPosition(hp.name()))
                    .setOnChanged(() -> {
                        hudRendererManager.updateLayoutFromConfig();
                        hudRendererManager.refreshStyle();
                    })
                    .setDefaultValue(HorizontalAlign.LEFT)
                    .create(positionCategory);
            new SignedIntegerOption(
                    context,
                    I18n.get(MusicHud.MOD_ID + ".config.layout.offsetX"),
                    clientConfig::getHudOffsetX,
                    clientConfig::setHudOffsetX)
                    .setOnChanged(() -> {
                        hudRendererManager.updateLayoutFromConfig();
                        hudRendererManager.refreshStyle();
                    })
                    .setRange(-1920, 1920)
                    .setDefaultValue(16)
                    .create(positionCategory);
            new SignedIntegerOption(
                    context,
                    I18n.get(MusicHud.MOD_ID + ".config.layout.offsetY"),
                    clientConfig::getHudOffsetY,
                    clientConfig::setHudOffsetY)
                    .setRange(-1920, 1920)
                    .setOnChanged(() -> {
                        hudRendererManager.updateLayoutFromConfig();
                        hudRendererManager.refreshStyle();
                    })
                    .setDefaultValue(16)
                    .create(positionCategory);
            DynamicIntegerOption cornerRadiusOption = new DynamicIntegerOption(
                    context,
                    I18n.get(MusicHud.MOD_ID + ".config.layout.hudCornerRadius"),
                    clientConfig::getHudCornerRadius,
                    clientConfig::setHudCornerRadius);
            cornerRadiusOption.setRange(0, clientConfig.getHudHeight() / 2);
            cornerRadiusOption.setOnChanged(() -> {
                hudRendererManager.updateLayoutFromConfig();
                hudRendererManager.refreshStyle();
            });
            cornerRadiusOption.setDefaultValue(8);
            DynamicIntegerOption widthOption = new DynamicIntegerOption(
                    context,
                    I18n.get(MusicHud.MOD_ID + ".config.layout.hudWidth"),
                    clientConfig::getHudWidth,
                    clientConfig::setHudWidth);
            widthOption.setOnChanged(() -> {
                hudRendererManager.updateLayoutFromConfig();
                hudRendererManager.refreshStyle();
            });
            widthOption.setRange(clientConfig.getHudHeight(), 800, 4);
            widthOption.setDefaultValue(150);
            PreferencesFragment.IntegerOption heightOption = new PreferencesFragment.IntegerOption(
                    context,
                    I18n.get(MusicHud.MOD_ID + ".config.layout.hudHeight"),
                    clientConfig::getHudHeight,
                    clientConfig::setHudHeight)
                    .setOnChanged(() -> {
                        hudRendererManager.updateLayoutFromConfig();
                        hudRendererManager.refreshStyle();
                        cornerRadiusOption.updateRange(0, clientConfig.getHudHeight() / 2, 1);
                        widthOption.updateRange(clientConfig.getHudHeight(), 800, 4);
                    })
                    .setRange(16, 256, 2)
                    .setDefaultValue(44);
            widthOption.create(positionCategory);
            heightOption.create(positionCategory);
            cornerRadiusOption.create(positionCategory);
            Button editHud = new Button(context);
            editHud.setText(I18n.get(MusicHud.MOD_ID + ".hudEditor.title"));
            editHud.setOnClickListener(button -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
                var minecraft = net.minecraft.client.Minecraft.getInstance();
                minecraft.setScreen(new indi.mopelotus.musichud.client.ui.screen.HudLayoutEditorScreen(minecraft.screen));
            }));
            positionCategory.addView(editHud);
            view.addView(positionCategory);

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
            ApiServerManager apiServerManager = ApiServerManager.getInstance();
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

            var apiCategory = PreferencesFragment.createCategoryList(view, I18n.get(MusicHud.MOD_ID + ".config.category.apiServer"));
            LinearLayout.LayoutParams params1 = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            params1.setMargins(0, dp(6), 0, dp(128));
            view.addView(apiCategory, params1);

            PreferencesFragment.BooleanOption startupBinaryApiServerOption = new PreferencesFragment.BooleanOption(
                    context,
                    I18n.get(MusicHud.MOD_ID + ".config.apiServer.startupBinaryApiServerWhenLaunch"),
                    serverConfig::getStartupBinaryApiServerWhenLaunch,
                    serverConfig::setStartupBinaryApiServerWhenLaunch)
                    .setDefaultValue(true);
            startupBinaryApiServerOption.create(apiCategory);

            new PreferencesFragment.IntegerOption(context,
                    I18n.get(MusicHud.MOD_ID + ".config.apiServer.port"),
                    serverConfig::getPort,
                    serverConfig::setPort)
                    .setRange(1, 65535)
                    .setDefaultValue(7832)
                    .create(apiCategory);
            {
                LinearLayout inputBox = PreferencesFragment.createInputBox(context, I18n.get(MusicHud.MOD_ID + ".config.apiServer.serverApiBaseUrl"));
                EditText input = inputBox.findViewById(R.id.input);
                if (input != null) {
                    input.setMinimumWidth(dp(256));
                    input.setTextAlignment(TEXT_ALIGNMENT_TEXT_START);
                    input.setText(serverConfig.getServerApiBaseUrl());
                    input.setOnKeyListener((v, c, e) -> {
                        if (c == GLFW.GLFW_KEY_ENTER) {
                            input.clearFocus();
                            return true;
                        }
                        return false;
                    });
                    input.setOnFocusChangeListener((v, b) -> {
                        if (!b) {
                            serverConfig.setServerApiBaseUrl(input.getText().toString());
                        }
                    });
                }
                apiCategory.addView(inputBox);
            }

            final EditText[] serverApiBinaryPathInput = {null};

            {
                LinearLayout inputBox = PreferencesFragment.createInputBox(context, I18n.get(MusicHud.MOD_ID + ".config.apiServer.serverApiBinaryExecutablePath"));
                EditText input = inputBox.findViewById(R.id.input);
                if (input != null) {
                    input.setMinimumWidth(dp(256));
                    input.setTextAlignment(TEXT_ALIGNMENT_TEXT_START);
                    input.setText(serverConfig.getServerApiBinaryExecutablePath());
                    input.setOnKeyListener((v, c, e) -> {
                        if (c == GLFW.GLFW_KEY_ENTER) {
                            input.clearFocus();
                            return true;
                        }
                        return false;
                    });
                    input.setOnFocusChangeListener((v, b) -> {
                        if (!b) {
                            serverConfig.setServerApiBinaryExecutablePath(input.getText().toString());
                        }
                    });
                }
                serverApiBinaryPathInput[0] = input;
                apiCategory.addView(inputBox);
            }

            LinearLayout apiServerStatusLayout = new LinearLayout(context);
            apiServerStatusLayout.setOrientation(LinearLayout.HORIZONTAL);
            apiServerStatusLayout.setGravity(Gravity.LEFT);
            apiServerStatusLayout.setVerticalGravity(Gravity.CENTER);
            LayoutParams params3 = new LayoutParams(MATCH_PARENT, dp(44));
            params3.setMargins(dp(6), 0, dp(6), 0);
            apiServerStatusLayout.setLayoutParams(params3);

            TextView apiStatusLabel = new TextView(context);
            apiStatusLabel.setTextSize(14);
            String binaryApiStatusTemplate = I18n.get(MusicHud.MOD_ID + ".text.binaryApiStatus");
            apiStatusLabel.setText(binaryApiStatusTemplate.replace("{}", I18n.get(apiServerManager.getBinaryApiServerStatus().i18nKey())));

            ButtonInsetBackgroundFactory backgroundFactory = ButtonInsetBackgroundFactory.builder().inset(0).padding(new ButtonInsetBackgroundFactory.Padding(dp(8), dp(4), dp(8), dp(4))).build();

            View distributionControl = ApiDistributionUi.create(context, backgroundFactory, serverApiBinaryPathInput);

            Button stopApiServerButton = new Button(context);
            stopApiServerButton.setText(I18n.get(MusicHud.MOD_ID + ".button.stopApiServer"));
            stopApiServerButton.setTextColor(Theme.PRIMARY_COLOR);
            stopApiServerButton.setTextSize(14);
            stopApiServerButton.setBackground(backgroundFactory.newBackgroundDrawable());
            stopApiServerButton.setOnClickListener((v1) -> {
                apiServerManager.stopApiServer();
            });

            Button restartApiServerButton = new Button(context);
            restartApiServerButton.setText(I18n.get(MusicHud.MOD_ID + ".button.restartApiServer"));
            restartApiServerButton.setTextColor(Theme.PRIMARY_COLOR);
            restartApiServerButton.setTextSize(14);
            restartApiServerButton.setBackground(backgroundFactory.newBackgroundDrawable());
            restartApiServerButton.setOnClickListener((v1) -> {
                apiServerManager.restartApiServer();
            });

            apiServerStatusLayout.addView(apiStatusLabel, new LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1));
            apiServerStatusLayout.addView(distributionControl);
            apiServerStatusLayout.addView(stopApiServerButton);
            apiServerStatusLayout.addView(restartApiServerButton);
            apiCategory.addView(apiServerStatusLayout);

            LinearLayout apiVersionLinearLayout = new LinearLayout(context);
            apiVersionLinearLayout.setOrientation(LinearLayout.HORIZONTAL);
            apiVersionLinearLayout.setGravity(Gravity.LEFT);
            apiVersionLinearLayout.setVerticalGravity(Gravity.CENTER);
            LayoutParams params2 = new LayoutParams(MATCH_PARENT, dp(44));
            params2.setMargins(dp(6), 0, dp(6), 0);
            apiVersionLinearLayout.setLayoutParams(params2);

            TextView apiVersionLabel = new TextView(context);
            apiVersionLabel.setTextSize(14);
            String apiServiceVersionTemplate = I18n.get(MusicHud.MOD_ID + ".text.apiServiceVersion");
            updateTuneWeaveVersionLabel(apiVersionLabel, apiServiceVersionTemplate);

            Button checkVersionButton = new Button(context);
            checkVersionButton.setText(I18n.get(MusicHud.MOD_ID + ".button.checkApiServerVersion"));
            checkVersionButton.setTextColor(Theme.PRIMARY_COLOR);
            checkVersionButton.setTextSize(14);
            checkVersionButton.setBackground(backgroundFactory.newBackgroundDrawable());
            checkVersionButton.setOnClickListener((v) -> {
                updateTuneWeaveVersionLabel(apiVersionLabel, apiServiceVersionTemplate);
            });

            apiVersionLinearLayout.addView(apiVersionLabel, new LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1));
            apiVersionLinearLayout.addView(checkVersionButton);
            apiCategory.addView(apiVersionLinearLayout);

            LinearLayout apiLogLayout = new LinearLayout(context);
            apiLogLayout.setOrientation(LinearLayout.HORIZONTAL);
            apiLogLayout.setGravity(Gravity.LEFT);
            apiLogLayout.setVerticalGravity(Gravity.CENTER);
            LayoutParams logParams = new LayoutParams(MATCH_PARENT, dp(44));
            logParams.setMargins(dp(6), 0, dp(6), 0);
            apiLogLayout.setLayoutParams(logParams);

            TextView apiLogLabel = new TextView(context);
            apiLogLabel.setTextSize(14);
            updateApiLogLabel(apiLogLabel);

            Button refreshApiLogButton = new Button(context);
            refreshApiLogButton.setText(I18n.get(MusicHud.MOD_ID + ".button.refreshApiLog"));
            refreshApiLogButton.setTextColor(Theme.PRIMARY_COLOR);
            refreshApiLogButton.setTextSize(14);
            refreshApiLogButton.setBackground(backgroundFactory.newBackgroundDrawable());
            refreshApiLogButton.setOnClickListener(v -> {
                updateApiLogLabel(apiLogLabel);
            });

            Button openApiLogDirButton = new Button(context);
            openApiLogDirButton.setText(I18n.get(MusicHud.MOD_ID + ".button.openApiLogDir"));
            openApiLogDirButton.setTextColor(Theme.PRIMARY_COLOR);
            openApiLogDirButton.setTextSize(14);
            openApiLogDirButton.setBackground(backgroundFactory.newBackgroundDrawable());
            openApiLogDirButton.setOnClickListener(v -> {
                Path logDir = apiServerManager.getLogDir();
                try {
                    Files.createDirectories(logDir);
                } catch (IOException ignored) {
                }
                Util.getPlatform().openFile(logDir.toFile());
                updateApiLogLabel(apiLogLabel);
            });

            Button clearApiLogButton = new Button(context);
            clearApiLogButton.setText(I18n.get(MusicHud.MOD_ID + ".button.clearApiLogs"));
            clearApiLogButton.setTextColor(Theme.ERROR_TEXT_COLOR);
            clearApiLogButton.setTextSize(14);
            clearApiLogButton.setBackground(backgroundFactory.newBackgroundDrawable());
            clearApiLogButton.setOnClickListener(v -> {
                LinearLayout warnContent = new LinearLayout(context);
                warnContent.setOrientation(LinearLayout.VERTICAL);
                TextView warnText = new TextView(context);
                warnText.setText(I18n.get(MusicHud.MOD_ID + ".modal.clearApiLogs.warning"));
                warnText.setTextSize(Theme.TEXT_SIZE_LARGE);
                warnText.setTextColor(Theme.NORMAL_TEXT_COLOR);
                warnContent.addView(warnText);
                new Modal(context, warnContent,
                        new Modal.ActionButton(I18n.get(MusicHud.MOD_ID + ".modal.clearApiLogs.button1"), (btn, modal) -> {
                            ApiServerManager.getInstance().clearLogs();
                            updateApiLogLabel(apiLogLabel);
                            modal.dismiss();
                        }),
                        new Modal.ActionButton(I18n.get(MusicHud.MOD_ID + ".modal.clearApiLogs.button2"), (btn, modal) -> modal.dismiss())
                ).show();
            });

            apiLogLayout.addView(apiLogLabel, new LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1));
            apiLogLayout.addView(refreshApiLogButton);
            apiLogLayout.addView(openApiLogDirButton);
            apiLogLayout.addView(clearApiLogButton);
            apiCategory.addView(apiLogLayout);

            Consumer<ApiServerManager.BinaryApiServerStatus> listener = (apiServerStatus) -> {
                MuiModApi.postToUiThread(() -> {
                    apiStatusLabel.setText(binaryApiStatusTemplate.replace("{}", I18n.get(apiServerStatus.i18nKey())));
                    updateTuneWeaveVersionLabel(apiVersionLabel, apiServiceVersionTemplate);
                    updateApiLogLabel(apiLogLabel);
                });
            };
            List<Consumer<ApiServerManager.BinaryApiServerStatus>> apiStatusListeners = apiServerManager.getApiStatusListeners();
            apiStatusListeners.add(listener);
            addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    apiStatusListeners.remove(listener);
                    Util.ioPool().execute(() -> {
                        clientConfig.save();
                        serverConfig.save();
                    });
                }
            });
        } catch (Exception e) {
            instance = null;
            throw e;
        }
    }

    private static void updateTuneWeaveVersionLabel(TextView label, String template) {
        String loading = I18n.get(MusicHud.MOD_ID + ".text.tuneWeaveVersion.loading");
        String unavailable = I18n.get(MusicHud.MOD_ID + ".text.tuneWeaveVersion.unavailable");
        label.setText(template.replace("{}", loading));
        CompletableFuture.supplyAsync(
                () -> TuneWeaveApiClient.serverVersion().orElse(unavailable),
                MusicHud.EXECUTOR
        ).thenAccept(version -> MuiModApi.postToUiThread(
                () -> label.setText(template.replace("{}", version))));
    }

    private static void updateApiLogLabel(TextView label) {
        long[] stats = ApiServerManager.getInstance().getLogStats();
        String template = I18n.get(MusicHud.MOD_ID + ".text.apiLogInfo");
        label.setText(template.replace("{count}", String.valueOf(stats[0]))
                .replace("{size}", formatBytes(stats[1])));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kib = bytes / 1024.0;
        if (kib < 1024) return String.format("%.1f KiB", kib);
        double mib = kib / 1024.0;
        if (mib < 100) return String.format("%.1f MiB", mib);
        return String.format("%.0f MiB", mib);
    }

}
