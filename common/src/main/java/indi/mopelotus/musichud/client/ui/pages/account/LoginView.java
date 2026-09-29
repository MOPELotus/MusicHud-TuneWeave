package indi.mopelotus.musichud.client.ui.pages.account;

import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.mc.ui.ClampingScrollView;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.*;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService;
import indi.mopelotus.musichud.client.ui.CallbackGeneration;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.components.PlatformSelector;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import lombok.Getter;
import lombok.NonNull;
import net.minecraft.client.resources.language.I18n;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static icyllis.modernui.view.ViewGroup.LayoutParams.*;

public class LoginView extends LinearLayout implements ILoginView {
    @Getter private static LoginView instance;
    @Getter private final ViewPager pager;
    private final TabLayout tabs;
    private final TextView status;
    private final TuneWeaveClientService tuneWeave = TuneWeaveClientService.getInstance();
    private final CallbackGeneration callbacks = new CallbackGeneration();
    private TuneWeavePlatform platform;
    private List<LoginFeaturePolicy.LoginMethod> methods = List.of();
    private ILoginView[] loginViews = new ILoginView[0];

    public LoginView(Context context) { this(context, true); }
    public LoginView(Context context, boolean showPlatforms) {
        super(context); instance = this; platform = tuneWeave.defaultPlatform();
        setOrientation(VERTICAL); setGravity(Gravity.CENTER_HORIZONTAL); setMinimumWidth(dp(320));
        if (showPlatforms) {
            PlatformSelector selector = new PlatformSelector(context, TuneWeavePlatform.values());
            selector.setSelectedPlatform(platform);
            selector.setOnPlatformSelectedListener(value -> {
                platform = value; tuneWeave.setDefaultPlatform(value); loadMethods();
            });
            addView(selector, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        }
        status = new TextView(context); status.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        status.setOnClickListener(v -> loadMethods()); addView(status, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        tabs = new TabLayout(context); tabs.setTabMode(TabLayout.MODE_SCROLLABLE);
        tabs.setTabGravity(TabLayout.GRAVITY_CENTER); tabs.setBackground(null);
        addView(tabs, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        pager = new ViewPager(context); pager.setFocusableInTouchMode(true); pager.setKeyboardNavigationCluster(true);
        icyllis.modernui.view.OneShotPreDrawListener.add(pager, () -> {
            var animator = icyllis.modernui.animation.ObjectAnimator.ofFloat(pager,
                    View.ROTATION_Y, pager.isLayoutRtl() ? -45 : 45, 0);
            animator.setInterpolator(icyllis.modernui.animation.MotionEasingUtils.MOTION_EASING_EMPHASIZED);
            animator.start();
        });
        tabs.setElevation(dp(3));
        addView(pager, new LayoutParams(MATCH_PARENT, dp(640)));
        tabs.setupWithViewPager(pager);
        pager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override public void onPageScrolled(int position, float offset, int pixels) {}
            @Override public void onPageScrollStateChanged(int state) {}
            @Override public void onPageSelected(int position) {
                for (int i = 0; i < loginViews.length; i++)
                    if (i != position && loginViews[i] != null) loginViews[i].reset();
            }
        });
        addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { instance = LoginView.this; loadMethods(); }
            @Override public void onViewDetachedFromWindow(View v) {
                callbacks.next(); pager.setAdapter(null);
                if (instance == LoginView.this) instance = null;
            }
        });
    }
    private void loadMethods() {
        long ticket = callbacks.next(); TuneWeavePlatform selected = platform;
        pager.setAdapter(null); methods = List.of(); loginViews = new ILoginView[0];
        status.setVisibility(VISIBLE); status.setText(I18n.get(MusicHud.MOD_ID + ".text.loading"));
        var request = tuneWeave.prepareViewRequest(() -> tuneWeave.capabilities(selected));
        CompletableFuture.supplyAsync(request, MusicHud.EXECUTOR).whenComplete((caps, error) ->
            callbacks.post(MuiModApi::postToUiThread, ticket, () -> {
                if (!isAttachedToWindow() || selected != platform) return;
                if (error != null) { status.setText(I18n.get(MusicHud.MOD_ID + ".button.loadingError")); return; }
                methods = LoginFeaturePolicy.supportedMethods(selected, caps);
                loginViews = new ILoginView[methods.size()];
                if (methods.isEmpty()) { status.setText(I18n.get(MusicHud.MOD_ID + ".text.login.noSupportedMethod")); return; }
                status.setVisibility(GONE); pager.setAdapter(new Adapter()); pager.setCurrentItem(0, false);
            }));
    }
    @Override public void reset() {
        int position = pager.getCurrentItem();
        if (position < loginViews.length && loginViews[position] != null) loginViews[position].reset();
    }
    @Override public void errorText(String message) {
        int position = pager.getCurrentItem();
        if (position < loginViews.length && loginViews[position] != null) loginViews[position].errorText(message);
    }
    private class Adapter extends PagerAdapter {
        @Override public int getCount() { return methods.size(); }
        @NonNull @Override public Object instantiateItem(@NonNull ViewGroup container, int position) {
            ClampingScrollView scroll = new ClampingScrollView(getContext());
            ILoginView form = switch (methods.get(position)) {
                case QR_CODE -> new QRLoginView(getContext());
                case PASSWORD -> new PhonePasswordLoginView(getContext());
                case DEVICE_CODE -> new PhoneCodeLoginView(getContext());
                case CREDENTIAL_IMPORT -> throw new IllegalStateException("Credential import has no login tab");
            };
            loginViews[position] = form;
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            params.gravity = Gravity.CENTER_HORIZONTAL;
            scroll.addView((View) form, params); container.addView(scroll); return scroll;
        }
        @Override public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            container.removeView((View) object);
        }
        @Override public boolean isViewFromObject(@NonNull View view, @NonNull Object object) { return view == object; }
        @Override public CharSequence getPageTitle(int position) {
            return I18n.get(MusicHud.MOD_ID + ".text.login.page." + switch (methods.get(position)) {
                case QR_CODE -> "qrCode"; case PASSWORD -> "password"; case DEVICE_CODE -> "deviceCode";
                case CREDENTIAL_IMPORT -> throw new IllegalStateException();
            });
        }
    }
}
