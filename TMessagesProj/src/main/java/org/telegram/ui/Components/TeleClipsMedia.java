package org.telegram.ui.Components;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

/** Shared publishing/playback limits, checked before any video transfer starts. */
public final class TeleClipsMedia {
    public static final String HASHTAG = "#TeleClips";
    public static final int MAX_SECONDS = 60;
    public static final long MAX_BYTES = 12L * 1024 * 1024;
    public static final int UPLOAD_MAX_SIDE = 1280;
    public static final int UPLOAD_BITRATE = 800_000;
    private static final java.util.regex.Pattern CLIP_TAG = java.util.regex.Pattern.compile("(?i)(?<![\\p{L}\\p{N}_])#teleclips(?![\\p{L}\\p{N}_])");

    private TeleClipsMedia() {}

    public static TLRPC.TL_documentAttributeVideo videoAttribute(TLRPC.Document document) {
        if (document == null) return null;
        for (TLRPC.DocumentAttribute attribute : document.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeVideo) {
                return (TLRPC.TL_documentAttributeVideo) attribute;
            }
        }
        return null;
    }

    public static boolean isShortVertical(TLRPC.Document document) {
        final TLRPC.TL_documentAttributeVideo video = videoAttribute(document);
        return video != null && !video.round_message && video.duration > 0 && video.duration <= MAX_SECONDS && video.w > 0 && video.h > video.w;
    }

    private static boolean isLightStream(TLRPC.Document document) {
        final TLRPC.TL_documentAttributeVideo video = videoAttribute(document);
        return isShortVertical(document) && document.size > 0 && document.size <= MAX_BYTES && video.supports_streaming && (long) video.w * video.h <= 1080L * 1920;
    }

    private static boolean isAvc(TLRPC.Document document) {
        final String codec = videoAttribute(document).video_codec;
        return codec == null || codec.isEmpty() || "h264".equalsIgnoreCase(codec) || "avc1".equalsIgnoreCase(codec);
    }

    public static TLRPC.Document playbackDocument(MessageObject message) {
        return message == null || message.messageOwner == null ? null : playbackDocument(message.messageOwner.media);
    }

    public static TLRPC.Document playbackDocument(TLRPC.MessageMedia media) {
        if (!(media instanceof TLRPC.TL_messageMediaDocument) || !isShortVertical(media.document)) return null;
        TLRPC.Document best = isLightStream(media.document) ? media.document : null;
        for (TLRPC.Document document : media.alt_documents) {
            if (!isLightStream(document)) continue;
            if (best == null || isAvc(document) && !isAvc(best) || isAvc(document) == isAvc(best) && document.size < best.size) {
                best = document;
            }
        }
        return best;
    }

    public static boolean hasClipTag(String caption) {
        return caption != null && CLIP_TAG.matcher(caption).find();
    }

    public static String publishingCaption(String caption) {
        final String text = caption == null ? "" : caption.trim();
        return hasClipTag(text) ? text : HASHTAG + (text.isEmpty() ? "" : "\n" + text);
    }
}
