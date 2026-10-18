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

final class ApiSettingsUi {
    private ApiSettingsUi() {}
    static void create(Context context, ConfigView owner, LinearLayout view, ServerConfig serverConfig) {
        ApiServerManager apiServerManager = ApiServerManager.getInstance();
            var apiCategory = PreferencesFragment.createCategoryList(view, I18n.get(MusicHud.MOD_ID + ".config.category.apiServer"));
            LinearLayout.LayoutParams params1 = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            params1.setMargins(0, owner.dp(6), 0, owner.dp(128));
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
                LinearLayout inputBox = ConfigView.createFullWidthInputBox(context, I18n.get(MusicHud.MOD_ID + ".config.apiServer.serverApiBaseUrl"));
                EditText input = inputBox.findViewById(R.id.input);
                if (input != null) {
                    input.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
                    input.setText(ClientConfig.getInstance().getTuneWeaveBaseUrl());
                    input.setOnKeyListener((v, c, e) -> {
                        if (c == GLFW.GLFW_KEY_ENTER) {
                            input.clearFocus();
                            return true;
                        }
                        return false;
                    });
                    input.setOnFocusChangeListener((v, b) -> {
                        if (!b) {
                            if (!ApiConnectionSettings.apply(input.getText().toString(), ClientConfig.getInstance(), serverConfig)) {
                                input.setText(ClientConfig.getInstance().getTuneWeaveBaseUrl());
                                ToastUtil.show(I18n.get(MusicHud.MOD_ID + ".text.invalidServiceUrl"));
                            }
                        }
                    });
                }
                apiCategory.addView(inputBox);
            }

            final EditText[] serverApiBinaryPathInput = {null};

            {
                LinearLayout inputBox = ConfigView.createFullWidthInputBox(context, I18n.get(MusicHud.MOD_ID + ".config.apiServer.serverApiBinaryExecutablePath"));
                EditText input = inputBox.findViewById(R.id.input);
                if (input != null) {
                    input.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
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
            LinearLayout.LayoutParams params3 = new LinearLayout.LayoutParams(MATCH_PARENT, owner.dp(44));
            params3.setMargins(owner.dp(6), 0, owner.dp(6), 0);
            apiServerStatusLayout.setLayoutParams(params3);

            TextView apiStatusLabel = new TextView(context);
            apiStatusLabel.setTextSize(14);
            String binaryApiStatusTemplate = I18n.get(MusicHud.MOD_ID + ".text.binaryApiStatus");
            apiStatusLabel.setText(binaryApiStatusTemplate.replace("{}", I18n.get(apiServerManager.getBinaryApiServerStatus().i18nKey())));

            InsetBackgroundFactory backgroundFactory = InsetBackgroundFactory.builder().inset(0).padding(new InsetBackgroundFactory.Padding(owner.dp(8), owner.dp(4), owner.dp(8), owner.dp(4))).build();

            View distributionControl = ApiDistributionUi.create(context, backgroundFactory, serverApiBinaryPathInput);

            Button stopApiServerButton = new Button(context);
            stopApiServerButton.setText(I18n.get(MusicHud.MOD_ID + ".button.stopApiServer"));
            stopApiServerButton.setTextColor(Theme.PRIMARY_COLOR);
            stopApiServerButton.setTextSize(14);
            backgroundFactory.applyBackgroundTo(stopApiServerButton);
            stopApiServerButton.setOnClickListener((v1) -> {
                apiServerManager.stopApiServer();
            });

            Button restartApiServerButton = new Button(context);
            restartApiServerButton.setText(I18n.get(MusicHud.MOD_ID + ".button.restartApiServer"));
            restartApiServerButton.setTextColor(Theme.PRIMARY_COLOR);
            restartApiServerButton.setTextSize(14);
            backgroundFactory.applyBackgroundTo(restartApiServerButton);
            restartApiServerButton.setOnClickListener((v1) -> {
                apiServerManager.restartApiServer();
            });

            apiServerStatusLayout.addView(apiStatusLabel, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1));
            apiServerStatusLayout.addView(distributionControl);
            apiServerStatusLayout.addView(stopApiServerButton);
            apiServerStatusLayout.addView(restartApiServerButton);
            apiCategory.addView(apiServerStatusLayout);

            LinearLayout apiVersionLinearLayout = new LinearLayout(context);
            apiVersionLinearLayout.setOrientation(LinearLayout.HORIZONTAL);
            apiVersionLinearLayout.setGravity(Gravity.LEFT);
            apiVersionLinearLayout.setVerticalGravity(Gravity.CENTER);
            LinearLayout.LayoutParams params2 = new LinearLayout.LayoutParams(MATCH_PARENT, owner.dp(44));
            params2.setMargins(owner.dp(6), 0, owner.dp(6), 0);
            apiVersionLinearLayout.setLayoutParams(params2);

            TextView apiVersionLabel = new TextView(context);
            apiVersionLabel.setTextSize(14);
            String apiServiceVersionTemplate = I18n.get(MusicHud.MOD_ID + ".text.apiServiceVersion");
            ConfigView.updateTuneWeaveVersionLabel(apiVersionLabel, apiServiceVersionTemplate);

            Button checkVersionButton = new Button(context);
            checkVersionButton.setText(I18n.get(MusicHud.MOD_ID + ".button.checkApiServerVersion"));
            checkVersionButton.setTextColor(Theme.PRIMARY_COLOR);
            checkVersionButton.setTextSize(14);
            backgroundFactory.applyBackgroundTo(checkVersionButton);
            checkVersionButton.setOnClickListener((v) -> {
                ConfigView.updateTuneWeaveVersionLabel(apiVersionLabel, apiServiceVersionTemplate);
            });

            apiVersionLinearLayout.addView(apiVersionLabel, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1));
            apiVersionLinearLayout.addView(checkVersionButton);
            Button checkUpdateButton = new Button(context);
            checkUpdateButton.setText(I18n.get(MusicHud.MOD_ID + ".update.service.check"));
            checkUpdateButton.setTextColor(Theme.PRIMARY_COLOR);
            checkUpdateButton.setTextSize(14);
            backgroundFactory.applyBackgroundTo(checkUpdateButton);
            checkUpdateButton.setOnClickListener(v -> indi.mopelotus.musichud.client.update.ClientUpdateUi.manualServiceCheck());
            apiVersionLinearLayout.addView(checkUpdateButton);
            apiCategory.addView(apiVersionLinearLayout);

