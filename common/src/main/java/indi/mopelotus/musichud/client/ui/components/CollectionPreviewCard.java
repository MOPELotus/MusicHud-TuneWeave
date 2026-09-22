package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.core.Context;
import icyllis.modernui.text.SpannableString;
import icyllis.modernui.text.Spanned;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory;

import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/** Upstream collection-card presentation for collections without platform subscription semantics. */
public final class CollectionPreviewCard extends LinearLayout {
    private final LinearLayout actions;

    public CollectionPreviewCard(Context context, String cover, String name, String description, String count) {
        super(context);
        setOrientation(VERTICAL);
        setLayoutParams(new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        UrlImageView image = new UrlImageView(context);
        image.setAspectRatio(1);
        image.setCornerRadius(dp(8));
        image.loadUrl(cover == null || cover.isBlank() ? MusicHud.ICON_BASE64 : cover);
        LayoutParams imageParams = new LayoutParams(dp(160), dp(160));
        imageParams.setMargins(0, 0, 0, dp(4));
        addView(image, imageParams);
        FlexWrapLayout row = new FlexWrapLayout(context);
        row.setAnimationsEnabled(false);
        row.setLineGravity(Gravity.TOP);
        addView(row, new LayoutParams(dp(160), WRAP_CONTENT));
        TextView metadata = new TextView(context);
        metadata.setTextSize(Theme.TEXT_SIZE_NORMAL);
        metadata.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        SpannableString label = new SpannableString("  " + count);
        var icon = ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/list_music.png");
        if (icon != null) label.setSpan(ImageUtils.getIconSpan(icon), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        metadata.setText(label);
        row.addView(metadata, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        actions = new LinearLayout(context);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(actions, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        TextView title = new TextView(context);
        title.setText(name);
        title.setTextSize(Theme.TEXT_SIZE_NORMAL);
        title.setTextColor(Theme.NORMAL_TEXT_COLOR);
        title.setMinLines(2);
        title.setMaxLines(4);
        title.setMaxWidth(dp(156));
        LayoutParams titleParams = new LayoutParams(dp(156), WRAP_CONTENT);
        titleParams.setMargins(dp(2), 0, dp(2), 0);
        addView(title, titleParams);
        setTooltipText(description == null || description.isBlank() ? name : name + "\n" + description);
        InsetBackgroundFactory.builder().cornerRadius(dp(8)).inset(dp(1))
                .padding(new InsetBackgroundFactory.Padding(dp(6), dp(6), dp(6), dp(6)))
                .build().applyBackgroundTo(this);
    }

    public LinearLayout getActions() { return actions; }
}
