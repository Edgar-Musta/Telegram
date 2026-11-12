package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.TeleClipsMedia;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;

/** Publishes globally searchable clips using a regular user's public Telegram channel. */
public class TeleClipsPublishActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private static final String CHANNEL_PREF = "teleclips_creator_channel";
    private static final int PICK_VIDEO = 6101;
    private static final int GALLERY_PERMISSION = 6102;
    private final Utilities.Callback<TLRPC.Message> onPublished;
    private final ArrayList<TLRPC.Chat> channels = new ArrayList<>();
    private FrameLayout rootView;
    private TextView channelText;
    private TextView channelSubtitle;
    private BackupImageView channelAvatar;
    private FrameLayout channelRow;
    private TextView videoText;
    private TextView videoPlaceholderText;
    private FrameLayout videoTile;
    private BackupImageView videoPreview;
    private LinearLayout videoPlaceholder;
    private ImageView changeVideo;
    private RadialProgressView videoProgress;
    private Bitmap selectedPreview;
    private SendMessagesHelper.SendingMediaInfo renderedVideo;
    private EditTextBoldCursor captionField;
    private ButtonWithCounterView publishButton;
    private TLRPC.Chat selectedChannel;
    private SendMessagesHelper.SendingMediaInfo selectedVideo;
    private MessageObject outgoingMessage;
    private int outgoingId;
    private boolean loadingChannels;
    private boolean readingVideo;
    private boolean publishing;
    private int videoGeneration;
    private String publishingPath;

    public TeleClipsPublishActivity(int account, Utilities.Callback<TLRPC.Message> onPublished) {
        super();
        setCurrentAccount(account);
        this.onPublished = onPublished;
    }

    public static boolean canPublishTo(TLRPC.Chat chat) {
        return chat != null && ChatObject.isChannelAndNotMegaGroup(chat) && ChatObject.isPublic(chat) && ChatObject.isInChat(chat) && ChatObject.canUserDoAction(chat, ChatObject.ACTION_POST);
    }

    @Override
    public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) return false;
        final NotificationCenter center = NotificationCenter.getInstance(currentAccount);
        center.addObserver(this, NotificationCenter.didReceiveNewMessages);
        center.addObserver(this, NotificationCenter.messageReceivedByServer);
        center.addObserver(this, NotificationCenter.messageSendError);
        center.addObserver(this, NotificationCenter.fileUploadProgressChanged);
        collectChannels();
        final long saved = MessagesController.getMainSettings(currentAccount).getLong(CHANNEL_PREF, 0);
        final TLRPC.Chat chat = getMessagesController().getChat(saved);
        if (canPublishTo(chat)) selectedChannel = chat;
        loadPublicChannels();
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        actionBar.setTitle(getString(R.string.TeleClipsNewClip));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        rootView = new FrameLayout(context);
        fragmentView = rootView;
        rootView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        final ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));

        videoTile = new FrameLayout(context);
        videoTile.setBackground(Theme.createRoundRectDrawable(dp(20), Theme.getColor(Theme.key_windowBackgroundWhite)));
        roundClip(videoTile, 20);
        videoTile.setOnClickListener(v -> openVideoPicker());
        content.addView(videoTile, LayoutHelper.createLinear(156, 248, Gravity.CENTER_HORIZONTAL));
        videoPreview = new BackupImageView(context);
        videoPreview.getImageReceiver().setAspectFit(false);
        videoPreview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        videoTile.addView(videoPreview, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        videoPlaceholder = new LinearLayout(context);
        videoPlaceholder.setOrientation(LinearLayout.VERTICAL);
        videoPlaceholder.setGravity(Gravity.CENTER_HORIZONTAL);
        final FrameLayout iconContainer = new FrameLayout(context);
        iconContainer.setBackground(Theme.createRoundRectDrawable(dp(20), Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton), .10f)));
        final ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.msg_teleclips);
        icon.setColorFilter(Theme.getColor(Theme.key_featuredStickers_addButton));
        iconContainer.addView(icon, LayoutHelper.createFrame(36, 36, Gravity.CENTER));
        videoPlaceholder.addView(iconContainer, LayoutHelper.createLinear(64, 64, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));
        videoPlaceholderText = text(context, 14, Theme.key_windowBackgroundWhiteBlackText, true);
        videoPlaceholderText.setGravity(Gravity.CENTER);
        videoPlaceholder.addView(videoPlaceholderText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        videoTile.addView(videoPlaceholder, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 10, 0, 10, 0));

        videoProgress = new RadialProgressView(context, resourceProvider);
        videoTile.addView(videoProgress, LayoutHelper.createFrame(32, 32, Gravity.CENTER));
        videoText = text(context, 11, Theme.key_windowBackgroundWhiteBlackText, false);
        videoText.setTextColor(0xffffffff);
        videoText.setGravity(Gravity.CENTER);
        videoText.setSingleLine(true);
        videoText.setEllipsize(TextUtils.TruncateAt.END);
        videoText.setPadding(dp(8), dp(5), dp(8), dp(5));
        videoText.setBackground(Theme.createRoundRectDrawable(dp(9), 0x99000000));
        videoTile.addView(videoText, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 8, 0, 8, 10));
        changeVideo = new ImageView(context);
        changeVideo.setImageResource(R.drawable.msg_gallery);
        changeVideo.setColorFilter(0xffffffff);
        changeVideo.setScaleType(ImageView.ScaleType.CENTER);
        changeVideo.setBackground(Theme.createRoundRectDrawable(dp(12), 0x99000000));
        changeVideo.setContentDescription(getString(R.string.TeleClipsChangeVideo));
        changeVideo.setOnClickListener(v -> openVideoPicker());
        videoTile.addView(changeVideo, LayoutHelper.createFrameRelatively(34, 34, Gravity.TOP | Gravity.END, 0, 8, 8, 0));
        final TextView limits = text(context, 12, Theme.key_windowBackgroundWhiteGrayText2, false);
        limits.setText(getString(R.string.TeleClipsCompactLimits));
        limits.setGravity(Gravity.CENTER);
        content.addView(limits, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 18));

        channelRow = new FrameLayout(context);
        channelRow.setBackground(Theme.createRoundRectDrawable(dp(16), Theme.getColor(Theme.key_windowBackgroundWhite)));
        roundClip(channelRow, 16);
        channelRow.setForeground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector)));
        channelRow.setOnClickListener(v -> chooseChannel());
        content.addView(channelRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 68, 0, 0, 0, 14));
        channelAvatar = new BackupImageView(context);
        channelAvatar.setRoundRadius(dp(20));
        channelRow.addView(channelAvatar, LayoutHelper.createFrameRelatively(40, 40, Gravity.START | Gravity.CENTER_VERTICAL, 14, 0, 0, 0));
        final LinearLayout channelTitles = new LinearLayout(context);
        channelTitles.setOrientation(LinearLayout.VERTICAL);
        channelText = text(context, 15, Theme.key_windowBackgroundWhiteBlackText, true);
        channelText.setSingleLine(true);
        channelText.setEllipsize(TextUtils.TruncateAt.END);
        channelTitles.addView(channelText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        channelSubtitle = text(context, 12, Theme.key_windowBackgroundWhiteGrayText2, false);
        channelSubtitle.setSingleLine(true);
        channelSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        channelTitles.addView(channelSubtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        channelRow.addView(channelTitles, LayoutHelper.createFrameRelatively(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 66, 0, 40, 0));
        final TextView arrow = text(context, 28, Theme.key_windowBackgroundWhiteGrayText2, false);
        arrow.setText(LocaleController.isRTL ? "‹" : "›");
        arrow.setGravity(Gravity.CENTER);
        channelRow.addView(arrow, LayoutHelper.createFrameRelatively(32, 40, Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

        captionField = new EditTextBoldCursor(context);
        captionField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        captionField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        captionField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        captionField.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated));
        captionField.setHint(getString(R.string.TeleClipsCaptionHint));
        captionField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        captionField.setMinLines(2);
        captionField.setMaxLines(4);
        captionField.setGravity(Gravity.TOP | Gravity.START);
        captionField.setPadding(dp(14), dp(12), dp(14), dp(12));
        captionField.setBackground(Theme.createRoundRectDrawable(dp(16), Theme.getColor(Theme.key_windowBackgroundWhite)));
        captionField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Math.max(0, getMessagesController().getCaptionMaxLengthLimit() - TeleClipsMedia.HASHTAG.length() - 1))});
        content.addView(captionField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final TextView tag = text(context, 12, Theme.key_featuredStickers_addButton, false);
        tag.setText(TeleClipsMedia.HASHTAG);
        tag.setPadding(dp(10), dp(5), dp(10), dp(5));
        tag.setBackground(Theme.createRoundRectDrawable(dp(9), Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton), .08f)));
        tag.setOnClickListener(v -> hint(getString(R.string.TeleClipsHashtagInfo)));
        content.addView(tag, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        final LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(16), dp(10), dp(16), dp(16));
        footer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        publishButton = new ButtonWithCounterView(context, resourceProvider).setRound();
        publishButton.setText(getString(R.string.TeleClipsPublish));
        publishButton.setOnClickListener(v -> publish());
        footer.addView(publishButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        scroll.addView(content, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        rootView.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL, 0, 0, 0, 74));
        rootView.addView(footer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 74, Gravity.BOTTOM));
        renderedVideo = null;
        updateForm();
        return rootView;
    }

    private TextView text(Context context, int size, int color, boolean bold) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(Theme.getColor(color));
        if (bold) view.setTypeface(AndroidUtilities.bold());
        return view;
    }

    private void roundClip(View view, int radius) {
        view.setClipToOutline(true);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View target, Outline outline) {
                outline.setRoundRect(0, 0, target.getWidth(), target.getHeight(), dp(radius));
            }
        });
    }

    private void collectChannels() {
        final HashSet<Long> ids = new HashSet<>();
        for (TLRPC.Chat channel : channels) ids.add(channel.id);
        for (int folder = 0; folder <= 1; folder++) {
            for (TLRPC.Dialog dialog : getMessagesController().getDialogs(folder)) {
                if (dialog.id >= 0) continue;
                final TLRPC.Chat channel = getMessagesController().getChat(-dialog.id);
                if (canPublishTo(channel) && ids.add(channel.id)) channels.add(channel);
            }
        }
    }

    private void loadPublicChannels() {
        if (loadingChannels) return;
        loadingChannels = true;
        final TLRPC.TL_channels_getAdminedPublicChannels req = new TLRPC.TL_channels_getAdminedPublicChannels();
        final int id = getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (isFinished) return;
            loadingChannels = false;
            if (response instanceof TLRPC.messages_Chats) {
                final TLRPC.messages_Chats result = (TLRPC.messages_Chats) response;
                getMessagesController().putChats(result.chats, false);
                final HashSet<Long> ids = new HashSet<>();
                for (TLRPC.Chat channel : channels) ids.add(channel.id);
                for (TLRPC.Chat channel : result.chats) {
                    if (canPublishTo(channel) && ids.add(channel.id)) channels.add(channel);
                }
            }
            updateForm();
        }));
        getConnectionsManager().bindRequestToGuid(id, classGuid);
    }

    private void chooseChannel() {
        collectChannels();
        final ArrayList<TLRPC.Chat> available = new ArrayList<>();
        for (TLRPC.Chat channel : channels) if (canPublishTo(channel)) available.add(channel);
        final CharSequence[] names = new CharSequence[available.size() + 1];
        for (int i = 0; i < available.size(); i++) names[i] = available.get(i).title + "\n@" + ChatObject.getPublicUsername(available.get(i));
        names[names.length - 1] = getString(R.string.TeleClipsCreateChannel);
        showDialog(new AlertDialog.Builder(getParentActivity(), resourceProvider)
            .setTitle(getString(R.string.TeleClipsChooseChannel))
            .setItems(names, (dialog, which) -> {
                if (which == available.size()) createChannel();
                else setChannel(available.get(which));
            }).create());
    }

    private void createChannel() {
        final Bundle args = new Bundle();
        args.putInt("step", 0);
        args.putBoolean("forcePublic", true);
        final ChannelCreateActivity creator = new ChannelCreateActivity(args);
        creator.setCurrentAccount(currentAccount);
        creator.setInitialName(UserObject.getUserName(getUserConfig().getCurrentUser()));
        creator.setOnFinishListener((fragment, chatId) -> {
            final TLRPC.Chat channel = getMessagesController().getChat(chatId);
            if (!canPublishTo(channel)) {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, getString(R.string.TeleClipsPublicChannelRequired)).show();
                return;
            }
            setChannel(channel);
            fragment.finishFragment();
        });
        presentFragment(creator);
    }

    private void setChannel(TLRPC.Chat channel) {
        selectedChannel = channel;
        MessagesController.getMainSettings(currentAccount).edit().putLong(CHANNEL_PREF, channel.id).apply();
        if (!channels.contains(channel)) channels.add(channel);
        updateForm();
    }

    private void openVideoPicker() {
        if (publishing || getParentActivity() == null) return;
        final String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (getParentActivity().checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            getParentActivity().requestPermissions(new String[]{permission}, GALLERY_PERMISSION);
            return;
        }
        final PhotoAlbumPickerActivity picker = new PhotoAlbumPickerActivity(PhotoAlbumPickerActivity.SELECT_TYPE_ALL, false, true, null);
        picker.setCurrentAccount(currentAccount);
        picker.setVideosOnly(true);
        picker.setMaxSelectedPhotos(1, false);
        picker.setDelegate(new PhotoAlbumPickerActivity.PhotoAlbumPickerActivityDelegate() {
            @Override
            public void didSelectPhotos(ArrayList<SendMessagesHelper.SendingMediaInfo> media, boolean notify, int scheduleDate) {
                if (!media.isEmpty()) readVideo(media.get(0));
            }

            @Override
            public void startPhotoSelectActivity() {
                final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("video/*");
                intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
                startActivityForResult(intent, PICK_VIDEO);
            }
        });
        presentFragment(picker);
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == GALLERY_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) openVideoPicker();
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_VIDEO && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            final SendMessagesHelper.SendingMediaInfo media = new SendMessagesHelper.SendingMediaInfo();
            media.uri = uri;
            media.isVideo = true;
            readVideo(media);
        }
    }

    private void readVideo(SendMessagesHelper.SendingMediaInfo media) {
        if (!media.isVideo || publishing) {
            hint(getString(R.string.TeleClipsVideoLimits));
            return;
        }
        final int generation = ++videoGeneration;
        readingVideo = true;
        selectedVideo = null;
        selectedPreview = null;
        updateForm();
        Utilities.globalQueue.postRunnable(() -> {
            boolean valid = false;
            Bitmap preview = null;
            final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                if (media.path == null && media.uri != null) media.path = MediaController.copyFileToCache(media.uri, "mp4");
                if (media.path == null || !new File(media.path).exists()) throw new IllegalArgumentException("Missing clip file");
                retriever.setDataSource(media.path);
                final long durationMs = Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                final int width = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                final int height = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                final String rotationText = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                final int rotation = rotationText == null ? 0 : Integer.parseInt(rotationText);
                final VideoEditedInfo edited = media.videoEditedInfo != null ? media.videoEditedInfo : new VideoEditedInfo();
                final int outputRotation = ((media.videoEditedInfo != null ? edited.rotationValue : rotation) % 360 + 360) % 360;
                final int resultWidth = edited.resultWidth > 0 ? edited.resultWidth : width;
                final int resultHeight = edited.resultHeight > 0 ? edited.resultHeight : height;
                final int outputWidth = edited.cropState != null && edited.cropState.transformWidth > 0 ? edited.cropState.transformWidth : resultWidth;
                final int outputHeight = edited.cropState != null && edited.cropState.transformHeight > 0 ? edited.cropState.transformHeight : resultHeight;
                final int displayWidth = outputRotation == 90 || outputRotation == 270 ? outputHeight : outputWidth;
                final int displayHeight = outputRotation == 90 || outputRotation == 270 ? outputWidth : outputHeight;
                final long startUs = Math.max(0, edited.startTime);
                final long endUs = edited.endTime > 0 ? Math.min(edited.endTime, durationMs * 1000) : durationMs * 1000;
                final long selectedMs = (endUs - startUs + 999) / 1000;
                if (displayHeight <= displayWidth || selectedMs <= 0 || selectedMs > TeleClipsMedia.MAX_SECONDS * 1000L) throw new IllegalArgumentException("Not a short portrait clip");
                edited.originalPath = media.path;
                edited.originalWidth = width;
                edited.originalHeight = height;
                edited.originalDuration = durationMs * 1000;
                edited.rotationValue = outputRotation;
                edited.startTime = startUs;
                edited.endTime = endUs;
                edited.estimatedDuration = selectedMs;
                final float scale = Math.min(1f, TeleClipsMedia.UPLOAD_MAX_SIDE / (float) Math.max(Math.max(resultWidth, resultHeight), Math.max(outputWidth, outputHeight)));
                edited.resultWidth = Math.max(2, Math.round(resultWidth * scale / 2) * 2);
                edited.resultHeight = Math.max(2, Math.round(resultHeight * scale / 2) * 2);
                if (edited.cropState != null) {
                    edited.cropState.transformWidth = Math.max(2, Math.round(outputWidth * scale / 2) * 2);
                    edited.cropState.transformHeight = Math.max(2, Math.round(outputHeight * scale / 2) * 2);
                }
                // Native "muted" messages are GIFs. Keep a silent TeleClip a regular video post.
                if (edited.muted) {
                    edited.muted = false;
                    edited.volume = 0f;
                }
                edited.bitrate = TeleClipsMedia.UPLOAD_BITRATE;
                edited.framerate = Math.max(1, Math.min(30, edited.framerate));
                edited.roundVideo = false;
                edited.estimatedSize = (long) (selectedMs / 1000d * (edited.bitrate + 128_000) / 8) + 256 * 1024;
                media.videoEditedInfo = edited;
                media.ttl = 0;
                valid = edited.estimatedSize <= TeleClipsMedia.MAX_BYTES;
                if (valid && edited.thumb == null && TextUtils.isEmpty(media.thumbPath)) {
                    try {
                        if (Build.VERSION.SDK_INT >= 27) {
                            preview = retriever.getScaledFrameAtTime(startUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 360);
                        } else {
                            final Bitmap frame = retriever.getFrameAtTime(startUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                            if (frame != null) {
                                final float thumbScale = Math.min(1f, 360f / Math.max(frame.getWidth(), frame.getHeight()));
                                preview = Bitmap.createScaledBitmap(frame, Math.max(1, (int) (frame.getWidth() * thumbScale)), Math.max(1, (int) (frame.getHeight() * thumbScale)), true);
                                if (preview != frame) frame.recycle();
                            }
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                try { retriever.release(); } catch (Exception ignored) {}
            }
            final boolean accepted = valid;
            final Bitmap poster = preview;
            AndroidUtilities.runOnUIThread(() -> {
                if (isFinished || generation != videoGeneration) {
                    if (poster != null) poster.recycle();
                    return;
                }
                readingVideo = false;
                if (accepted) {
                    selectedVideo = media;
                    selectedPreview = poster;
                    if (captionField != null && !TextUtils.isEmpty(media.caption)) captionField.setText(media.caption);
                } else {
                    hint(getString(R.string.TeleClipsVideoLimits));
                }
                updateForm();
            });
        });
    }

    private void updateForm() {
        if (rootView == null || isFinished) return;
        if (selectedChannel != null && !canPublishTo(getMessagesController().getChat(selectedChannel.id))) selectedChannel = null;
        channelText.setText(selectedChannel == null ? getString(R.string.TeleClipsChooseChannel) : selectedChannel.title);
        channelSubtitle.setText(selectedChannel == null ? getString(R.string.TeleClipsPublicChannel) : "@" + ChatObject.getPublicUsername(selectedChannel));
        final AvatarDrawable avatar = new AvatarDrawable();
        if (selectedChannel != null) {
            avatar.setInfo(selectedChannel);
            channelAvatar.setForUserOrChat(selectedChannel, avatar);
        } else {
            avatar.setInfo(getUserConfig().getCurrentUser());
            channelAvatar.setForUserOrChat(getUserConfig().getCurrentUser(), avatar);
        }
        channelRow.setEnabled(!publishing);
        channelRow.setAlpha(publishing ? .6f : 1f);
        videoTile.setEnabled(!publishing && !readingVideo);
        videoTile.setContentDescription(getString(selectedVideo == null ? R.string.TeleClipsSelectVideo : R.string.TeleClipsChangeVideo));
        changeVideo.setEnabled(!publishing && !readingVideo);
        changeVideo.setVisibility(selectedVideo != null ? View.VISIBLE : View.GONE);
        videoProgress.setVisibility(readingVideo ? View.VISIBLE : View.GONE);
        final boolean hasPreview = selectedVideo != null && (selectedPreview != null || selectedVideo.videoEditedInfo.thumb != null || !TextUtils.isEmpty(selectedVideo.thumbPath));
        if (renderedVideo != selectedVideo) {
            videoPreview.clearImage();
            if (selectedVideo != null) {
                if (selectedPreview != null) videoPreview.setImageBitmap(selectedPreview);
                else if (selectedVideo.videoEditedInfo.thumb != null) videoPreview.setImageBitmap(selectedVideo.videoEditedInfo.thumb);
                else if (!TextUtils.isEmpty(selectedVideo.thumbPath)) videoPreview.setImage(ImageLocation.getForPath(selectedVideo.thumbPath), "240_360", (android.graphics.drawable.Drawable) null, selectedVideo);
            }
            renderedVideo = selectedVideo;
        }
        videoPreview.setVisibility(hasPreview ? View.VISIBLE : View.GONE);
        videoPlaceholder.setVisibility(!readingVideo && !hasPreview ? View.VISIBLE : View.GONE);
        videoPlaceholderText.setText(getString(selectedVideo == null ? R.string.TeleClipsSelectVideo : R.string.TeleClipsVideo));
        videoText.setVisibility(selectedVideo == null ? View.GONE : View.VISIBLE);
        if (selectedVideo != null) videoText.setText(LocaleController.formatString(R.string.TeleClipsSelectedVideo, (int) Math.ceil(selectedVideo.videoEditedInfo.estimatedDuration / 1000d), AndroidUtilities.formatFileSize(selectedVideo.videoEditedInfo.estimatedSize)));
        captionField.setEnabled(!publishing);
        publishButton.setEnabled(!publishing && !readingVideo && selectedVideo != null && selectedChannel != null);
        publishButton.setLoading(publishing);
        publishButton.setText(getString(publishing ? R.string.TeleClipsPublishing : R.string.TeleClipsPublish), true);
    }

    private void publish() {
        if (publishing || selectedVideo == null || !canPublishTo(selectedChannel)) return;
        publishing = true;
        outgoingMessage = null;
        outgoingId = 0;
        publishingPath = selectedVideo.path;
        updateForm();
        final CharSequence caption = TeleClipsMedia.publishingCaption(captionField.getText().toString());
        SendMessagesHelper.prepareSendingVideo(getAccountInstance(), selectedVideo.path, selectedVideo.videoEditedInfo, selectedVideo.coverPath, null, -selectedChannel.id, null, null, null, null, null, 0, null, true, 0, 0, false, false, caption, null, 0, 0);
    }

    private void hint(CharSequence text) {
        if (rootView != null && !isFinished) BulletinFactory.of(this).createSimpleBulletin(R.raw.info, text).show();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (!publishing || isFinished || selectedChannel == null) return;
        if (id == NotificationCenter.didReceiveNewMessages && (Long) args[0] == -selectedChannel.id) {
            for (MessageObject message : (ArrayList<MessageObject>) args[1]) {
                if (message.getId() < 0 && (message.videoEditedInfo != null && TextUtils.equals(message.videoEditedInfo.originalPath, publishingPath) || TextUtils.equals(message.messageOwner.attachPath, publishingPath))) {
                    outgoingMessage = message;
                    outgoingId = message.getId();
                }
            }
        } else if (id == NotificationCenter.messageReceivedByServer && outgoingMessage != null && (Integer) args[0] == outgoingId) {
            final TLRPC.Message message = args[2] instanceof TLRPC.Message ? (TLRPC.Message) args[2] : outgoingMessage.messageOwner;
            message.id = (Integer) args[1];
            publishing = false;
            if (onPublished != null) onPublished.run(message);
            finishFragment();
        } else if (id == NotificationCenter.messageSendError && outgoingMessage != null && (Integer) args[0] == outgoingId) {
            publishing = false;
            hint(getString(R.string.TeleClipsPublishError));
            updateForm();
        } else if (id == NotificationCenter.fileUploadProgressChanged && rootView != null && (TextUtils.equals((String) args[0], publishingPath) || outgoingMessage != null && TextUtils.equals((String) args[0], outgoingMessage.messageOwner.attachPath))) {
            final long total = (Long) args[2];
            if (total > 0) publishButton.setText(LocaleController.formatString(R.string.TeleClipsUploading, (int) Math.min(100, (Long) args[1] * 100 / total)), true);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
        collectChannels();
        updateForm();
    }

    @Override
    public void onFragmentDestroy() {
        AndroidUtilities.removeAdjustResize(getParentActivity(), classGuid);
        videoGeneration++;
        final NotificationCenter center = NotificationCenter.getInstance(currentAccount);
        center.removeObserver(this, NotificationCenter.didReceiveNewMessages);
        center.removeObserver(this, NotificationCenter.messageReceivedByServer);
        center.removeObserver(this, NotificationCenter.messageSendError);
        center.removeObserver(this, NotificationCenter.fileUploadProgressChanged);
        super.onFragmentDestroy();
    }
}