            LinearLayout apiLogLayout = new LinearLayout(context);
            apiLogLayout.setOrientation(LinearLayout.HORIZONTAL);
            apiLogLayout.setGravity(Gravity.LEFT);
            apiLogLayout.setVerticalGravity(Gravity.CENTER);
            LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(MATCH_PARENT, owner.dp(44));
            logParams.setMargins(owner.dp(6), 0, owner.dp(6), 0);
            apiLogLayout.setLayoutParams(logParams);

            TextView apiLogLabel = new TextView(context);
            apiLogLabel.setTextSize(14);
            updateApiLogLabel(apiLogLabel);

            Button refreshApiLogButton = new Button(context);
            refreshApiLogButton.setText(I18n.get(MusicHud.MOD_ID + ".button.refreshApiLog"));
            refreshApiLogButton.setTextColor(Theme.PRIMARY_COLOR);
            refreshApiLogButton.setTextSize(14);
            backgroundFactory.applyBackgroundTo(refreshApiLogButton);
            refreshApiLogButton.setOnClickListener(v -> {
                updateApiLogLabel(apiLogLabel);
            });

            Button openApiLogDirButton = new Button(context);
            openApiLogDirButton.setText(I18n.get(MusicHud.MOD_ID + ".button.openApiLogDir"));
            openApiLogDirButton.setTextColor(Theme.PRIMARY_COLOR);
            openApiLogDirButton.setTextSize(14);
            backgroundFactory.applyBackgroundTo(openApiLogDirButton);
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
            backgroundFactory.applyBackgroundTo(clearApiLogButton);
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

            apiLogLayout.addView(apiLogLabel, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1));
            apiLogLayout.addView(refreshApiLogButton);
            apiLogLayout.addView(openApiLogDirButton);
            apiLogLayout.addView(clearApiLogButton);
            apiCategory.addView(apiLogLayout);

            Consumer<ApiServerManager.BinaryApiServerStatus> listener = (apiServerStatus) -> {
                MuiModApi.postToUiThread(() -> {
                    apiStatusLabel.setText(binaryApiStatusTemplate.replace("{}", I18n.get(apiServerStatus.i18nKey())));
                    ConfigView.updateTuneWeaveVersionLabel(apiVersionLabel, apiServiceVersionTemplate);
                    updateApiLogLabel(apiLogLabel);
                });
            };
            List<Consumer<ApiServerManager.BinaryApiServerStatus>> apiStatusListeners = apiServerManager.getApiStatusListeners();
            apiStatusListeners.add(listener);
            owner.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    apiStatusListeners.remove(listener);
                }
            });
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
