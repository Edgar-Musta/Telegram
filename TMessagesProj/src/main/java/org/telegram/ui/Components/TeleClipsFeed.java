package org.telegram.ui.Components;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

/** Fragment-scoped, UI-thread feed state. All media is sourced from Telegram messages. */
public final class TeleClipsFeed {

    public static final int FOLLOWING = 0;
    public static final int EXPLORE = 1;

    private static final int CHANNEL_PAGE_SIZE = 12;
    private static final int CHANNELS_PER_BATCH = 6;
    private static final int MAX_PARALLEL_REQUESTS = 3;
    private static final int PUBLIC_PAGE_SIZE = 50;
    private static final int MAX_EMPTY_PAGES = 5;

    private final int currentAccount;
    private final int classGuid;
    private final int type;
    private final Runnable onChanged;
    private final ArrayList<MessageObject> messages = new ArrayList<>();
    private final HashSet<String> messageKeys = new HashSet<>();
    private final HashSet<Integer> requests = new HashSet<>();
    private final ArrayList<ChannelCursor> channels = new ArrayList<>();
    private final HashSet<Long> channelIds = new HashSet<>();
    private final HashSet<Long> failedChannels = new HashSet<>();

    private int generation;
    private int nextChannel;
    private int emptyPages;
    private int offsetId;
    private int offsetRate;
    private int revision;
    private long offsetPeer;
    private boolean initialized;
    private boolean loading;
    private boolean endReached;
    private boolean destroyed;
    private TLRPC.TL_error error;

    public int selectedPosition;
    public long playbackPosition;
    public boolean userPaused;
    public int prefetchedSize = -1;

    public TeleClipsFeed(int currentAccount, int classGuid, int type, Runnable onChanged) {
        this.currentAccount = currentAccount;
        this.classGuid = classGuid;
        this.type = type;
        this.onChanged = onChanged;
    }

    public int getType() {
        return type;
    }

