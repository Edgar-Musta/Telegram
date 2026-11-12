package org.telegram.ui.Components;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/** Uses Telegram's bounded MP4-prefix preloader (up to 2 MB), without extra players. */
public final class TeleClipsPreloader {
    private final int currentAccount;
    private final HashMap<Long, TLRPC.Document> owned = new HashMap<>();

    public TeleClipsPreloader(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    public void setPosition(ArrayList<MessageObject> messages, int position) {
        final FileLoader loader = FileLoader.getInstance(currentAccount);
        final TLRPC.Document current = position >= 0 && position < messages.size() ? TeleClipsMedia.playbackDocument(messages.get(position)) : null;
        if (current != null) owned.remove(current.id); // Playback takes over its stream operation.
        final HashSet<Long> wanted = new HashSet<>();
        for (int i = position + 1; i < messages.size() && i <= position + 2; i++) {
            final TLRPC.Document document = TeleClipsMedia.playbackDocument(messages.get(i));
            if (document != null && (current == null || current.id != document.id)) wanted.add(document.id);
        }
        for (Long id : new ArrayList<>(owned.keySet())) {
            if (!wanted.contains(id)) {
                cancelPrefix(owned.remove(id));
            }
        }
        for (int i = position + 1; i < messages.size() && i <= position + 2; i++) {
            final MessageObject message = messages.get(i);
            final TLRPC.Document document = TeleClipsMedia.playbackDocument(message);
            if (document == null || !wanted.contains(document.id) || owned.containsKey(document.id)) continue;
            final String name = FileLoader.getAttachFileName(document);
            if (loader.isLoadingFile(name) || loader.getPathToAttach(document).exists()) continue;
            // cacheType 10 uses FileLoadOperation's existing 2 MB video-preload budget.
            loader.loadFile(document, message, FileLoader.PRIORITY_LOW, 10);
            owned.put(document.id, document);
        }
    }

    private void cancelPrefix(TLRPC.Document document) {
        if (document == null) return;
        final FileLoader loader = FileLoader.getInstance(currentAccount);
        // Normal user downloads/active playback are represented in the UI map; prefixes aren't.
        if (!loader.isLoadingFile(FileLoader.getAttachFileName(document))) {
            loader.cancelLoadFile(document, false);
        }
    }

    public void clear() {
        for (TLRPC.Document document : owned.values()) cancelPrefix(document);
        owned.clear();
    }
}
