package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Image;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.text.SpannableString;
import icyllis.modernui.text.Spanned;
import icyllis.modernui.text.style.ImageSpan;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.TextView;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.*;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.components.ModifyPlaylistTrackModalButton;
import indi.mopelotus.musichud.client.ui.components.ToggleTrackLikeStateButton;
import indi.mopelotus.musichud.client.ui.components.FlexWrapLayout;
import indi.mopelotus.musichud.client.ui.components.ArtistDetailView;
import indi.mopelotus.musichud.client.ui.components.MusicCollectionDetailView;
import indi.mopelotus.musichud.client.utils.PlayerInfoUtil;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import indi.mopelotus.musichud.client.utils.ui.InsetBackgroundFactory;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.language.I18n;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

import static icyllis.modernui.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static icyllis.modernui.view.ViewGroup.LayoutParams.WRAP_CONTENT;

public class MusicListItem extends LinearLayout {
    public static final int imageSize = 56;
    private static final MusicService musicService = MusicService.getInstance();
    private static final DateTimeFormatter PLAY_RECORD_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final DateTimeFormatter timeFormatterWithHour = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("mm:ss");
    @Getter
    private UrlImageView albumImageView;
    private TextView musicName;
    private boolean platformActionsEnabled = true;

    public void setPlatformActionsEnabled(boolean enabled) {
        platformActionsEnabled = enabled;
        if (musicDetail != null) bindData(musicDetail);
    }
    private TextView feeLabel;
    private FlexWrapLayout row2;
    private TextView durationText;
    private TextView pusherText;
    @Setter
    @Getter
    private boolean showPusherInfo = true;
    @Getter
    private MusicDetail musicDetail;
    private PlayerHeadView pusherHeadView;
    private ToggleTrackLikeStateButton likeButton;
    @Getter
    private LinearLayout buttonsLayout;
    private ModifyPlaylistTrackModalButton addToPlaylistButton;
    private PlaybackSourceLink sourceButton;
    private TextView playRecordTimeText;
    private LinearLayout pusherInfo;
    @Getter
    private LinearLayout infoRow;

    public MusicListItem(Context context) {
        super(context);
        initView(context);
    }