    public ArrayList<MessageObject> getMessages() {
        return messages;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isLoading() {
        return loading;
    }

    public boolean isEndReached() {
        return endReached;
    }

    public TLRPC.TL_error getError() {
        return error;
    }

    public int getRevision() {
        return revision;
    }

    public void addPublished(TLRPC.Message message) {
        final MessageObject clip = createClip(message);
        if (clip != null && messageKeys.add(clip.getDialogId() + "_" + clip.getId())) {
            messages.add(0, clip);
            selectedPosition = 0;
            playbackPosition = 0;
            userPaused = false;
            revision++;
            notifyChanged();
        }
    }

    public void loadMore() {
        if (destroyed || loading || endReached) {
            return;
        }
        initialized = true;
        loading = true;
        error = null;
        emptyPages = 0;
        prefetchedSize = messages.size();
        failedChannels.clear();
        notifyChanged();
        if (type == FOLLOWING) {
            appendSubscribedChannels();
            loadFollowingBatch();
        } else {
            loadPublicPosts();
        }
    }

    public void refresh() {
        cancelRequests();
        initialized = loading = endReached = false;
        error = null;
        messages.clear();
        revision++;
        messageKeys.clear();
        channels.clear();
        channelIds.clear();
        failedChannels.clear();
        nextChannel = offsetId = offsetRate = selectedPosition = 0;
        offsetPeer = playbackPosition = 0;
        userPaused = false;
        prefetchedSize = -1;
        notifyChanged();
        loadMore();
    }

    public void onDialogsChanged() {
        if (destroyed || type != FOLLOWING) {
            return;
        }
        if (appendSubscribedChannels()) {
            endReached = false;
            prefetchedSize = -1;
            notifyChanged();
        }
    }

    private boolean appendSubscribedChannels() {
        final MessagesController controller = MessagesController.getInstance(currentAccount);
        boolean added = false;
        // Archived subscriptions are part of Following too.
        for (int folder = 0; folder <= 1; folder++) {
            final ArrayList<TLRPC.Dialog> dialogs = controller.getDialogs(folder);
            if (dialogs == null) {
                continue;
            }
            for (TLRPC.Dialog dialog : dialogs) {
                final long dialogId = dialog.id != 0 ? dialog.id : DialogObject.getPeerDialogId(dialog.peer);
                if (dialogId >= 0) {
                    continue;
                }
                final TLRPC.Chat chat = controller.getChat(-dialogId);
                if (isPublicChannel(chat) && ChatObject.isInChat(chat) && channelIds.add(chat.id)) {
                    channels.add(new ChannelCursor(chat.id));
                    added = true;
                }
            }
        }
        return added;
    }

    private void loadFollowingBatch() {
        final FollowingBatch batch = new FollowingBatch();
        final MessagesController controller = MessagesController.getInstance(currentAccount);
        for (int checked = 0; checked < channels.size() && batch.cursors.size() < CHANNELS_PER_BATCH; checked++) {
            final ChannelCursor cursor = channels.get(nextChannel++ % channels.size());
            final TLRPC.Chat chat = controller.getChat(cursor.chatId);
            if (!isPublicChannel(chat) || !ChatObject.isInChat(chat)) {
                cursor.endReached = true;
            }
            if (!cursor.endReached && !failedChannels.contains(cursor.chatId)) {
                batch.cursors.add(cursor);
            }
        }
        if (batch.cursors.isEmpty()) {
            finishFollowingLoad();
            return;
        }
        requestFollowingBatch(batch);
    }

    private void requestFollowingBatch(FollowingBatch batch) {
        while (!destroyed && batch.inFlight < MAX_PARALLEL_REQUESTS && batch.next < batch.cursors.size()) {
            final ChannelCursor cursor = batch.cursors.get(batch.next++);
            final TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
            req.peer = MessagesController.getInstance(currentAccount).getInputPeer(-cursor.chatId);
            req.q = "";
            req.filter = new TLRPC.TL_inputMessagesFilterVideo();
            req.limit = CHANNEL_PAGE_SIZE;
            req.offset_id = cursor.offsetId;
            req.min_date = ConnectionsManager.getInstance(currentAccount).getCurrentTime() - 90 * 24 * 60 * 60;
            batch.inFlight++;
            sendRequest(req, (response, requestError) -> {
                batch.inFlight--;
                if (response instanceof TLRPC.messages_Messages) {
                    final TLRPC.messages_Messages result = (TLRPC.messages_Messages) response;
                    cachePeers(result);
                    for (TLRPC.Message message : result.messages) {
                        final MessageObject clip = createClip(message);
                        if (clip != null) {
                            batch.messages.add(clip);
                        }
                    }
                    final int lastId = result.messages.isEmpty() ? 0 : result.messages.get(result.messages.size() - 1).id;
                    cursor.endReached = result.messages.size() < CHANNEL_PAGE_SIZE || lastId <= 0 || lastId == cursor.offsetId;
                    if (lastId > 0) {
                        cursor.offsetId = lastId;
                    }
                } else {
                    error = requestError != null ? requestError : unexpectedResponse();
                    failedChannels.add(cursor.chatId);
                }
                if (batch.next == batch.cursors.size() && batch.inFlight == 0) {
                    Collections.sort(batch.messages, (a, b) -> Integer.compare(b.messageOwner.date, a.messageOwner.date));
                    final int added = appendMessages(batch.messages);
                    // Search pages can contain only landscape videos. Advance the raw cursor anyway.
                    if (added == 0 && error == null && ++emptyPages < MAX_EMPTY_PAGES) {
                        loadFollowingBatch();
                    } else {
                        finishFollowingLoad();
                    }
                } else {
                    requestFollowingBatch(batch);
                }
            });
        }
    }

    private void finishFollowingLoad() {
        loading = false;
        endReached = true;
        for (ChannelCursor cursor : channels) {
            if (!cursor.endReached) {
                endReached = false;
                break;
            }
        }
        notifyChanged();
    }

    private void loadPublicPosts() {
        // A dedicated global namespace that regular users can publish into through public channels.
        final TLRPC.TL_channels_searchPosts req = new TLRPC.TL_channels_searchPosts();
        req.flags |= 1;
        req.hashtag = TeleClipsMedia.HASHTAG;
        req.limit = PUBLIC_PAGE_SIZE;
        req.offset_id = offsetId;
        req.offset_rate = offsetRate;
        req.offset_peer = offsetPeer == 0 ? new TLRPC.TL_inputPeerEmpty() : MessagesController.getInstance(currentAccount).getInputPeer(offsetPeer);
        sendRequest(req, (response, requestError) -> {
            if (!(response instanceof TLRPC.messages_Messages)) {
                error = requestError != null ? requestError : unexpectedResponse();
                loading = false;
                notifyChanged();
                return;
            }
            final TLRPC.messages_Messages result = (TLRPC.messages_Messages) response;
            cachePeers(result);
            final ArrayList<MessageObject> clips = new ArrayList<>();
            for (TLRPC.Message message : result.messages) {
                final MessageObject clip = createClip(message);
                if (clip != null) {
                    clips.add(clip);
                }
            }
            final int added = appendMessages(clips);
            // Match Telegram's public-hashtag search pagination; bit 0 indicates next_rate presence.
            endReached = result.messages.size() < req.limit || !(result instanceof TLRPC.TL_messages_messagesSlice);
            if (!result.messages.isEmpty()) {
                final TLRPC.Message last = result.messages.get(result.messages.size() - 1);
                final long lastPeer = DialogObject.getPeerDialogId(last.peer_id);
                if (last.id == offsetId && lastPeer == offsetPeer && result.next_rate == offsetRate) {
                    endReached = true;
                }
                offsetId = last.id;
                offsetPeer = lastPeer;
                offsetRate = result.next_rate;
            }
            if (added == 0 && !endReached && ++emptyPages < MAX_EMPTY_PAGES) {
                loadPublicPosts();
            } else {
                loading = false;
                notifyChanged();
            }
        });
    }

    private void cachePeers(TLRPC.messages_Messages result) {
        MessagesController.getInstance(currentAccount).putUsers(result.users, false);
        MessagesController.getInstance(currentAccount).putChats(result.chats, false);
        MessagesStorage.getInstance(currentAccount).putUsersAndChats(result.users, result.chats, true, true);
    }

    private MessageObject createClip(TLRPC.Message message) {
        if (message instanceof TLRPC.TL_messageEmpty || message.id <= 0 || message.peer_id == null || message.peer_id.channel_id == 0 || !(message.media instanceof TLRPC.TL_messageMediaDocument)) {
            return null;
        }
        final TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(message.peer_id.channel_id);
        if (!isPublicChannel(chat) || type == FOLLOWING && !ChatObject.isInChat(chat)) {
            return null;
        }
        final MessageObject object = new MessageObject(currentAccount, message, false, false);
        if (!object.isVideo() || object.isGif() || object.isRoundVideo() || message.media.ttl_seconds != 0 || object.hasMediaSpoilers()) {
            return null;
        }
        if (type == EXPLORE && !TeleClipsMedia.hasClipTag(message.message)) {
            return null;
        }
        return TeleClipsMedia.playbackDocument(object) != null ? object : null;
    }

    private static boolean isPublicChannel(TLRPC.Chat chat) {
        return chat != null && ChatObject.isChannelAndNotMegaGroup(chat) && ChatObject.isPublic(chat) && !ChatObject.isKickedFromChat(chat);
    }

    private int appendMessages(ArrayList<MessageObject> clips) {
        final int before = messages.size();
        for (MessageObject clip : clips) {
            if (messageKeys.add(clip.getDialogId() + "_" + clip.getId())) {
                messages.add(clip);
            }
        }
        return messages.size() - before;
    }

    private void sendRequest(TLObject request, RequestDelegate delegate) {
        final int token = generation;
        final int[] requestId = new int[1];
        final ConnectionsManager connectionsManager = ConnectionsManager.getInstance(currentAccount);
        requestId[0] = connectionsManager.sendRequest(request, (response, requestError) -> AndroidUtilities.runOnUIThread(() -> {
            requests.remove(requestId[0]);
            if (!destroyed && generation == token) {
                delegate.run(response, requestError);
            }
        }), ConnectionsManager.RequestFlagDoNotWaitFloodWait);
        requests.add(requestId[0]);
        connectionsManager.bindRequestToGuid(requestId[0], classGuid);
    }

    private static TLRPC.TL_error unexpectedResponse() {
        final TLRPC.TL_error error = new TLRPC.TL_error();
        error.text = "TELECLIPS_LOAD_FAILED";
        return error;
    }

    private void notifyChanged() {
        if (!destroyed) {
            onChanged.run();
        }
    }

    private void cancelRequests() {
        generation++;
        for (Integer request : requests) {
            ConnectionsManager.getInstance(currentAccount).cancelRequest(request, true);
        }
        requests.clear();
    }

    public void destroy() {
        destroyed = true;
        cancelRequests();
    }

    private static final class ChannelCursor {
        final long chatId;
        int offsetId;
        boolean endReached;

        ChannelCursor(long chatId) {
            this.chatId = chatId;
        }
    }

    private static final class FollowingBatch {
        final ArrayList<ChannelCursor> cursors = new ArrayList<>();
        final ArrayList<MessageObject> messages = new ArrayList<>();
        int next;
        int inFlight;
    }
}
