package indi.mopelotus.musichud.client.services;

import icyllis.modernui.mc.MuiModApi;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.api.AutoConnectServerFilterType;
import indi.mopelotus.musichud.beans.user.Profile;
import indi.mopelotus.musichud.beans.user.ProfileConfigData;
import indi.mopelotus.musichud.client.interfaces.IClientEventService;
import indi.mopelotus.musichud.client.network.vanilla.VanillaPlayerProxy;
import indi.mopelotus.musichud.client.ui.pages.account.AccountBaseView;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveSession;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.interfaces.*;
import indi.mopelotus.musichud.server.api.ApiServerManager;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import indi.mopelotus.musichud.server.ServerPlayerRegistry;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class LoginService implements IClientLoginService {
    private static final ClientConfig clientConfig = ClientConfig.getInstance();
    private static final Logger logger = MusicHud.getLogger(LoginService.class);
    private static final TuneWeaveClientService tuneWeave = TuneWeaveClientService.getInstance();
    private static volatile LoginService instance = null;
    private final List<Consumer<LoginState>> loginStateListeners = new CopyOnWriteArrayList<>();
    private volatile LoginState loginState = getLoginState();
    @Getter
    private volatile String lastLoginErrorMessage;
    private final AtomicInteger platformSessionVersion = new AtomicInteger(0);
    public static LoginService getInstance() {
        if (instance == null) {
            synchronized (LoginService.class) {
                if (instance == null) {
                    instance = new LoginService();
                }
            }
        }
        return instance;
    }

    @Override
    public boolean isLogined() {
        return getLoginState() == LoginState.LOGGED_IN;
    }

    @Override
    public LoginState getLoginState() {
        Profile current = Profile.getCurrent();
        boolean tuneWeaveCredential = availableTuneWeavePlatform() != null;
        boolean realProfile = current != null && !current.equals(Profile.ANONYMOUS);
        if (tuneWeaveCredential && realProfile) {
            return LoginState.LOGGED_IN;
        }
        if (Profile.ANONYMOUS.equals(current)) {
            return LoginState.ANONYMOUS;
        }
        return LoginState.UNLOGGED;
    }

    @Override
    public Unregister addLoginStateListener(Consumer<LoginState> listener) {
        loginStateListeners.add(listener);
        return () -> loginStateListeners.remove(listener);
    }

    private void notifyLoginStateChanged() {
        LoginState state = getLoginState();
        if (state == loginState) return;
        loginState = state;
        loginStateListeners.forEach(listener -> listener.accept(state));
    }

    public void clearLastLoginErrorMessage() {
        lastLoginErrorMessage = null;
    }

    @Override
    public boolean hasStoredSession() {
        return availableTuneWeavePlatform() != null;
    }

    public boolean hasAnyTuneWeaveLogin() {
        for (TuneWeavePlatform platform : TuneWeavePlatform.values()) {
            if (tuneWeave.hasCredential(platform)) return true;
        }
        return false;
    }

    @Override
    public void restoreSession() {
        restoreTuneWeaveSession();
    }

    @Override
    public synchronized void logout() {
        int version = platformSessionVersion.incrementAndGet();
        TuneWeavePlatform platform = tuneWeave.defaultPlatform();
        Runnable logout = tuneWeave.prepareLogout(platform);
        MusicHud.EXECUTOR.execute(() -> {
            try {
                logout.run();
            } catch (RuntimeException error) {
                logger.warn("TuneWeave logout did not finish; only the original local session may be discarded");
            }
            synchronized (LoginService.this) {
                if (version != platformSessionVersion.get() || tuneWeave.defaultPlatform() != platform) return;
                MusicService.getInstance().invalidateUserCollections();
                // Retire the old account's playback engine without clearing the server's public state.
                MusicService.getInstance().recoverPlaybackAfterLogout();
                Profile.setCurrent(Profile.ANONYMOUS);
                notifyLoginStateChanged();
                refreshAccountView();
            }
        });
    }

    public synchronized void completeTuneWeaveLogin(TuneWeaveSession sessionProfile) {
        if (sessionProfile == null || !sessionProfile.authenticated()) {
            throw new IllegalArgumentException("TuneWeave did not return an authenticated profile");
        }
        platformSessionVersion.incrementAndGet();
        tuneWeave.setDefaultPlatform(sessionProfile.platform());
        MusicService.getInstance().invalidateUserCollections();
        Profile profile = sessionProfile.toMusicHudProfile();
        Profile.setCurrent(profile);
        ProfileConfigData profileConfigData = ProfileConfigData.getInstance();
        profileConfigData.setProfile(profile);
        profileConfigData.saveToConfig();
        lastLoginErrorMessage = null;
        notifyLoginStateChanged();
        refreshAccountView();
    }

    public synchronized void switchTuneWeavePlatform(TuneWeavePlatform platform) {
        tuneWeave.cancelLogins();
        int version = platformSessionVersion.incrementAndGet();
        tuneWeave.setDefaultPlatform(platform);
        MusicService.getInstance().invalidateUserCollections();
        if (!tuneWeave.hasCredential(platform)) {
            Profile.setCurrent(Profile.ANONYMOUS);
            notifyLoginStateChanged();
            refreshAccountView();
            return;
        }
        MusicHud.EXECUTOR.execute(() -> {
            try {
                TuneWeaveSession profile = tuneWeave.loadSession(platform);
                completeSessionIfCurrent(version, platform, profile);
            } catch (RuntimeException error) {
                if (version != platformSessionVersion.get()) return;
                lastLoginErrorMessage = error.getMessage();
                refreshAccountView();
            }
        });
    }

    public synchronized void restoreTuneWeaveSession() {
        TuneWeavePlatform platform = availableTuneWeavePlatform();
        if (platform == null) return;
        int version = platformSessionVersion.incrementAndGet();
        tuneWeave.setDefaultPlatform(platform);
        MusicHud.EXECUTOR.execute(() -> {
            try {
                TuneWeaveSession profile = tuneWeave.loadSession(platform);
                completeSessionIfCurrent(version, platform, profile);
            } catch (RuntimeException error) {
                if (version != platformSessionVersion.get()) return;
                lastLoginErrorMessage = error.getMessage();
                logger.warn("Failed to restore the client-owned TuneWeave session: {}", error.getMessage());
                refreshAccountView();
            }
        });
    }

    private static TuneWeavePlatform availableTuneWeavePlatform() {
        TuneWeavePlatform preferred = tuneWeave.defaultPlatform();
        if (tuneWeave.hasCredential(preferred)) return preferred;
        for (TuneWeavePlatform platform : TuneWeavePlatform.values()) {
            if (tuneWeave.hasCredential(platform)) return platform;
        }
        return null;
    }

    private static void refreshAccountView() {
        AccountBaseView accountBaseView = AccountBaseView.getInstance();
        if (accountBaseView != null) {
            MuiModApi.postToUiThread(accountBaseView::refresh);
        }
    }

    @RegisterMark
    public static final class RegisterImpl implements ClientRegister {
        @Override
        public void register() {
            IClientEventService eventService = IClientEventService.getInstance();
            ApiServerManager apiServerManager = ApiServerManager.getInstance();
            if (apiServerManager != null) {
                apiServerManager.getApiStatusListeners().add(status -> {
                    LoginService loginService = LoginService.getInstance();
                    if (status == ApiServerManager.BinaryApiServerStatus.RUNNING
                            && Minecraft.getInstance().player != null
                            && loginService.hasStoredSession()
                            && !loginService.isLogined()) {
                        loginService.restoreSession();
                    }
                });
            }
            eventService.registerClientPlayerJoin((player) -> {
                ConnectionManager.getInstance().onPlayerJoin(player, () -> {
                    ServerData currentServer = Minecraft.getInstance().getCurrentServer();
                    if (currentServer != null) {
                        boolean autoConnectToServer = clientConfig.getEnableAutoConnect();
                        if (autoConnectToServer) {
                            AutoConnectServerFilterType connectServerFilterType = clientConfig.getConnectServerFilterType();
                            if ((connectServerFilterType == AutoConnectServerFilterType.BLACK_LIST
                                    && clientConfig.getBlackList().stream().noneMatch(i -> Pattern.matches(i, currentServer.ip)))
                                    || (connectServerFilterType == AutoConnectServerFilterType.WHITE_LIST
                                    && clientConfig.getWhiteList().stream().anyMatch(i -> Pattern.matches(i, currentServer.ip)))) {
                                IConnectionManager.getInstance().connectToExternalServer();
                            } else {
                                IConnectionManager.getInstance().launchIsolated();
                            }
                        } else {
                            IConnectionManager.getInstance().launchIsolated();
                        }
                    } else {
                        // Single Player: try external first, fall back to isolated on timeout
                        IConnectionManager.getInstance().connectToExternalServer();
                    }
                });
            });
            eventService.registerClientPlayerQuit(player -> ConnectionManager.getInstance().onPlayerQuit(player));
            eventService.registerClientTickPost(() -> ConnectionManager.getInstance().onClientTick());
        }
    }

    public synchronized void completeTuneWeaveLogin(
            indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveLoginAttempt attempt, TuneWeaveSession profile) {
        tuneWeave.publishLogin(attempt, () -> completeTuneWeaveLogin(profile));
    }

    private synchronized void completeSessionIfCurrent(int version, TuneWeavePlatform platform, TuneWeaveSession profile) {
        if (version == platformSessionVersion.get() && tuneWeave.defaultPlatform() == platform) {
            completeTuneWeaveLogin(profile);
        }
    }
}
