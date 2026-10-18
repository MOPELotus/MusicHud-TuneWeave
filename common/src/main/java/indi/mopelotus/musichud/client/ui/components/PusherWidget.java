package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.animation.LayoutTransition;
import icyllis.modernui.core.Context;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;
import indi.mopelotus.musichud.beans.music.PusherInfo;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.utils.PlayerInfoUtil;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

/** Shared pusher presentation; each binding resolves its own player and rejects stale lookups. */
public class PusherWidget extends LinearLayout {
    private final PlayerHeadView pusherHeadView;
    private final TextView pusherText;
    private long binding;

    public PusherWidget(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        pusherHeadView = new PlayerHeadView(context);
        var headParams = new LayoutParams(dp(18), dp(18));
        headParams.setMargins(0, 0, dp(4), 0);
        addView(pusherHeadView, headParams);
        pusherText = new TextView(context);
        pusherText.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        pusherText.setTextSize(Theme.TEXT_SIZE_NORMAL);
        pusherText.setSingleLine();
        var textParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        textParams.gravity = Gravity.CENTER_VERTICAL;
        addView(pusherText, textParams);
        var transition = new LayoutTransition();
        transition.enableTransitionType(LayoutTransition.CHANGING);
        setLayoutTransition(transition);
        clear();
    }

    public PlayerHeadView getPusherHeadView() { return pusherHeadView; }
    public TextView getPusherText() { return pusherText; }

    public void clear() {
        binding++;
        pusherHeadView.setPlayerSkinSupplier(null);
        pusherHeadView.setVisibility(GONE);
        pusherText.setText("");
        setVisibility(GONE);
    }

    public void bind(@Nullable PusherInfo info) {
        clear();
        if (info == null || PusherInfo.EMPTY.equals(info) || info.getPlayerName() == null
                || info.getPlayerName().isBlank()) return;
        long ticket = binding;
        pusherText.setText(info.getPlayerName());
        setVisibility(VISIBLE);
        pusherHeadView.setPlayerSkinSupplier(() -> {
            if (ticket != binding) return null;
            Identifier skin = null;
            try { skin = PlayerInfoUtil.getPlayerSkin(PlayerInfoUtil.getPlayerInfoByUUID(info.getPlayerUUID())); }
            catch (Exception ignored) { }
            if (ticket != binding) return null;
            pusherHeadView.setVisibility(skin == null ? GONE : VISIBLE);
            return skin;
        });
    }
}
