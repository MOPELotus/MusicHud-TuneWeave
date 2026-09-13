package indi.mopelotus.musichud.client.ui.pages;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.mc.ConfigItem;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.mc.ui.PreferencesFragment;
import icyllis.modernui.text.SpannableString;
import icyllis.modernui.text.style.URLSpan;
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
import java.util.concurrent.CancellationException;
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

            Button downloadApiServerButton = createDownloadApiButton(context, backgroundFactory, serverApiBinaryPathInput);

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
            apiServerStatusLayout.addView(downloadApiServerButton);
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

    private @NotNull Button createDownloadApiButton(Context context, ButtonInsetBackgroundFactory backgroundFactory, EditText[] serverApiBinaryPathInput) {
        Button downloadApiServerButton = new Button(context);
        downloadApiServerButton.setText(I18n.get(MusicHud.MOD_ID + ".button.downloadApiServer"));
        downloadApiServerButton.setTextColor(Theme.PRIMARY_COLOR);
        downloadApiServerButton.setTextSize(14);
        downloadApiServerButton.setBackground(backgroundFactory.newBackgroundDrawable());

        final String downloadingText = I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.downloading");
        final String button1Text = I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.button1");
        final String button2Text = I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.button2");
        final String button1CancelText = I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.downloading.button1");
        final String button2hideText = I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.downloading.button2");
        final String button1YesText = I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.done.button1");
        final String button2NoText = I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.done.button2");

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(context);
        title.setTextSize(Theme.TEXT_SIZE_LARGE);

        TextView description = new TextView(context);
        String desc = I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.description");
        description.setText(desc);
        description.setTextSize(Theme.TEXT_SIZE_NORMAL);

        TextView descriptionUrl = new TextView(context);
        String url = I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.description.url");
        int indexOfUrl = url.indexOf("{url}");
        String manifestUrl = ApiServerFetcher.TUNEWEAVE_MANIFEST_URL;
        String replace = url.replace("{url}", manifestUrl);
        SpannableString spannableString = new SpannableString(replace);
        spannableString.setSpan(new URLSpan(manifestUrl), indexOfUrl, indexOfUrl + manifestUrl.length(), SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        descriptionUrl.setText(spannableString);
        descriptionUrl.setTextSize(Theme.TEXT_SIZE_NORMAL);
        descriptionUrl.setOnClickListener(v -> Util.getPlatform().openUri(manifestUrl));

        Path path = Paths.get(serverConfig.getServerApiBinaryExecutablePath());
        while (!Files.isDirectory(path)) {
            path = path.getParent();
            if (path == null) {
                path = Paths.get("tuneweave-music-client");
                break;
            }
        }
        final Path[] targetDir = {path};
        final ApiServerFetcher.ReleaseSummary[] latestRelease = {null};

        LinearLayout directoryLayout = new LinearLayout(context);
        directoryLayout.setOrientation(LinearLayout.HORIZONTAL);

        TextView existingVersionWarning = new TextView(context);
        existingVersionWarning.setTextSize(Theme.TEXT_SIZE_NORMAL);
        existingVersionWarning.setTextColor(Theme.WARN_TEXT_COLOR);
        existingVersionWarning.setVisibility(GONE);

        TextView directoryText = new TextView(context);
        directoryText.setTextSize(Theme.TEXT_SIZE_NORMAL);
        directoryText.setText(I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.dir"));

        EditText directoryTextInput = new EditText(context, null, R.attr.editTextOutlinedStyle);
        directoryTextInput.setHint(I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.dir.field.hint"));
        directoryTextInput.setTextSize(Theme.TEXT_SIZE_NORMAL);
        directoryTextInput.setTextColor(Theme.NORMAL_TEXT_COLOR);
        directoryTextInput.setText(targetDir[0].toString());

        Button selectDirectoryButton = new Button(context);
        selectDirectoryButton.setText(I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.dir.button.select"));
        selectDirectoryButton.setTextColor(Theme.PRIMARY_COLOR);
        selectDirectoryButton.setTextSize(Theme.TEXT_SIZE_NORMAL);
        selectDirectoryButton.setBackground(backgroundFactory.newBackgroundDrawable());
        selectDirectoryButton.setOnClickListener(v -> {
            Path defaultPath = targetDir[0].toAbsolutePath();
            String folder = TinyFileDialogs.tinyfd_selectFolderDialog(
                    I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.dir.dialog.title"), defaultPath.toString());
            if (folder != null) {
                targetDir[0] = Paths.get(folder);
                directoryTextInput.setText(folder);
                checkExistingVersion(targetDir[0], latestRelease[0] != null ? latestRelease[0].tag() : null, existingVersionWarning);
            }
        });

        LayoutParams params1 = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT, 0);
        params1.setMargins(0, 0, dp(8), 0);
        directoryLayout.addView(directoryText, params1);
        directoryLayout.addView(directoryTextInput, new LayoutParams(0, WRAP_CONTENT, 1));
        directoryLayout.addView(selectDirectoryButton, new LayoutParams(WRAP_CONTENT, MATCH_PARENT, 0));

        LinearLayout proxyLayout = new LinearLayout(context);
        proxyLayout.setOrientation(HORIZONTAL);
        proxyLayout.setGravity(Gravity.CENTER_VERTICAL);

        TextView proxyText = new TextView(context);
        proxyText.setTextSize(Theme.TEXT_SIZE_NORMAL);
        proxyText.setText(I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.proxy"));

        Spinner proxySpinner = new Spinner(context);
        final Spinner[] proxySpinnerRef = {null};
        proxySpinnerRef[0] = proxySpinner;
        String[] proxyLabels = Arrays.stream(ApiServerFetcher.DownloadProxy.values())
                .map(dp -> I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.proxy." + dp.name())).toArray(String[]::new);
        ArrayAdapter<String> proxyAdapter = new ArrayAdapter<>(context, proxyLabels) {
            @Override
            @NotNull
            public View getView(int position, View convertView, @NotNull ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextSize(Theme.TEXT_SIZE_NORMAL);
                return tv;
            }
            @Override
            public View getDropDownView(int position, View convertView, @NotNull ViewGroup parent) {
                View dropDownView = super.getDropDownView(position, convertView, parent);
                if (dropDownView instanceof TextView tv) {
                    tv.setTextSize(Theme.TEXT_SIZE_NORMAL);
                    return tv;
                } else {
                    return dropDownView;
                }
            }
        };
        proxySpinner.setAdapter(proxyAdapter);
        proxySpinner.setSelection(0);

        LayoutParams params = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT, 0);
        params.setMargins(0, 0, dp(8), 0);
        proxyLayout.addView(proxyText, params);
        proxyLayout.addView(proxySpinner, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT, 1));

        LayoutParams proxyParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        proxyParams.setMargins(0, 0, 0, dp(8));

        LinearLayout releaseInfoLayout = new LinearLayout(context);
        releaseInfoLayout.setOrientation(LinearLayout.HORIZONTAL);
        releaseInfoLayout.setGravity(Gravity.CENTER_VERTICAL);

        TextView releaseNameLabel = new TextView(context);
        releaseNameLabel.setTextSize(Theme.TEXT_SIZE_NORMAL);
        releaseNameLabel.setTextColor(Theme.NORMAL_TEXT_COLOR);
        releaseNameLabel.setText(I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.release.fetching"));

        Button refreshReleaseButton = new Button(context);
        refreshReleaseButton.setText(I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.release.refresh"));
        refreshReleaseButton.setTextColor(Theme.PRIMARY_COLOR);
        refreshReleaseButton.setTextSize(Theme.TEXT_SIZE_NORMAL);
        refreshReleaseButton.setBackground(backgroundFactory.newBackgroundDrawable());
        refreshReleaseButton.setOnClickListener(v -> {
            refreshReleaseInfo(releaseNameLabel, latestRelease, targetDir, existingVersionWarning, proxySpinnerRef[0]);
        });

        releaseInfoLayout.addView(releaseNameLabel, new LayoutParams(0, WRAP_CONTENT, 1));
        releaseInfoLayout.addView(refreshReleaseButton, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

        LinearLayout progressLayout = new LinearLayout(context);
        progressLayout.setOrientation(LinearLayout.HORIZONTAL);
        progressLayout.setGravity(Gravity.CENTER_VERTICAL);

        ProgressBar progressBar = new ProgressBar(context, null, R.attr.progressBarStyleHorizontal);
        progressBar.setMin(0);
        progressBar.setMax(100);

        TextView progressText = new TextView(context);
        progressText.setTextSize(Theme.TEXT_SIZE_NORMAL);
        progressText.setTextColor(Theme.NORMAL_TEXT_COLOR);

        LayoutParams progParams = new LayoutParams(0, dp(24), 1);
        progParams.setMargins(0, 0, progressBar.dp(4), 0);
        progressLayout.addView(progressBar, progParams);
        progressLayout.addView(progressText, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

//        LayoutParams params4 = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
//        params4.setMargins(0, 0, 0, dp(8));
        LayoutParams params5 = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        params5.setMargins(0, dp(4), 0, dp(4));

        LinearLayout idlePage = new LinearLayout(context);
        idlePage.setOrientation(LinearLayout.VERTICAL);
//        idlePage.addView(title, params4);
        idlePage.addView(description);
        idlePage.addView(descriptionUrl);
        idlePage.addView(directoryLayout, params5);
        idlePage.addView(proxyLayout, proxyParams);
        idlePage.addView(releaseInfoLayout);
        idlePage.addView(existingVersionWarning);

//        TextView dlTitle = new TextView(context);
//        dlTitle.setText(baseTitle);
//        dlTitle.setTextSize(Theme.TEXT_SIZE_LARGE);

        TextView dlDesc = new TextView(context);
        dlDesc.setText(I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.downloading.description"));
        dlDesc.setTextSize(Theme.TEXT_SIZE_NORMAL);

        LinearLayout progressPage = new LinearLayout(context);
        progressPage.setOrientation(LinearLayout.VERTICAL);
        progressPage.setVisibility(GONE);
//        LayoutParams params6 = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
//        params6.setMargins(0, 0, 0, dp(8));
//        progressPage.addView(dlTitle, params6);
        progressPage.addView(dlDesc);
        progressPage.addView(progressLayout);

        LinearLayout donePage = new LinearLayout(context);
        donePage.setOrientation(LinearLayout.VERTICAL);
        donePage.setVisibility(GONE);

        String doneTitle = I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.done.title");

        TextView doneDesc = new TextView(context);
        doneDesc.setTextSize(Theme.TEXT_SIZE_NORMAL);
        doneDesc.setTextColor(Theme.NORMAL_TEXT_COLOR);

        donePage.addView(doneDesc);

        content.addView(idlePage);
        content.addView(progressPage);
        content.addView(donePage);

        ApiDownloadSession downloadSession = ApiDownloadSession.getInstance();

        String baseTitle = I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.title");
        java.util.function.Consumer<ApiDownloadSession.Page> setPage = page -> {
            switch (page) {
                case IDLE -> {
                    title.setText(baseTitle);
                    idlePage.setVisibility(VISIBLE);
                    progressPage.setVisibility(GONE);
                    donePage.setVisibility(GONE);
                }
                case DOWNLOADING -> {
                    title.setText(baseTitle);
                    idlePage.setVisibility(GONE);
                    progressPage.setVisibility(VISIBLE);
                    donePage.setVisibility(GONE);
                }
                case DONE -> {
                    title.setText(doneTitle);
                    idlePage.setVisibility(GONE);
                    progressPage.setVisibility(GONE);
                    donePage.setVisibility(VISIBLE);
                }
            }
        };

        Modal.ActionButton cancelBtn = new Modal.ActionButton(button2Text, (btn, dialog) -> {
            if (ApiDownloadSession.Page.DONE.equals(downloadSession.snapshot().page())) {
                downloadSession.reset();
                setPage.accept(ApiDownloadSession.Page.IDLE);
                downloadApiServerButton.setText(I18n.get(MusicHud.MOD_ID + ".button.downloadApiServer"));
            }
            dialog.dismiss();
        });

        Modal.ActionButton confirmButton = new Modal.ActionButton(button1Text, (btn, dialog) -> {
            ApiDownloadSession.Page page = downloadSession.snapshot().page();
            if (ApiDownloadSession.Page.IDLE.equals(page)) {
                targetDir[0] = Paths.get(directoryTextInput.getText().toString().trim());
                if (!downloadSession.tryStart(targetDir[0])) return;
                btn.setText(button1CancelText);
                cancelBtn.setText(button2hideText);
                setPage.accept(ApiDownloadSession.Page.DOWNLOADING);
                progressBar.setProgress(0);
                progressText.setText("");
                downloadApiServerButton.setText(downloadingText);

                try {
                    Files.createDirectories(targetDir[0]);
                } catch (IOException ignored) {}

                ApiBinaryUpdateService updateService = ApiBinaryUpdateService.getInstance();

                ApiServerFetcher.DownloadProxy selectedProxy = ApiServerFetcher.DownloadProxy.values()[proxySpinner.getSelectedItemPosition()];
                long sessionGeneration = downloadSession.generation();

                CompletableFuture<ApiBinaryUpdateService.DownloadedRelease> future = updateService.downloadToTemp(targetDir[0], selectedProxy,
                        (downloaded, total) -> downloadSession.reportProgress(downloaded, total, sessionGeneration),
                        downloadSession.cancelFlag());
                downloadSession.setFuture(future);
                future.thenAccept(downloaded -> {
                    if (!downloadSession.complete(sessionGeneration, downloaded)) return;
                    MuiModApi.postToUiThread(() -> {
                        downloaded.tempFile().toFile().deleteOnExit();
                        ToastUtil.show(I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.done"));
                        doneDesc.setText(I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.done.description")
                                .replace("{path}", downloaded.tempFile().toString()));
                        setPage.accept(ApiDownloadSession.Page.DONE);
                        btn.setText(button1YesText);
                        btn.setEnabled(true);
                        cancelBtn.setText(button2NoText);
                        cancelBtn.getButton().setVisibility(VISIBLE);
                        cancelBtn.getButton().setScaleX(1f);
                        cancelBtn.getButton().setAlpha(1f);
                        downloadApiServerButton.setText(I18n.get(MusicHud.MOD_ID + ".button.downloadApiServerDone"));
                    });
                }).exceptionally(ex -> {
                    if (!downloadSession.fail(sessionGeneration)) return null;
                    MuiModApi.postToUiThread(() -> {
                        if (ex instanceof CancellationException || ex.getCause() instanceof CancellationException) {
                            ToastUtil.show(I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.cancelled"));
                        } else {
                            ToastUtil.show(I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.error") + ": " + ex.getMessage());
                        }
                        setPage.accept(ApiDownloadSession.Page.IDLE);
                        btn.setText(button1Text);
                        btn.setEnabled(true);
                        cancelBtn.setText(button2Text);
                        cancelBtn.getButton().setVisibility(VISIBLE);
                        cancelBtn.getButton().setScaleX(1f);
                        cancelBtn.getButton().setAlpha(1f);
                        downloadApiServerButton.setText(I18n.get(MusicHud.MOD_ID + ".button.downloadApiServer"));
                    });
                    return null;
                });
            } else if (ApiDownloadSession.Page.DOWNLOADING.equals(page)) {
                downloadSession.cancel();
                setPage.accept(ApiDownloadSession.Page.IDLE);
                btn.setText(button1Text);
                btn.setEnabled(true);
                cancelBtn.setText(button2Text);
                downloadApiServerButton.setText(I18n.get(MusicHud.MOD_ID + ".button.downloadApiServer"));
            } else if (ApiDownloadSession.Page.DONE.equals(page)) {
                ApiBinaryUpdateService updateService = ApiBinaryUpdateService.getInstance();
                ApiBinaryUpdateService.DownloadedRelease downloaded = downloadSession.snapshot().release();
                Path finalPath = downloaded == null ? null
                        : updateService.resolveFinalPath(downloaded.tempFile(), downloaded.tag());
                if (finalPath == null) {
                    ToastUtil.show(I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.renameFailed"));
                    return;
                }
                Path installDir = downloadSession.snapshot().targetDir();
                if (installDir == null || !updateService.recordManagedInstallation(installDir, downloaded.tag(),
                        downloaded.version(), finalPath.getFileName().toString())) {
                    ToastUtil.show(I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.renameFailed"));
                    return;
                }
                String configPath = updateService.relativizePath(finalPath);
                serverConfig.setServerApiBinaryExecutablePath(configPath);
                serverConfig.save();
                if (serverApiBinaryPathInput[0] != null) {
                    serverApiBinaryPathInput[0].setText(configPath);
                }
                ApiServerManager apiServer = ApiServerManager.getInstance();
                if (apiServer != null) {
                    apiServer.restartApiServer();
                }
                downloadSession.reset();
                downloadApiServerButton.setText(I18n.get(MusicHud.MOD_ID + ".button.downloadApiServer"));
                btn.setText(button1Text);
                cancelBtn.setText(button2Text);
                dialog.dismiss();
            }
        });

        Runnable sessionListener = () -> MuiModApi.postToUiThread(() -> {
            ApiDownloadSession.Snapshot snapshot = downloadSession.snapshot();
            setPage.accept(snapshot.page());
            if (snapshot.targetDir() != null) {
                targetDir[0] = snapshot.targetDir();
                directoryTextInput.setText(snapshot.targetDir().toString());
            }
            if (snapshot.page() == ApiDownloadSession.Page.DOWNLOADING) {
                progressBar.setProgress(snapshot.total() > 0
                        ? (int) Math.min(100, snapshot.downloaded() * 100 / snapshot.total()) : 0);
                progressText.setText(snapshot.total() > 0
                        ? formatBytes(snapshot.downloaded()) + " / " + formatBytes(snapshot.total())
                        : formatBytes(snapshot.downloaded()));
                downloadApiServerButton.setText(downloadingText);
            } else if (snapshot.page() == ApiDownloadSession.Page.DONE && snapshot.release() != null) {
                doneDesc.setText(I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.done.description")
                        .replace("{path}", snapshot.release().tempFile().toString()));
                downloadApiServerButton.setText(I18n.get(MusicHud.MOD_ID + ".button.downloadApiServerDone"));
            } else if (snapshot.page() == ApiDownloadSession.Page.IDLE) {
                downloadApiServerButton.setText(I18n.get(MusicHud.MOD_ID + ".button.downloadApiServer"));
            }
        });
        Modal dialog = new Modal(context, title, content, confirmButton, cancelBtn);

        dialog.setOnDismissListener(() -> {
            downloadSession.removeListener(sessionListener);
        });

        downloadApiServerButton.setOnClickListener((v) -> {
            refreshReleaseInfo(releaseNameLabel, latestRelease, targetDir, existingVersionWarning, proxySpinnerRef[0]);
            ApiDownloadSession.Snapshot snapshot = downloadSession.snapshot();
            if (snapshot.targetDir() != null) {
                targetDir[0] = snapshot.targetDir();
                directoryTextInput.setText(snapshot.targetDir().toString());
            }
            setPage.accept(snapshot.page());
            downloadSession.addListener(sessionListener);
            dialog.show();
        });
        return downloadApiServerButton;
    }

    private void refreshReleaseInfo(TextView releaseLabel, ApiServerFetcher.ReleaseSummary[] latest, Path[] targetDir, TextView warning, Spinner proxySpinner) {
        releaseLabel.setText(I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.release.fetching"));
        ApiBinaryUpdateService.getInstance().fetchLatestRelease().thenAccept(r -> {
            if (r != null) {
                MuiModApi.postToUiThread(() -> {
                    latest[0] = r;
                    releaseLabel.setText(I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.release.label")
                            .replace("{tag}", r.tag()));
                    String oldVersion = ApiBinaryUpdateService.getInstance().findInstalledVersion(targetDir[0], r.tag());
                    if (oldVersion != null) {
                        warning.setText(I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.existingVersion")
                                .replace("{version}", oldVersion).replace("{tag}", r.tag()));
                        warning.setVisibility(VISIBLE);
                    } else {
                        warning.setVisibility(GONE);
                    }
                });
            } else {
                MuiModApi.postToUiThread(() -> releaseLabel.setText(I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.release.failed")));
            }
        }).exceptionally(ex -> {
            MuiModApi.postToUiThread(() -> releaseLabel.setText(I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.release.failed")));
            return null;
        });
    }

    private void checkExistingVersion(Path targetDir, String tag, TextView warning) {
        if (tag == null) {
            warning.setVisibility(GONE);
            return;
        }
        String oldVersion = ApiBinaryUpdateService.getInstance().findInstalledVersion(targetDir, tag);
        if (oldVersion != null) {
            warning.setText(I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.existingVersion")
                    .replace("{version}", oldVersion).replace("{tag}", tag));
            warning.setVisibility(VISIBLE);
        } else {
            warning.setVisibility(GONE);
        }
    }
}