    private void initView(Context context) {
        setOrientation(HORIZONTAL);
        LayoutParams musicLayoutParams = new LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        setLayoutParams(musicLayoutParams);
        setGravity(Gravity.CENTER_VERTICAL);

        albumImageView = new UrlImageView(context);
        albumImageView.setCornerRadius(dp(4));
        albumImageView.setAspectRatio(1);
        addView(albumImageView, new LayoutParams(dp(imageSize), dp(imageSize)));

        LinearLayout musicTexts = new LinearLayout(context);
        musicTexts.setOrientation(VERTICAL);
        musicTexts.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams textsParams = new LayoutParams(0, WRAP_CONTENT, 1);
        textsParams.setMargins(dp(12), 0, 0, 0);
        addView(musicTexts, textsParams);

        LinearLayout row1 = new LinearLayout(context);
        row1.setOrientation(HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        musicTexts.addView(row1);

        musicName = new TextView(context);
        musicName.setSingleLine(true);
        musicName.setTextSize(Theme.TEXT_SIZE_LARGE);
        musicName.setTextColor(Theme.NORMAL_TEXT_COLOR);
        row1.addView(musicName, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

        row2 = new FlexWrapLayout(context);
        row2.setAnimationsEnabled(false);
        musicTexts.addView(row2, new LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        infoRow = new LinearLayout(context);
        infoRow.setOrientation(HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        musicTexts.addView(infoRow);

        durationText = new TextView(context);
        durationText.setTextSize(Theme.TEXT_SIZE_NORMAL);
        durationText.setSingleLine(true);
        durationText.setTextColor(Theme.SECONDARY_TEXT_COLOR);

        LayoutParams params = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        params.setMargins(0, 0, dp(12), 0);
        infoRow.addView(durationText, params);

        feeLabel = new TextView(context);
        feeLabel.setSingleLine(true);
        feeLabel.setTextSize(Theme.TEXT_SIZE_NORMAL);
        feeLabel.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        feeLabel.setVisibility(GONE);
        LayoutParams params1 = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        params1.setMargins(0, 0, dp(12), 0);
        infoRow.addView(feeLabel, params1);

        pusherInfo = new LinearLayout(context);
        pusherInfo.setOrientation(LinearLayout.HORIZONTAL);
        pusherInfo.setGravity(Gravity.CENTER_VERTICAL);
        pusherInfo.setVisibility(GONE);
        LayoutParams params2 = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        params2.setMargins(0, 0, dp(12), 0);
        infoRow.addView(pusherInfo, params2);

        pusherText = new TextView(context);
        pusherText.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        pusherText.setTextSize(Theme.TEXT_SIZE_NORMAL);
        pusherText.setTextAlignment(TEXT_ALIGNMENT_TEXT_START);

        pusherHeadView = new PlayerHeadView(context);
        int rowHeight = pusherText.dp(Theme.TEXT_SIZE_LARGER);
        //noinspection SuspiciousNameCombination
        pusherHeadView.setLayoutParams(new LinearLayout.LayoutParams(rowHeight, rowHeight));
        pusherHeadView.setVisibility(View.GONE);

        pusherInfo.addView(pusherHeadView);
        LinearLayout.LayoutParams params5 = new LinearLayout.LayoutParams(WRAP_CONTENT, rowHeight);
        params5.gravity = Gravity.LEFT | Gravity.CENTER_HORIZONTAL;
        params5.setMargins(pusherText.dp(4), 0, 0, 0);
        pusherInfo.addView(pusherText, params5);

        sourceButton = new PlaybackSourceLink(context);
        infoRow.addView(sourceButton, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

        playRecordTimeText = new TextView(context);
        playRecordTimeText.setSingleLine(true);
        playRecordTimeText.setTextSize(Theme.TEXT_SIZE_NORMAL);
        playRecordTimeText.setTextColor(Theme.SECONDARY_TEXT_COLOR);
        playRecordTimeText.setVisibility(GONE);
        LayoutParams recordTimeParams = new LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        infoRow.addView(playRecordTimeText, recordTimeParams);

        buttonsLayout = new LinearLayout(context);
        buttonsLayout.setOrientation(HORIZONTAL);
        buttonsLayout.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams buttonsLayoutParams = new LayoutParams(WRAP_CONTENT, MATCH_PARENT, 0);
        buttonsLayoutParams.setMargins(0, 0, dp(10), 0);
        addView(buttonsLayout, buttonsLayoutParams);

        InsetBackgroundFactory backgroundFactory = InsetBackgroundFactory.builder()
                .inset(dp(2))
                .backgroundColor(Theme.GHOST_BUTTON_STATES)
                .cornerRadius(dp(4)).build();
        addToPlaylistButton = new ModifyPlaylistTrackModalButton(context);
        backgroundFactory.applyBackgroundTo(addToPlaylistButton);
        buttonsLayout.addView(addToPlaylistButton, new LinearLayout.LayoutParams(dp(40), dp(40), 0));

        likeButton = new ToggleTrackLikeStateButton(context);
        backgroundFactory.applyBackgroundTo(likeButton);
        buttonsLayout.addView(likeButton, new LinearLayout.LayoutParams(dp(40), dp(40), 0));
    }

    public void setRowAnimationsEnabled(boolean enabled) {
        row2.setAnimationsEnabled(enabled);
    }

    public void clearData() {
        albumImageView.clear();
        musicName.setText("");
        feeLabel.setText("");
        feeLabel.setVisibility(GONE);
        durationText.setText("");
        row2.removeAllViews();
        pusherText.setText("");
        pusherHeadView.setVisibility(View.GONE);
        pusherHeadView.setPlayerSkinSupplier(null);
        playRecordTimeText.setText("");
        playRecordTimeText.setVisibility(GONE);
        addToPlaylistButton.bindMusicDetail(null);
        likeButton.bindMusicList(null);
        setTag(null);
        musicDetail = null;
        sourceButton.bind(PlaybackSource.NONE);
        pusherInfo.setVisibility(GONE);
    }

    /**
     * Shows the play-record time at the end of the last row; pass {@code null} or
     * {@link Instant#MIN} to hide it.
     */
    public void setPlayRecordTime(Instant playTime) {
        if (playRecordTimeText == null) {
            return;
        }
        if (playTime == null || playTime.equals(Instant.MIN)) {
            playRecordTimeText.setText("");
            playRecordTimeText.setVisibility(GONE);
        } else {
            playRecordTimeText.setText(PLAY_RECORD_FORMATTER.format(playTime));
            playRecordTimeText.setVisibility(VISIBLE);
        }
    }

    public void bindData(MusicDetail musicDetail) {
        if (musicDetail == null || musicDetail == MusicDetail.NONE) {
            clearData();
            return;
        }
        setTag(musicDetail.getId());
        this.musicDetail = musicDetail;
        Album album = musicDetail.getAlbum();
        albumImageView.loadUrl(album.getThumbnailPicUrl(dp(imageSize)));

        musicName.setText(musicDetail.getName());
        Fee fee1 = musicDetail.getFee();
        if (fee1 == Fee.VIP || fee1 == Fee.SEPARATELY_PURCHASE) {
            feeLabel.setText(I18n.get(MusicHud.MOD_ID + ".text.fee." + fee1.name()));
            feeLabel.setVisibility(VISIBLE);
        } else {
            feeLabel.setVisibility(GONE);
        }

        row2.removeAllViews();
        int index = 0;
        Context context = getContext();
        InsetBackgroundFactory backgroundFactory = InsetBackgroundFactory.builder()
                .inset(0)
                .cornerRadius(dp(2))
                .padding(new InsetBackgroundFactory.Padding(0, 0, 0, 0))
                .build();
        List<Artist> artists = musicDetail.getArtists();
        for (final Artist artist : artists) {
            if (index != 0) {
                TextView split = new TextView(context);
                split.setTextColor(Theme.SECONDARY_TEXT_COLOR);
                split.setTextSize(Theme.TEXT_SIZE_SMALL);
                split.setText(" / ");
                split.setSingleLine();
                row2.addView(split);
            }
            index++;
            Button artistButton = new Button(context);
            backgroundFactory.applyBackgroundTo(artistButton);
            boolean validArtist = platformActionsEnabled && isPlatformTrack(musicDetail) && !artist.getSourceRef().isBlank();
            artistButton.setEnabled(validArtist);
            artistButton.setTextColor(validArtist ? Theme.PRIMARY_COLOR : Theme.SECONDARY_TEXT_COLOR);
            artistButton.setTextSize(Theme.TEXT_SIZE_NORMAL);
            artistButton.setTextAlignment(TEXT_ALIGNMENT_TEXT_START);
            artistButton.setText(artist.getName());
            artistButton.setSingleLine();
            artistButton.setOnClickListener(button -> {
                RouterContainer routerContainer = RouterContainer.getInstance();
                if (routerContainer != null) {
                    routerContainer.pushNavigate(
                            new ArtistDetailView(context, artist)
                    );
                }
            });
            row2.addView(artistButton);
        }
        String albumName = album.getName();
        albumName = albumName == null ? "" : albumName;
        if (!artists.isEmpty() && !artists.getFirst().getName().isBlank() && !albumName.isBlank()) {
            TextView split = new TextView(context);
            split.setTextColor(Theme.SECONDARY_TEXT_COLOR);
            split.setTextSize(Theme.TEXT_SIZE_SMALL);
            split.setText(" - ");
            split.setSingleLine();
            row2.addView(split);
        }
        if (!Album.NONE.equals(album)) {
            Button albumButton = new Button(context);
            backgroundFactory.applyBackgroundTo(albumButton);
            boolean validAlbum = platformActionsEnabled && isPlatformTrack(musicDetail) && !album.getSourceRef().isBlank();
            albumButton.setEnabled(validAlbum);
            albumButton.setTextColor(validAlbum ? Theme.PRIMARY_COLOR : Theme.SECONDARY_TEXT_COLOR);
            albumButton.setTextSize(Theme.TEXT_SIZE_NORMAL);
            albumButton.setText(albumName);
            albumButton.setSingleLine();
            albumButton.setTextAlignment(TEXT_ALIGNMENT_TEXT_START);
            albumButton.setOnClickListener(button -> {
                RouterContainer routerContainer = RouterContainer.getInstance();
                if (routerContainer != null) {
                    routerContainer.pushNavigate(
                            new MusicCollectionDetailView(context, album)
                    );
                }
            });
            row2.addView(albumButton);
        }

        Duration duration = Duration.of(musicDetail.getDurationMillis(), ChronoUnit.MILLIS);
        DateTimeFormatter formatter = duration.toHoursPart() >= 1 ?
                timeFormatterWithHour :
                timeFormatter;
        durationText.setText(formatter.format(
                LocalTime.MIDNIGHT.plusSeconds(duration.toSeconds())
        ));

        pusherInfo.setVisibility(GONE);
        pusherText.setText("");
        pusherHeadView.setVisibility(GONE);
        pusherHeadView.setPlayerSkinSupplier(null);
        if (showPusherInfo) {
            PusherInfo pusher = musicDetail.getPusherInfo();
            if (!pusher.getPlayerName().isBlank()) {
                pusherInfo.setVisibility(VISIBLE);
                pusherText.setText(pusher.getPlayerName());
                pusherHeadView.setVisibility(VISIBLE);
                pusherHeadView.setPlayerSkinSupplier(() -> {
                    try {
                        return PlayerInfoUtil.getPlayerSkin(PlayerInfoUtil.getPlayerInfoByUUID(pusher.getPlayerUUID()));
                    } catch (IllegalStateException ignored) {
                        return null;
                    }
                });
            }
        }
        sourceButton.bind(showPusherInfo ? musicDetail.getPlaybackSource() : PlaybackSource.NONE);
        boolean podcastOrRadio = !platformActionsEnabled || "podcast_episode".equals(musicDetail.getSourceKind())
                || "radio_station".equals(musicDetail.getSourceKind()) || "cloud_upload".equals(musicDetail.getSourceKind());
        boolean video = "video".equals(musicDetail.getSourceKind());
        addToPlaylistButton.setVisibility(podcastOrRadio ? GONE : VISIBLE);
        likeButton.setVisibility(video || podcastOrRadio ? GONE : VISIBLE);
        addToPlaylistButton.bindMusicDetail(platformActionsEnabled ? musicDetail : null);
        likeButton.bindMusicList(video || podcastOrRadio ? null
                : musicService.getMusicTrackState(musicDetail).currentUsersLikeList());
    }
    private static boolean isPlatformTrack(MusicDetail musicDetail) {
        String kind = musicDetail.getSourceKind();
        return !"cloud_upload".equals(kind) && !"video".equals(kind) && !"podcast_episode".equals(kind) && !"radio_station".equals(kind);
    }
}
