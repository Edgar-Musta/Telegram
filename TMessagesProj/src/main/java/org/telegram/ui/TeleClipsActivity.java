package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.EdgeToEdgeSupportMode;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.Components.TeleClipsFeed;
import org.telegram.ui.Components.TeleClipsMedia;
import org.telegram.ui.Components.TeleClipsPreloader;
import org.telegram.ui.Components.VideoPlayer;
import org.telegram.ui.Stars.StarsController;
import org.telegram.ui.Stars.StarsReactionsSheet;
import org.telegram.ui.Stories.DarkThemeResourceProvider;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;

public class TeleClipsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    public static final String PREF_KEY = "teleclips_enabled";
    private static final int[] OBSERVERS = {
        NotificationCenter.messageReceivedByServer,
        NotificationCenter.didUpdateReactions, NotificationCenter.chatInfoDidLoad,
        NotificationCenter.updateInterfaces, NotificationCenter.dialogsNeedReload
    };

    public static boolean isEnabled() {
        return MessagesController.getGlobalMainSettings().getBoolean(PREF_KEY, true);
    }

    public static void setEnabled(boolean enabled) {
        MessagesController.getGlobalMainSettings().edit().putBoolean(PREF_KEY, enabled).apply();
    }

    private final TeleClipsFeed[] feeds = new TeleClipsFeed[2];
    private final HashSet<Long> joiningChannels = new HashSet<>();
    private final HashSet<Long> justJoinedChannels = new HashSet<>();
    private int selectedFeed = TeleClipsFeed.EXPLORE;
    private int adapterSize;
    private int adapterRevision;
    private TeleClipsPreloader preloader;
    private FrameLayout rootView;
    private RecyclerView listView;
    private LinearLayoutManager layoutManager;
    private PagerSnapHelper snapHelper;
    private ClipsAdapter adapter;
    private FrameLayout topBar;
    private FeedTabs tabsView;
    private FrameLayout emptyPanel;
    private RadialProgressView emptyProgress;
    private TextView emptyTitle;
    private TextView emptySubtitle;
    private ButtonWithCounterView emptyButton;
    private TextView emptySecondary;
    private TextView moreButton;

    private TeleClipsFeed activeFeed;
    private ClipPageView activePage;
    private MessageObject activeMessage;
    private VideoPlayer videoPlayer;
    private int playerGeneration;
    private boolean streamingRequired;
    private boolean playbackError;
    private boolean fullyVisible;
    private boolean modalPaused;
    private boolean slidePaused;
    private boolean muted = true;
    private int discussionRequest;
    private MessageObject discussionMessage;
    private int insetLeft;
    private int insetRight;
    private int insetTop = AndroidUtilities.statusBarHeight;
    private int insetBottom = AndroidUtilities.navigationBarHeight;

    public TeleClipsActivity() {
        this(UserConfig.selectedAccount);
    }

    public TeleClipsActivity(int account) {
        super();
        setCurrentAccount(account);
        resourceProvider = new DarkThemeResourceProvider() {
            @Override
            public void appendColors() {
                sparseIntArray.put(Theme.key_featuredStickers_buttonText, 0xffffffff);
                sparseIntArray.put(Theme.key_actionBarTabActiveText, 0xffffffff);
                sparseIntArray.put(Theme.key_actionBarTabUnactiveText, 0x99ffffff);
                sparseIntArray.put(Theme.key_actionBarTabLine, 0xffffffff);
            }

            @Override
            public boolean isDark() {
                return true;
            }
        };
    }

    @Override
    public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) {
            return false;
        }
        for (int i = 0; i < feeds.length; i++) {
            feeds[i] = new TeleClipsFeed(currentAccount, classGuid, i, this::onFeedChanged);
        }
        preloader = new TeleClipsPreloader(currentAccount);
        for (int event : OBSERVERS) {
            NotificationCenter.getInstance(currentAccount).addObserver(this, event);
        }
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        // BaseFragment creates an ActionBar even when the screen supplies its own overlay.
        actionBar.setAddToContainer(false);
        actionBar.setVisibility(View.GONE);
        actionBar.setTitle(getString(R.string.TeleClips));

        rootView = new FrameLayout(context) {
            @Override
            public void onWindowFocusChanged(boolean hasWindowFocus) {
                super.onWindowFocusChanged(hasWindowFocus);
                if (hasWindowFocus) {
                    post(TeleClipsActivity.this::updatePlayback);
                } else {
                    pausePlayer();
                    preloader.clear();
                }
            }
        };
        fragmentView = rootView;
        rootView.setBackgroundColor(color(Theme.key_windowBackgroundGray));
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (view, windowInsets) -> {
            final Insets insets = AndroidUtilities.getDefaultWindowInsets(windowInsets, false);
            onInsets(insets.left, insets.top, insets.right, insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        listView = new RecyclerView(context);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, RecyclerView.VERTICAL, false));
        listView.setHasFixedSize(true);
        listView.setItemViewCacheSize(1);
        listView.setItemAnimator(null);
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ClipsAdapter());
        snapHelper = new PagerSnapHelper();
        snapHelper.attachToRecyclerView(listView);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    updatePlayback();
                } else {
                    pausePlayer();
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updateMoreButton();
            }
        });
        rootView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        createEmptyPanel(context);

        moreButton = textView(context, 13, Theme.key_windowBackgroundWhiteBlackText);
        moreButton.setGravity(Gravity.CENTER);
        moreButton.setPadding(dp(14), dp(8), dp(14), dp(8));
        moreButton.setBackground(Theme.createRoundRectDrawable(dp(18), Theme.multAlpha(color(Theme.key_windowBackgroundGray), .75f)));
        moreButton.setOnClickListener(v -> currentFeed().loadMore());
        rootView.addView(moreButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        createTopBar(context);

        adapterSize = currentFeed().getMessages().size();
        adapterRevision = currentFeed().getRevision();
        layoutManager.scrollToPositionWithOffset(currentFeed().selectedPosition, 0);
        onInsets(insetLeft, insetTop, insetRight, insetBottom);
        updateEmptyPanel();
        if (!currentFeed().isInitialized()) {
            currentFeed().loadMore();
        }
        ViewCompat.requestApplyInsets(rootView);
        return fragmentView;
    }

    private int color(int key) {
        return Theme.getColor(key, resourceProvider);
    }

    private TextView textView(Context context, int size, int colorKey) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        view.setTextColor(color(colorKey));
        return view;
    }

    private void createTopBar(Context context) {
        final View scrim = new View(context);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{
            Theme.multAlpha(color(Theme.key_windowBackgroundGray), .75f), 0
        }));
        rootView.addView(scrim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 160, Gravity.TOP));

        topBar = new FrameLayout(context);
        final ImageView back = new ImageView(context);
        back.setImageResource(R.drawable.ic_ab_back);
        back.setColorFilter(color(Theme.key_windowBackgroundWhiteBlackText));
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setContentDescription(getString(R.string.Back));
        back.setBackground(Theme.createSelectorDrawable(color(Theme.key_listSelector)));
        back.setOnClickListener(v -> finishFragment());
        topBar.addView(back, LayoutHelper.createFrameRelatively(48, 48, Gravity.START | Gravity.CENTER_VERTICAL));

        back.setLayoutParams(LayoutHelper.createFrameRelatively(44, 44, Gravity.START | Gravity.TOP));
        final LinearLayout brand = new LinearLayout(context);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        final ImageView logo = new ImageView(context);
        logo.setImageResource(R.drawable.msg_teleclips);
        logo.setColorFilter(color(Theme.key_windowBackgroundWhiteBlackText));
        brand.addView(logo, LayoutHelper.createLinear(24, 24));
        final TextView title = textView(context, 16, Theme.key_windowBackgroundWhiteBlackText);
        title.setText(getString(R.string.TeleClips));
        title.setTypeface(AndroidUtilities.bold());
        brand.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8, 0, 0, 0));
        topBar.addView(brand, LayoutHelper.createFrameRelatively(LayoutHelper.WRAP_CONTENT, 44, Gravity.START | Gravity.TOP, 48, 0, 0, 0));

        final TextView create = textView(context, 28, Theme.key_windowBackgroundWhiteBlackText);
        create.setText("+");
        create.setGravity(Gravity.CENTER);
        create.setContentDescription(getString(R.string.TeleClipsNewClip));
        create.setBackground(Theme.createRoundRectDrawable(dp(14), color(Theme.key_featuredStickers_addButton)));
        create.setOnClickListener(v -> openPublisher());
        topBar.addView(create, LayoutHelper.createFrameRelatively(40, 36, Gravity.END | Gravity.TOP, 0, 4, 48, 0));
        final ImageView menu = new ImageView(context);
        menu.setImageResource(R.drawable.ic_ab_other);
        menu.setColorFilter(color(Theme.key_windowBackgroundWhiteBlackText));
        menu.setScaleType(ImageView.ScaleType.CENTER);
        menu.setContentDescription(getString(R.string.AccDescrMoreOptions));
        menu.setBackground(Theme.createSelectorDrawable(color(Theme.key_listSelector)));
        menu.setOnClickListener(v -> ItemOptions.makeOptions(this, menu)
            .add(R.drawable.msg_reset, getString(R.string.TeleClipsRefresh), this::refreshFeed)
            .show());
        topBar.addView(menu, LayoutHelper.createFrameRelatively(44, 44, Gravity.END | Gravity.TOP));
        tabsView = new FeedTabs(context);
        topBar.addView(tabsView, LayoutHelper.createFrameRelatively(LayoutHelper.MATCH_PARENT, 44, Gravity.TOP, 56, 44, 56, 0));
        rootView.addView(topBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 96, Gravity.TOP));
    }

    private void createEmptyPanel(Context context) {
        emptyPanel = new FrameLayout(context);
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        final ImageView logo = new ImageView(context);
        logo.setImageResource(R.drawable.msg_teleclips);
        logo.setColorFilter(color(Theme.key_featuredStickers_addButton));
        content.addView(logo, LayoutHelper.createLinear(56, 56, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 24));
        emptyProgress = new RadialProgressView(context, resourceProvider);
        emptyProgress.setProgressColor(color(Theme.key_windowBackgroundWhiteBlackText));
        content.addView(emptyProgress, LayoutHelper.createLinear(36, 36, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));
        emptyTitle = textView(context, 18, Theme.key_windowBackgroundWhiteBlackText);
        emptyTitle.setTypeface(AndroidUtilities.bold());
        emptyTitle.setGravity(Gravity.CENTER);
        content.addView(emptyTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        emptySubtitle = textView(context, 14, Theme.key_windowBackgroundWhiteGrayText3);
        emptySubtitle.setGravity(Gravity.CENTER);
        content.addView(emptySubtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 16));
        emptyButton = new ButtonWithCounterView(context, resourceProvider).setRound();
        emptyButton.setPadding(dp(24), 0, dp(24), 0);
        emptyButton.setMinWidth(dp(188));
        emptyButton.setOnClickListener(v -> {
            if (currentFeed().getError() != null || !currentFeed().isEndReached()) {
                currentFeed().loadMore();
            } else {
                openPublisher();
            }
        });
        content.addView(emptyButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44, Gravity.CENTER_HORIZONTAL));
        emptySecondary = textView(context, 14, Theme.key_windowBackgroundWhiteGrayText3);
        emptySecondary.setGravity(Gravity.CENTER);
        emptySecondary.setPadding(dp(16), dp(12), dp(16), dp(12));
        emptySecondary.setOnClickListener(v -> {
            if (selectedFeed == TeleClipsFeed.FOLLOWING) selectFeed(TeleClipsFeed.EXPLORE);
            else refreshFeed();
        });
        content.addView(emptySecondary, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44, Gravity.CENTER_HORIZONTAL));
        emptyPanel.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 0, 32, 0));
        rootView.addView(emptyPanel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    private TeleClipsFeed currentFeed() {
        return feeds[selectedFeed];
    }

    private void openPublisher() {
        preloader.clear();
        presentFragment(new TeleClipsPublishActivity(currentAccount, message -> {
            if (isFinished || listView == null) return;
            selectFeed(TeleClipsFeed.EXPLORE);
            feeds[TeleClipsFeed.EXPLORE].addPublished(message);
            layoutManager.scrollToPositionWithOffset(0, 0);
            if (rootView != null) rootView.post(() -> showHint(getString(R.string.TeleClipsPublished)));
        }));
    }

    private void selectFeed(int index) {
        if (selectedFeed == index) {
            return;
        }
        listView.stopScroll();
        preloader.clear();
        savePlaybackPosition();
        releasePlayer();
        activePage = null;
        activeMessage = null;
        activeFeed = null;
        playbackError = false;
        selectedFeed = index;
        tabsView.updateSelection();
        adapterSize = currentFeed().getMessages().size();
        adapterRevision = currentFeed().getRevision();
        adapter.notifyDataSetChanged();
        layoutManager.scrollToPositionWithOffset(currentFeed().selectedPosition, 0);
        updateEmptyPanel();
        if (!currentFeed().isInitialized()) {
            currentFeed().loadMore();
        }
        rootView.post(this::updatePlayback);
    }

    private void refreshFeed() {
        preloader.clear();
        listView.stopScroll();
        savePlaybackPosition();
        releasePlayer();
        activePage = null;
        activeMessage = null;
        activeFeed = null;
        playbackError = false;
        currentFeed().refresh();
        layoutManager.scrollToPositionWithOffset(0, 0);
    }

    private void onFeedChanged() {
        if (rootView == null || adapter == null || isFinished) {
            return;
        }
        final int size = currentFeed().getMessages().size();
        if (size < adapterSize || adapterRevision != currentFeed().getRevision()) {
            adapter.notifyDataSetChanged();
        } else if (size > adapterSize) {
            adapter.notifyItemRangeInserted(adapterSize, size - adapterSize);
        }
        adapterSize = size;
        adapterRevision = currentFeed().getRevision();
        updateEmptyPanel();
        rootView.post(this::updatePlayback);
    }

    private void updateEmptyPanel() {
        if (emptyPanel == null) {
            return;
        }
        final TeleClipsFeed feed = currentFeed();
        emptyPanel.setVisibility(feed.getMessages().isEmpty() ? View.VISIBLE : View.GONE);
        emptyProgress.setVisibility(feed.isLoading() ? View.VISIBLE : View.GONE);
        emptySubtitle.setVisibility(feed.isLoading() ? View.GONE : View.VISIBLE);
        emptyButton.setVisibility(feed.isLoading() ? View.GONE : View.VISIBLE);
        emptySecondary.setVisibility(feed.isLoading() ? View.GONE : View.VISIBLE);
        emptySecondary.setText(getString(selectedFeed == TeleClipsFeed.FOLLOWING ? R.string.TeleClipsExplore : R.string.TeleClipsRefresh));
        if (feed.isLoading()) {
            emptyTitle.setText(getString(R.string.Loading));
        } else if (feed.getError() != null) {
            emptyTitle.setText(getString(R.string.TeleClipsLoadError));
            final String error = feed.getError().text;
            emptySubtitle.setText(getString("PREMIUM_ACCOUNT_REQUIRED".equals(error) ? R.string.TeleClipsSearchPremium : error != null && error.startsWith("FLOOD_WAIT") ? R.string.TeleClipsSearchLimit : R.string.TeleClipsRetryInfo));
            emptyButton.setText(getString(R.string.Retry));
        } else {
            emptyTitle.setText(getString(selectedFeed == TeleClipsFeed.EXPLORE ? R.string.TeleClipsGlobalEmptyTitle : R.string.TeleClipsNoVideos));
            emptySubtitle.setText(getString(selectedFeed == TeleClipsFeed.FOLLOWING ? R.string.TeleClipsFollowingEmpty : R.string.TeleClipsExploreEmpty));
            emptyButton.setText(getString(!feed.isEndReached() ? R.string.TeleClipsLoadMore : R.string.TeleClipsNewClip));
        }
        updateMoreButton();
    }

    private void updateMoreButton() {
        if (moreButton == null || listView == null) {
            return;
        }
        final TeleClipsFeed feed = currentFeed();
        final boolean atEnd = !feed.getMessages().isEmpty() && !listView.canScrollVertically(1);
        moreButton.setVisibility(atEnd && !feed.isLoading() && !feed.isEndReached() ? View.VISIBLE : View.GONE);
        moreButton.setText(getString(feed.getError() != null ? R.string.Retry : R.string.TeleClipsLoadMore));
    }

    private void maybeLoadMore() {
        final TeleClipsFeed feed = currentFeed();
        final int size = feed.getMessages().size();
        if (!feed.isLoading() && !feed.isEndReached() && feed.getError() == null && feed.prefetchedSize != size && feed.selectedPosition >= size - 3) {
            feed.loadMore();
        }
    }

    private boolean canWatch() {
        return fullyVisible && !isPaused && !isFinished && !modalPaused && !slidePaused && rootView != null && rootView.hasWindowFocus();
    }

    private void updatePlayback() {
        if (listView == null || !canWatch() || listView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            pausePlayer();
            return;
        }
        final View snapped = snapHelper.findSnapView(layoutManager);
        if (!(snapped instanceof ClipPageView)) {
            maybeLoadMore();
            return;
        }
        final ClipPageView page = (ClipPageView) snapped;
        final int position = listView.getChildAdapterPosition(page);
        // Adapter notifications are applied on the next layout. Never play an old tab's holder.
        if (page.message == null || position == RecyclerView.NO_POSITION || position >= currentFeed().getMessages().size() || currentFeed().getMessages().get(position) != page.message) {
            pausePlayer();
            return;
        }
        if (activePage != page || activeMessage != page.message || activeFeed != currentFeed()) {
            savePlaybackPosition();
            releasePlayer();
            activePage = page;
            activeMessage = page.message;
            activeFeed = currentFeed();
            if (activeFeed.selectedPosition != position) {
                activeFeed.playbackPosition = 0;
                activeFeed.userPaused = false;
            }
            activeFeed.selectedPosition = position;
            playbackError = false;
            loadChannelInfo(activeMessage);
            page.updateMetadata();
        }
        if (!activeFeed.userPaused && !playbackError && videoPlayer == null) {
            preparePlayer();
        }
        if (!activeFeed.userPaused && !playbackError && SharedConfig.streamMedia) preloader.setPosition(currentFeed().getMessages(), position);
        if (videoPlayer != null) {
            videoPlayer.setMute(muted);
            videoPlayer.setPlayWhenReady(!activeFeed.userPaused && !playbackError);
        }
        final boolean buffering = videoPlayer != null && videoPlayer.getPlaybackState() == ExoPlayer.STATE_BUFFERING;
        page.showPlaybackState(activeFeed.userPaused, buffering, playbackError);
        rootView.setKeepScreenOn(!activeFeed.userPaused && !playbackError);
        maybeLoadMore();
        updateMoreButton();
    }

    private File findVideoFile(MessageObject message, TLRPC.Document document) {
        if (document.id == message.getDocument().id && message.attachPathExists && !TextUtils.isEmpty(message.messageOwner.attachPath)) {
            final File file = new File(message.messageOwner.attachPath);
            if (file.exists()) return file;
        }
        final FileLoader loader = FileLoader.getInstance(currentAccount);
        File file = loader.getPathToAttach(document);
        if (file != null && file.exists()) return file;
        file = loader.getPathToAttach(document, true);
        return file != null && file.exists() ? file : null;
    }

    private void preparePlayer() {
        final MessageObject message = activeMessage;
        final ClipPageView page = activePage;
        final TLRPC.Document document = TeleClipsMedia.playbackDocument(message);
        if (document == null) {
            playbackError = true;
            return;
        }
        final File file = findVideoFile(message, document);
        streamingRequired = file == null && !SharedConfig.streamMedia;
        if (streamingRequired) {
            playbackError = true;
            return;
        }
        final int generation = ++playerGeneration;
        final VideoPlayer player = new VideoPlayer();
        videoPlayer = player;
        player.setPlayWhenReady(false);
        player.setTextureView(page.textureView);
        player.setLooping(true);
        player.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
            private boolean isCurrent() {
                return !isFinished && player == videoPlayer && generation == playerGeneration && page == activePage;
            }

            @Override
            public void onStateChanged(boolean playWhenReady, int playbackState) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (isCurrent()) {
                        page.bufferingView.setVisibility(playbackState == ExoPlayer.STATE_BUFFERING && !activeFeed.userPaused ? View.VISIBLE : View.GONE);
                    }
                });
            }

            @Override
            public void onError(VideoPlayer player, Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    if (isCurrent()) {
                        savePlaybackPosition();
                        releasePlayer();
                        playbackError = true;
                        page.showPlaybackState(false, false, true);
                    }
                });
            }

            @Override
            public void onVideoSizeChanged(int width, int height, int rotation, float pixelWidthHeightRatio) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (isCurrent() && width > 0 && height > 0) {
                        page.aspectLayout.setAspectRatio(width * pixelWidthHeightRatio / height, rotation);
                    }
                });
            }

            @Override
            public void onRenderedFirstFrame() {
                AndroidUtilities.runOnUIThread(() -> {
                    if (isCurrent()) {
                        page.thumbnail.setVisibility(View.GONE);
                        page.textureView.setAlpha(1f);
                        page.bufferingView.setVisibility(View.GONE);
                    }
                });
            }

            @Override
            public void onRenderedFirstFrame(AnalyticsListener.EventTime eventTime) {
                onRenderedFirstFrame();
            }
        });
        try {
            if (file != null) {
                player.preparePlayer(Uri.fromFile(file), "other");
            } else {
                final int reference = FileLoader.getInstance(currentAccount).getFileReference(message);
                player.preparePlayer(VideoPlayer.VideoUri.getUri(currentAccount, document, reference), "other", FileLoader.PRIORITY_HIGH, 0);
            }
            player.setMute(muted);
            if (activeFeed.playbackPosition > 0) {
                player.seekTo(activeFeed.playbackPosition);
            }
        } catch (Exception e) {
            FileLog.e(e);
            releasePlayer();
            playbackError = true;
        }
    }

    private void savePlaybackPosition() {
        if (activeFeed != null && videoPlayer != null) {
            activeFeed.playbackPosition = Math.max(0, videoPlayer.getCurrentPosition());
        }
    }

    private void pausePlayer() {
        if (videoPlayer != null) {
            videoPlayer.pause();
        }
        if (rootView != null) {
            rootView.setKeepScreenOn(false);
        }
    }

    private void releasePlayer() {
        playerGeneration++;
        pausePlayer();
        if (videoPlayer != null) {
            videoPlayer.releasePlayer(true);
            videoPlayer = null;
        }
        if (activePage != null) {
            activePage.resetPlaybackVisuals();
        }
    }

    private void togglePause(ClipPageView page) {
        if (page != activePage || !canWatch()) {
            return;
        }
        activeFeed.userPaused = !activeFeed.userPaused;
        updatePlayback();
    }

    private void toggleMute() {
        muted = !muted;
        if (videoPlayer != null) {
            videoPlayer.setMute(muted);
        }
        updateVisibleMetadata();
    }

    private void retryPlayback() {
        if (streamingRequired && !SharedConfig.streamMedia) SharedConfig.toggleStreamMedia();
        streamingRequired = false;
        playbackError = false;
        if (activeFeed != null) {
            activeFeed.userPaused = false;
        }
        updatePlayback();
    }

    private void loadChannelInfo(MessageObject message) {
        getMessagesController().loadFullChat(-message.getDialogId(), classGuid, false);
        final StarsController.MessageId starMessage = StarsController.MessageId.from(message);
        if (starMessage.did < 0 && starMessage.did != message.getDialogId()) {
            getMessagesController().loadFullChat(-starMessage.did, classGuid, false);
        }
    }

    private ReactionsLayoutInBubble.VisibleReaction getLikeReaction(MessageObject message) {
        final TLRPC.ChatFull info = getMessagesController().getChatFull(-message.getDialogId());
        if (info == null || !message.canSetReaction()) return null;
        if (ChatObject.reactionIsAvailable(info, "\u2764")) {
            return ReactionsLayoutInBubble.VisibleReaction.fromEmojicon("\u2764");
        }
        final String preferred = getMediaDataController().getDoubleTapReaction();
        if (preferred != null && ChatObject.reactionIsAvailable(info, preferred)) {
            return ReactionsLayoutInBubble.VisibleReaction.fromEmojicon(preferred);
        }
        if (info.available_reactions instanceof TLRPC.TL_chatReactionsSome) {
            for (TLRPC.Reaction reaction : ((TLRPC.TL_chatReactionsSome) info.available_reactions).reactions) {
                if (reaction instanceof TLRPC.TL_reactionEmoji) {
                    return ReactionsLayoutInBubble.VisibleReaction.fromTL(reaction);
                }
            }
        }
        return null;
    }

    private void react(ClipPageView page, boolean onlyAdd) {
        final MessageObject message = page.message;
        final ReactionsLayoutInBubble.VisibleReaction reaction = getLikeReaction(message);
        if (reaction == null) {
            if (getMessagesController().getChatFull(-message.getDialogId()) == null) {
                loadChannelInfo(message);
                showHint(getString(R.string.Loading));
            } else {
                showHint(getString(R.string.SendReactionsIsRestrictedInThisChat));
            }
            return;
        }
        if (onlyAdd && message.hasChosenReaction(reaction)) return;
        final boolean added = message.selectReaction(reaction, false, false);
        page.updateMetadata();
        if (added) {
            page.likeButton.animate().scaleX(1.2f).scaleY(1.2f).setDuration(120).withEndAction(() ->
                page.likeButton.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start()
            ).start();
        }
        getSendMessagesHelper().sendReaction(message, new ArrayList<>(message.getChoosenReactions()), added ? reaction : null, false, true, this, this::updateVisibleMetadata);
    }

    private void openComments(MessageObject message) {
        if (message.messageOwner.replies == null || !message.messageOwner.replies.comments || message.messageOwner.replies.channel_id == 0) {
            showHint(getString(R.string.NoComments));
            return;
        }
        if (discussionRequest != 0) {
            getConnectionsManager().cancelRequest(discussionRequest, true);
        }
        final TLRPC.Chat originalChat = getMessagesController().getChat(-message.getDialogId());
        final long discussionId = message.messageOwner.replies.channel_id;
        final TLRPC.TL_messages_getDiscussionMessage req = new TLRPC.TL_messages_getDiscussionMessage();
        req.peer = getMessagesController().getInputPeer(message.getDialogId());
        req.msg_id = message.getId();
        discussionMessage = message;
        updateVisibleMetadata();
        final int[] requestId = new int[1];
        requestId[0] = getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (isFinished || discussionRequest != requestId[0]) return;
            discussionRequest = 0;
            discussionMessage = null;
            updateVisibleMetadata();
            if (isPaused || !fullyVisible || activeMessage != message) return;
            if (!(response instanceof TLRPC.TL_messages_discussionMessage)) {
                showHint(getString(error != null ? R.string.TeleClipsLoadError : R.string.NoComments));
                return;
            }
            final TLRPC.TL_messages_discussionMessage result = (TLRPC.TL_messages_discussionMessage) response;
            getMessagesController().putUsers(result.users, false);
            getMessagesController().putChats(result.chats, false);
            getMessagesStorage().putUsersAndChats(result.users, result.chats, true, true);
            final ArrayList<MessageObject> thread = new ArrayList<>();
            for (TLRPC.Message reply : result.messages) {
                if (!(reply instanceof TLRPC.TL_messageEmpty) && reply.peer_id != null && reply.peer_id.channel_id == discussionId) {
                    reply.isThreadMessage = true;
                    thread.add(new MessageObject(currentAccount, reply, true, true));
                }
            }
            if (thread.isEmpty()) {
                showHint(getString(R.string.NoComments));
                return;
            }
            getMessagesStorage().loadMessageAttachPaths(thread, () -> AndroidUtilities.runOnUIThread(() -> {
                if (isFinished || isPaused || activeMessage != message) return;
                final Bundle args = new Bundle();
                args.putLong("chat_id", discussionId);
                args.putInt("message_id", Math.max(1, result.read_inbox_max_id));
                args.putInt("unread_count", result.unread_count);
                final ChatActivity chat = new ChatActivity(args);
                chat.setCurrentAccount(currentAccount);
                chat.setThreadMessages(thread, originalChat, message.getId(), result.read_inbox_max_id, result.read_outbox_max_id, null);
                presentFragment(chat);
            }));
        }));
        discussionRequest = requestId[0];
        getConnectionsManager().bindRequestToGuid(requestId[0], classGuid);
    }

    private void sendStarTip(MessageObject message) {
        final StarsController.MessageId key = StarsController.MessageId.from(message);
        final TLRPC.ChatFull info = getMessagesController().getChatFull(-key.did);
        if (info == null) {
            loadChannelInfo(message);
            showHint(getString(R.string.Loading));
            return;
        }
        if (!info.paid_reactions_available) {
            final TLRPC.Chat chat = getMessagesController().getChat(-key.did);
            showHint(LocaleController.formatString(R.string.StarsReactionsDisabled, chat == null ? "" : chat.title));
            return;
        }
        final ArrayList<TLRPC.MessageReactor> reactors = message.messageOwner.reactions == null ? null : message.messageOwner.reactions.top_reactors;
        showClipDialog(new StarsReactionsSheet(getParentActivity(), currentAccount, key.did, this, message, reactors, true, false, 0, resourceProvider));
    }

    private void share(MessageObject message) {
        if (message.messageOwner.noforwards || getMessagesController().isPeerNoForwards(message.getDialogId())) {
            showHint(getString(R.string.TeleClipsShareRestricted));
            return;
        }
        final TLRPC.Chat chat = getMessagesController().getChat(-message.getDialogId());
        final String username = chat == null ? null : ChatObject.getPublicUsername(chat);
        final String link = TextUtils.isEmpty(username) ? null : "https://" + getMessagesController().linkPrefix + "/" + username + "/" + message.getId();
        final ArrayList<MessageObject> messages = new ArrayList<>();
        messages.add(message);
        showClipDialog(new ShareAlert(getParentActivity(), messages, null, true, link, false, resourceProvider));
    }

    private void showClipDialog(Dialog dialog) {
        preloader.clear();
        modalPaused = true;
        pausePlayer();
        if (showDialog(dialog, dismissed -> {
            modalPaused = false;
            if (rootView != null) rootView.post(this::updatePlayback);
        }) == null) {
            modalPaused = false;
            updatePlayback();
        }
    }

    private void showHint(CharSequence text) {
        if (!isFinished && rootView != null) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, text).show();
        }
    }

    private void openChannel(MessageObject message) {
        final ChatActivity chat = ChatActivity.of(message.getDialogId());
        chat.setCurrentAccount(currentAccount);
        presentFragment(chat);
    }

    private void joinOrOpen(MessageObject message) {
        final TLRPC.Chat chat = getMessagesController().getChat(-message.getDialogId());
        if (chat == null) return;
        if (ChatObject.isInChat(chat)) {
            openChannel(message);
            return;
        }
        if (!joiningChannels.add(chat.id)) return;
        updateVisibleMetadata();
        getMessagesController().addUserToChat(chat.id, getUserConfig().getCurrentUser(), 0, null, this, true, () -> {
            joiningChannels.remove(chat.id);
            if (isFinished) return;
            if (ChatObject.isInChat(getMessagesController().getChat(chat.id))) {
                justJoinedChannels.add(chat.id);
                AndroidUtilities.runOnUIThread(() -> {
                    justJoinedChannels.remove(chat.id);
                    updateVisibleMetadata();
                }, 1200);
            }
            updateVisibleMetadata();
            feeds[TeleClipsFeed.FOLLOWING].onDialogsChanged();
        }, error -> {
            joiningChannels.remove(chat.id);
            updateVisibleMetadata();
            return true;
        });
    }

    private void updateVisibleMetadata() {
        if (listView == null || isFinished) return;
        for (int i = 0; i < listView.getChildCount(); i++) {
            if (listView.getChildAt(i) instanceof ClipPageView) {
                ((ClipPageView) listView.getChildAt(i)).updateMetadata();
            }
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (isFinished) return;
        if (id == NotificationCenter.messageReceivedByServer) {
            if (args[2] instanceof TLRPC.Message && TeleClipsMedia.hasClipTag(((TLRPC.Message) args[2]).message)) {
                feeds[TeleClipsFeed.EXPLORE].addPublished((TLRPC.Message) args[2]);
            }
        } else if (id == NotificationCenter.didUpdateReactions) {
            final long dialogId = (Long) args[0];
            final int messageId = (Integer) args[1];
            for (TeleClipsFeed feed : feeds) {
                for (MessageObject message : feed.getMessages()) {
                    if (message.getDialogId() == dialogId && message.getId() == messageId) {
                        message.messageOwner.reactions = (TLRPC.TL_messageReactions) args[2];
                    }
                }
            }
            updateVisibleMetadata();
        } else if (id == NotificationCenter.dialogsNeedReload) {
            feeds[TeleClipsFeed.FOLLOWING].onDialogsChanged();
        } else if (id == NotificationCenter.chatInfoDidLoad || id == NotificationCenter.updateInterfaces) {
            updateVisibleMetadata();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (rootView != null) rootView.post(this::updatePlayback);
    }

    @Override
    public void onPause() {
        preloader.clear();
        pausePlayer();
        savePlaybackPosition();
        super.onPause();
    }

    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        fullyVisible = true;
        slidePaused = false;
        if (rootView != null) rootView.post(this::updatePlayback);
    }

    @Override
    public void onBecomeFullyHidden() {
        preloader.clear();
        fullyVisible = false;
        savePlaybackPosition();
        releasePlayer();
        super.onBecomeFullyHidden();
    }

    @Override
    public void onBeginSlide() {
        preloader.clear();
        slidePaused = true;
        pausePlayer();
        super.onBeginSlide();
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        slidePaused = false;
        if (rootView != null) rootView.post(this::updatePlayback);
    }

    @Override
    public void clearViews() {
        if (preloader != null) preloader.clear();
        savePlaybackPosition();
        releasePlayer();
        activePage = null;
        activeMessage = null;
        activeFeed = null;
        if (listView != null) listView.setAdapter(null);
        listView = null;
        rootView = null;
        adapter = null;
        super.clearViews();
    }

    @Override
    public void onFragmentDestroy() {
        preloader.clear();
        fullyVisible = false;
        savePlaybackPosition();
        releasePlayer();
        for (TeleClipsFeed feed : feeds) {
            if (feed != null) feed.destroy();
        }
        for (int event : OBSERVERS) {
            NotificationCenter.getInstance(currentAccount).removeObserver(this, event);
        }
        super.onFragmentDestroy();
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public EdgeToEdgeSupportMode getEdgeToEdgeSupportMode() {
        return EdgeToEdgeSupportMode.FULL;
    }

    @Override
    public boolean drawEdgeNavigationBar() {
        return false;
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }

    @Override
    public int getNavigationBarColor() {
        return color(Theme.key_windowBackgroundGray);
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        insetLeft = left;
        insetTop = top;
        insetRight = right;
        insetBottom = bottom;
        if (topBar != null) {
            ((FrameLayout.LayoutParams) topBar.getLayoutParams()).topMargin = top;
            topBar.setPadding(left, 0, right, 0);
            topBar.requestLayout();
        }
        if (moreButton != null) {
            ((FrameLayout.LayoutParams) moreButton.getLayoutParams()).topMargin = top + dp(104);
            moreButton.requestLayout();
        }
        if (listView != null) {
            for (int i = 0; i < listView.getChildCount(); i++) {
                if (listView.getChildAt(i) instanceof ClipPageView) {
                    ((ClipPageView) listView.getChildAt(i)).updateInsets();
                }
            }
        }
    }

    private class ClipsAdapter extends RecyclerView.Adapter<ClipViewHolder> {
        @NonNull
        @Override
        public ClipViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final ClipPageView page = new ClipPageView(parent.getContext());
            page.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new ClipViewHolder(page);
        }

        @Override
        public void onBindViewHolder(@NonNull ClipViewHolder holder, int position) {
            holder.page.bind(currentFeed().getMessages().get(position));
            // The first item may attach after the network callback's posted playback update.
            holder.page.post(() -> {
                if (listView != null && holder.page.getParent() == listView) updatePlayback();
            });
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull ClipViewHolder holder) {
            if (holder.page == activePage) {
                savePlaybackPosition();
                releasePlayer();
            }
        }

        @Override
        public void onViewRecycled(@NonNull ClipViewHolder holder) {
            holder.page.resetPlaybackVisuals();
        }

        @Override
        public int getItemCount() {
            return currentFeed().getMessages().size();
        }
    }

    private static class ClipViewHolder extends RecyclerView.ViewHolder {
        final ClipPageView page;

        ClipViewHolder(ClipPageView page) {
            super(page);
            this.page = page;
        }
    }

    private class ClipPageView extends FrameLayout {
        private MessageObject message;
        private boolean captionExpanded;
        private final AspectRatioFrameLayout aspectLayout;
        private final TextureView textureView;
        private final View tapArea;
        private final BackupImageView thumbnail;
        private final BackupImageView avatarView;
        private final TextView titleView;
        private final TextView creatorSubtitle;
        private final TextView captionView;
        private final TextView captionMore;
        private final ScrollView captionScroll;
        private final LinearLayout creatorLayout;
        private final LinearLayout actionsLayout;
        private final ButtonWithCounterView joinButton;
        private final ActionView likeButton;
        private final ActionView commentsButton;
        private final ActionView starsButton;
        private final ActionView shareButton;
        private final ImageView muteButton;
        private final RadialProgressView bufferingView;
        private final ImageView playIcon;
        private final LinearLayout errorLayout;
        private final TextView playbackErrorText;
        private final ButtonWithCounterView retryButton;

        ClipPageView(Context context) {
            super(context);
            setBackgroundColor(color(Theme.key_windowBackgroundGray));
            setClipChildren(true);
            aspectLayout = new AspectRatioFrameLayout(context);
            aspectLayout.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
            textureView = new TextureView(context);
            textureView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            aspectLayout.addView(textureView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            addView(aspectLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));
            thumbnail = new BackupImageView(context);
            thumbnail.getImageReceiver().setAspectFit(false);
            thumbnail.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(thumbnail, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            tapArea = new View(context);
            tapArea.setContentDescription(getString(R.string.TeleClipsPause));
            tapArea.setOnClickListener(v -> togglePause(this));
            final GestureDetector gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    return tapArea.performClick();
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (message != null && activePage == ClipPageView.this) react(ClipPageView.this, true);
                    return true;
                }
            });
            tapArea.setOnTouchListener((view, event) -> gestures.onTouchEvent(event));
            addView(tapArea, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            final View scrim = new View(context);
            scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{
                Theme.multAlpha(color(Theme.key_windowBackgroundGray), .85f), 0
            }));
            addView(scrim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 300, Gravity.BOTTOM));

            creatorLayout = new LinearLayout(context);
            creatorLayout.setOrientation(LinearLayout.VERTICAL);
            final LinearLayout creatorRow = new LinearLayout(context);
            creatorRow.setGravity(Gravity.CENTER_VERTICAL);
            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(dp(22));
            avatarView.setOnClickListener(v -> openChannel(message));
            creatorRow.addView(avatarView, LayoutHelper.createLinear(44, 44));
            titleView = textView(context, 16, Theme.key_windowBackgroundWhiteBlackText);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleView.setOnClickListener(v -> openChannel(message));
            final LinearLayout titles = new LinearLayout(context);
            titles.setOrientation(LinearLayout.VERTICAL);
            titles.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            creatorSubtitle = textView(context, 12, Theme.key_windowBackgroundWhiteGrayText3);
            creatorSubtitle.setSingleLine(true);
            creatorSubtitle.setEllipsize(TextUtils.TruncateAt.END);
            titles.addView(creatorSubtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
            creatorRow.addView(titles, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
            creatorLayout.addView(creatorRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            captionScroll = new ScrollView(context) {
                @Override
                public boolean dispatchTouchEvent(MotionEvent event) {
                    if (captionExpanded) {
                        getParent().requestDisallowInterceptTouchEvent(event.getActionMasked() != MotionEvent.ACTION_UP && event.getActionMasked() != MotionEvent.ACTION_CANCEL);
                    }
                    return super.dispatchTouchEvent(event);
                }

                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    final int limit = captionExpanded ? Math.min(dp(180), Math.max(dp(64), ClipPageView.this.getHeight() / 3)) : dp(52);
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
                }
            };
            captionScroll.setVerticalScrollBarEnabled(false);
            captionView = textView(context, 14, Theme.key_windowBackgroundWhiteBlackText);
            captionView.setMaxLines(2);
            captionView.setEllipsize(TextUtils.TruncateAt.END);
            captionView.setOnClickListener(v -> toggleCaption());
            captionScroll.addView(captionView, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
            creatorLayout.addView(captionScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
            captionMore = textView(context, 13, Theme.key_windowBackgroundWhiteGrayText3);
            captionMore.setPadding(0, dp(4), 0, dp(4));
            captionMore.setOnClickListener(v -> toggleCaption());
            creatorLayout.addView(captionMore, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            joinButton = new ButtonWithCounterView(context, resourceProvider).setRound();
            joinButton.setPadding(dp(22), 0, dp(22), 0);
            joinButton.setMinWidth(dp(104));
            joinButton.setOnClickListener(v -> joinOrOpen(message));
            creatorLayout.addView(joinButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 40, 0, 12, 0, 0));
            addView(creatorLayout, LayoutHelper.createFrameRelatively(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.START | Gravity.BOTTOM, 16, 0, 80, 20));

            actionsLayout = new LinearLayout(context);
            actionsLayout.setOrientation(LinearLayout.VERTICAL);
            actionsLayout.setGravity(Gravity.CENTER_HORIZONTAL);
            muteButton = new ImageView(context);
            muteButton.setScaleType(ImageView.ScaleType.CENTER);
            muteButton.setColorFilter(color(Theme.key_windowBackgroundWhiteBlackText));
            muteButton.setBackground(Theme.createSelectorDrawable(color(Theme.key_listSelector)));
            muteButton.setOnClickListener(v -> toggleMute());
            actionsLayout.addView(muteButton, LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL));
            likeButton = new ActionView(context, R.drawable.msg_reactions, getString(R.string.TeleClipsReact));
            likeButton.setOnClickListener(v -> react(this, false));
            actionsLayout.addView(likeButton, LayoutHelper.createLinear(56, 64));
            commentsButton = new ActionView(context, R.drawable.msg_discussion, getString(R.string.TeleClipsComments));
            commentsButton.setOnClickListener(v -> openComments(message));
            actionsLayout.addView(commentsButton, LayoutHelper.createLinear(56, 64));
            starsButton = new ActionView(context, R.drawable.star_reaction, getString(R.string.TeleClipsStarTip));
            starsButton.label.setText(getString(R.string.TeleClipsTip));
            starsButton.icon.setColorFilter(color(Theme.key_starsGradient1));
            starsButton.setOnClickListener(v -> sendStarTip(message));
            actionsLayout.addView(starsButton, LayoutHelper.createLinear(56, 64));
            shareButton = new ActionView(context, R.drawable.msg_shareout, getString(R.string.ShareFile));
            shareButton.label.setVisibility(GONE);
            shareButton.setOnClickListener(v -> share(message));
            actionsLayout.addView(shareButton, LayoutHelper.createLinear(56, 64));
            addView(actionsLayout, LayoutHelper.createFrameRelatively(64, LayoutHelper.WRAP_CONTENT, Gravity.END | Gravity.BOTTOM, 0, 0, 8, 20));

            bufferingView = new RadialProgressView(context, resourceProvider);
            bufferingView.setProgressColor(color(Theme.key_windowBackgroundWhiteBlackText));
            bufferingView.setVisibility(GONE);
            addView(bufferingView, LayoutHelper.createFrame(36, 36, Gravity.CENTER));
            playIcon = new ImageView(context);
            playIcon.setImageResource(R.drawable.ic_action_play);
            playIcon.setColorFilter(color(Theme.key_windowBackgroundWhiteBlackText));
            playIcon.setVisibility(GONE);
            playIcon.setOnClickListener(v -> togglePause(this));
            playIcon.setContentDescription(getString(R.string.TeleClipsPlay));
            addView(playIcon, LayoutHelper.createFrame(56, 56, Gravity.CENTER));

            errorLayout = new LinearLayout(context);
            errorLayout.setOrientation(LinearLayout.VERTICAL);
            errorLayout.setGravity(Gravity.CENTER_HORIZONTAL);
            playbackErrorText = textView(context, 14, Theme.key_windowBackgroundWhiteBlackText);
            playbackErrorText.setGravity(Gravity.CENTER);
            errorLayout.addView(playbackErrorText, LayoutHelper.createLinear(220, LayoutHelper.WRAP_CONTENT));
            retryButton = new ButtonWithCounterView(context, resourceProvider).setRound();
            retryButton.setPadding(dp(16), 0, dp(16), 0);
            retryButton.setMinWidth(dp(128));
            retryButton.setOnClickListener(v -> retryPlayback());
            errorLayout.addView(retryButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 40, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0));
            errorLayout.setVisibility(GONE);
            addView(errorLayout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        }

        void bind(MessageObject newMessage) {
            if (message == newMessage) {
                updateMetadata();
                return;
            }
            if (this == activePage) {
                savePlaybackPosition();
                releasePlayer();
                activePage = null;
                activeMessage = null;
            }
            message = newMessage;
            captionExpanded = false;
            resetPlaybackVisuals();
            final TLRPC.Document document = TeleClipsMedia.playbackDocument(message);
            for (TLRPC.DocumentAttribute attribute : document.attributes) {
                if (attribute instanceof TLRPC.TL_documentAttributeVideo && attribute.h > 0) {
                    aspectLayout.setAspectRatio(attribute.w / (float) attribute.h, 0);
                    break;
                }
            }
            final TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(message.photoThumbs, 320);
            thumbnail.setImage(ImageLocation.getForObject(thumb, message.photoThumbsObject), "320_568", message.strippedThumb, message);
            final String plainCaption = message.messageOwner.message == null ? "" : message.messageOwner.message;
            if (plainCaption.regionMatches(true, 0, TeleClipsMedia.HASHTAG, 0, TeleClipsMedia.HASHTAG.length())) {
                captionView.setText(plainCaption.substring(TeleClipsMedia.HASHTAG.length()).trim());
            } else {
                captionView.setText(message.caption != null ? message.caption : plainCaption);
            }
            captionScroll.setVisibility(TextUtils.isEmpty(captionView.getText()) ? GONE : VISIBLE);
            captionMore.setVisibility(captionView.length() > 100 || captionView.getText().toString().contains("\n") ? VISIBLE : GONE);
            updateCaption();
            likeButton.animate().cancel();
            likeButton.setScaleX(1f);
            likeButton.setScaleY(1f);
            updateMetadata();
            updateInsets();
        }

        private void toggleCaption() {
            captionExpanded = !captionExpanded;
            updateCaption();
        }

        private void updateCaption() {
            captionView.setMaxLines(captionExpanded ? Integer.MAX_VALUE : 2);
            captionView.setEllipsize(captionExpanded ? null : TextUtils.TruncateAt.END);
            captionMore.setText(getString(captionExpanded ? R.string.TeleClipsLess : R.string.TeleClipsMore));
            captionScroll.scrollTo(0, 0);
            captionScroll.requestLayout();
        }

        void updateInsets() {
            final FrameLayout.LayoutParams creatorParams = (FrameLayout.LayoutParams) creatorLayout.getLayoutParams();
            creatorParams.leftMargin = insetLeft + dp(LocaleController.isRTL ? 80 : 16);
            creatorParams.rightMargin = insetRight + dp(LocaleController.isRTL ? 16 : 80);
            creatorParams.bottomMargin = insetBottom + dp(20);
            creatorLayout.setLayoutParams(creatorParams);
            final FrameLayout.LayoutParams actionsParams = (FrameLayout.LayoutParams) actionsLayout.getLayoutParams();
            actionsParams.leftMargin = insetLeft + dp(LocaleController.isRTL ? 8 : 0);
            actionsParams.rightMargin = insetRight + dp(LocaleController.isRTL ? 0 : 8);
            actionsParams.bottomMargin = insetBottom + dp(20);
            actionsLayout.setLayoutParams(actionsParams);
        }

        void updateMetadata() {
            if (message == null) return;
            final TLRPC.Chat chat = getMessagesController().getChat(-message.getDialogId());
            if (chat != null) {
                final AvatarDrawable avatar = new AvatarDrawable();
                final TLRPC.User author = message.messageOwner.from_id != null && message.messageOwner.from_id.user_id != 0 ? getMessagesController().getUser(message.messageOwner.from_id.user_id) : null;
                if (author != null) {
                    avatar.setInfo(author);
                    avatarView.setForUserOrChat(author, avatar);
                    titleView.setText(UserObject.getUserName(author));
                } else {
                    avatar.setInfo(chat);
                    avatarView.setForUserOrChat(chat, avatar);
                    titleView.setText(chat.title);
                }
                final String username = ChatObject.getPublicUsername(chat);
                creatorSubtitle.setText(TextUtils.isEmpty(username) ? "" : "@" + username);
                avatarView.setContentDescription(chat.title);
                joinButton.setText(getString(justJoinedChannels.contains(chat.id) ? R.string.TeleClipsJoined : R.string.TeleClipsJoin), true);
                joinButton.setVisibility(!ChatObject.isInChat(chat) || justJoinedChannels.contains(chat.id) ? VISIBLE : GONE);
                joinButton.setLoading(joiningChannels.contains(chat.id));
                joinButton.setEnabled(!joiningChannels.contains(chat.id));
            }
            int count = 0;
            if (message.messageOwner.reactions != null) {
                for (TLRPC.ReactionCount reaction : message.messageOwner.reactions.results) {
                    if (!(reaction.reaction instanceof TLRPC.TL_reactionPaid)) count += reaction.count;
                }
            }
            likeButton.setCounter(count);
            final ReactionsLayoutInBubble.VisibleReaction reaction = getLikeReaction(message);
            final boolean chosen = reaction != null && message.hasChosenReaction(reaction);
            likeButton.icon.setImageResource(chosen ? R.drawable.msg_reactions_filled : R.drawable.msg_reactions);
            likeButton.icon.setColorFilter(color(chosen ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteBlackText));
            likeButton.setAlpha(reaction == null ? .5f : 1f);
            likeButton.setVisibility(reaction != null || getMessagesController().getChatFull(-message.getDialogId()) == null ? VISIBLE : GONE);
            likeButton.setSelected(chosen);
            final boolean comments = message.messageOwner.replies != null && message.messageOwner.replies.comments;
            commentsButton.setCounter(comments ? message.messageOwner.replies.replies : 0);
            commentsButton.setVisibility(VISIBLE);
            commentsButton.setEnabled(discussionMessage != message);
            starsButton.setVisibility(VISIBLE);
            shareButton.setAlpha(message.messageOwner.noforwards || getMessagesController().isPeerNoForwards(message.getDialogId()) ? .5f : 1f);
            muteButton.setImageResource(muted ? R.drawable.video_muted : R.drawable.list_unmute);
            muteButton.setContentDescription(getString(muted ? R.string.TeleClipsUnmute : R.string.TeleClipsMute));
        }

        void showPlaybackState(boolean paused, boolean loading, boolean error) {
            tapArea.setContentDescription(getString(paused ? R.string.TeleClipsPlay : R.string.TeleClipsPause));
            playIcon.setVisibility(paused && !error ? VISIBLE : GONE);
            bufferingView.setVisibility(loading && !paused && !error ? VISIBLE : GONE);
            errorLayout.setVisibility(error ? VISIBLE : GONE);
            playbackErrorText.setText(getString(streamingRequired ? R.string.TeleClipsStreamingRequired : R.string.TeleClipsPlaybackError));
            retryButton.setText(getString(streamingRequired ? R.string.TeleClipsEnableStreaming : R.string.Retry));
        }

        void resetPlaybackVisuals() {
            textureView.setAlpha(0f);
            thumbnail.setVisibility(VISIBLE);
            bufferingView.setVisibility(GONE);
            playIcon.setVisibility(GONE);
            errorLayout.setVisibility(GONE);
        }
    }

    private class ActionView extends LinearLayout {
        final ImageView icon;
        final TextView label;
        private final String description;

        ActionView(Context context, int resource, String description) {
            super(context);
            this.description = description;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setContentDescription(description);
            setBackground(Theme.createSelectorDrawable(color(Theme.key_listSelector)));
            icon = new ImageView(context);
            icon.setImageResource(resource);
            icon.setColorFilter(color(Theme.key_windowBackgroundWhiteBlackText));
            icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(icon, LayoutHelper.createLinear(30, 30, Gravity.CENTER_HORIZONTAL));
            label = textView(context, 12, Theme.key_windowBackgroundWhiteBlackText);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 0));
        }

        void setCounter(int count) {
            label.setText(LocaleController.formatNumber(count, ' '));
            setContentDescription(description + ", " + label.getText());
        }
    }

    /** The same eased, text-width underline convention used by Telegram's tab strips. */
    private class FeedTabs extends LinearLayout {
        private final TextView[] tabs = new TextView[2];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final AnimatedFloat center = new AnimatedFloat(this, 250, CubicBezierInterpolator.EASE_OUT_QUINT);
        private final AnimatedFloat width = new AnimatedFloat(this, 250, CubicBezierInterpolator.EASE_OUT_QUINT);
        private boolean laidOut;

        FeedTabs(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setLayoutDirection(LocaleController.isRTL ? LAYOUT_DIRECTION_RTL : LAYOUT_DIRECTION_LTR);
            for (int i = 0; i < tabs.length; i++) {
                final int index = i;
                tabs[i] = textView(context, 15, Theme.key_actionBarTabActiveText);
                tabs[i].setText(getString(i == TeleClipsFeed.FOLLOWING ? R.string.TeleClipsFollowing : R.string.TeleClipsExplore));
                tabs[i].setTypeface(AndroidUtilities.bold());
                tabs[i].setGravity(Gravity.CENTER);
                tabs[i].setSingleLine(true);
                tabs[i].setEllipsize(TextUtils.TruncateAt.END);
                tabs[i].setBackground(Theme.createSelectorDrawable(color(Theme.key_listSelector)));
                tabs[i].setOnClickListener(v -> selectFeed(index));
                addView(tabs[i], LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
            }
            updateSelection();
        }

        void updateSelection() {
            for (int i = 0; i < tabs.length; i++) {
                tabs[i].setSelected(selectedFeed == i);
                tabs[i].setTextColor(color(selectedFeed == i ? Theme.key_actionBarTabActiveText : Theme.key_actionBarTabUnactiveText));
            }
            invalidate();
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (!laidOut) {
                center.force(tabs[selectedFeed].getLeft() + tabs[selectedFeed].getWidth() / 2f);
                width.force(indicatorWidth());
                laidOut = true;
            }
        }

        private float indicatorWidth() {
            return Math.min(tabs[selectedFeed].getWidth() - dp(12), tabs[selectedFeed].getPaint().measureText(tabs[selectedFeed].getText().toString()));
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            final float x = center.set(tabs[selectedFeed].getLeft() + tabs[selectedFeed].getWidth() / 2f);
            final float w = width.set(Math.max(dp(16), indicatorWidth()));
            paint.setColor(color(Theme.key_actionBarTabLine));
            canvas.drawRoundRect(x - w / 2f, getHeight() - dp(4), x + w / 2f, getHeight() - dp(1), dp(2), dp(2), paint);
        }
    }
}
