package indi.mopelotus.musichud.client.ui.pages;

import icyllis.modernui.R;
import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.text.SpannableString;
import icyllis.modernui.text.style.URLSpan;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.*;
import icyllis.modernui.widget.LinearLayout.LayoutParams;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.ToastUtil;
import indi.mopelotus.musichud.client.ui.components.Modal;
import indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory;
import indi.mopelotus.musichud.interfaces.ServerConfig;
import indi.mopelotus.musichud.server.api.*;
import net.minecraft.client.resources.language.I18n;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import static icyllis.modernui.view.ViewGroup.LayoutParams.*;
import static icyllis.modernui.view.View.*;
import static icyllis.modernui.widget.LinearLayout.HORIZONTAL;

/** Download controls compiled only into the standard distribution. */
public final class ApiDistributionUi {
    private static final ServerConfig serverConfig = ServerConfig.getInstance();

    private ApiDistributionUi() {}

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kib = bytes / 1024.0;
        if (kib < 1024) return String.format("%.1f KiB", kib);
        double mib = kib / 1024.0;
        if (mib < 100) return String.format("%.1f MiB", mib);
        return String.format("%.0f MiB", mib);
    }

    public static @NotNull View create(Context context, InsetBackgroundFactory backgroundFactory, EditText[] serverApiBinaryPathInput) {
        Button downloadApiServerButton = new Button(context);
        downloadApiServerButton.setText(I18n.get(MusicHud.MOD_ID + ".button.downloadApiServer"));
        downloadApiServerButton.setTextColor(Theme.PRIMARY_COLOR);
        downloadApiServerButton.setTextSize(14);
        backgroundFactory.applyBackgroundTo(downloadApiServerButton);

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
        descriptionUrl.setOnClickListener(v -> com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create(manifestUrl)));

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
        backgroundFactory.applyBackgroundTo(selectDirectoryButton);
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
        params1.setMargins(0, 0, downloadApiServerButton.dp(8), 0);
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
        params.setMargins(0, 0, downloadApiServerButton.dp(8), 0);
        proxyLayout.addView(proxyText, params);
        proxyLayout.addView(proxySpinner, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT, 1));

        LayoutParams proxyParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        proxyParams.setMargins(0, 0, 0, downloadApiServerButton.dp(8));

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
        backgroundFactory.applyBackgroundTo(refreshReleaseButton);
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

        LayoutParams progParams = new LayoutParams(0, downloadApiServerButton.dp(24), 1);
        progParams.setMargins(0, 0, progressBar.dp(4), 0);
        progressLayout.addView(progressBar, progParams);
        progressLayout.addView(progressText, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

//        LayoutParams params4 = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
//        params4.setMargins(0, 0, 0, downloadApiServerButton.dp(8));
        LayoutParams params5 = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        params5.setMargins(0, downloadApiServerButton.dp(4), 0, downloadApiServerButton.dp(4));

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
//        params6.setMargins(0, 0, 0, downloadApiServerButton.dp(8));
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
                confirmButton.setText(button1CancelText);
                cancelBtn.setText(button2hideText);
                progressBar.setProgress(snapshot.total() > 0
                        ? (int) Math.min(100, snapshot.downloaded() * 100 / snapshot.total()) : 0);
                progressText.setText(snapshot.total() > 0
                        ? formatBytes(snapshot.downloaded()) + " / " + formatBytes(snapshot.total())
                        : formatBytes(snapshot.downloaded()));
                downloadApiServerButton.setText(downloadingText);
            } else if (snapshot.page() == ApiDownloadSession.Page.DONE && snapshot.release() != null) {
                confirmButton.setText(button1YesText);
                cancelBtn.setText(button2NoText);
                doneDesc.setText(I18n.get(MusicHud.MOD_ID + ".modal.downloadApiServer.done.description")
                        .replace("{path}", snapshot.release().tempFile().toString()));
                downloadApiServerButton.setText(I18n.get(MusicHud.MOD_ID + ".button.downloadApiServerDone"));
            } else if (snapshot.page() == ApiDownloadSession.Page.IDLE) {
                confirmButton.setText(button1Text);
                cancelBtn.setText(button2Text);
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
            sessionListener.run();
        });
        return downloadApiServerButton;
    }

    private static void refreshReleaseInfo(TextView releaseLabel, ApiServerFetcher.ReleaseSummary[] latest, Path[] targetDir, TextView warning, Spinner proxySpinner) {
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

    private static void checkExistingVersion(Path targetDir, String tag, TextView warning) {
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
