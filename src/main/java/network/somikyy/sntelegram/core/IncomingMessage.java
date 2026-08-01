/*
 * SNTelegram - part of the Somikyy Network plugin suite.
 * Copyright (C) 2026 Somikyy Network
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package network.somikyy.sntelegram.core;

import java.util.List;

/**
 * One Telegram message, reduced to what a Minecraft bridge cares about.
 *
 * <p>The two hard parts of this conversion are both traps that the Bot API documentation only
 * hints at, and both would produce a bridge that seems to work:
 *
 * <ol>
 *   <li><b>Every message in a forum topic looks like a reply.</b> Telegram attaches
 *       {@code reply_to_message} pointing at the service message that created the topic, so a
 *       naive bridge decides that every single line is a reply to something. Detection is exact:
 *       the topic's {@code message_thread_id} and the creating message's {@code message_id} are
 *       the same number, so an implicit reply is one where they match. The
 *       {@code forum_topic_created} field on the replied-to message confirms it independently,
 *       and both are checked.</li>
 *   <li><b>Service messages arrive as messages.</b> Creating, renaming, closing or reopening a
 *       topic all produce entries in {@code getUpdates} that would otherwise be forwarded into
 *       the game as empty chat lines.</li>
 * </ol>
 */
public record IncomingMessage(long messageId, Integer threadId, long chatId, long fromUserId,
                              String authorName, String authorUsername, String text,
                              List<TextSpan> spans, Long replyToMessageId, String mediaKind,
                              boolean service) {

    /** True when the message carries nothing to show in game. */
    public boolean empty() {
        return service || ((text == null || text.isBlank()) && mediaKind == null);
    }

    /** True when a human deliberately replied to a specific message. */
    public boolean isRealReply() {
        return replyToMessageId != null;
    }

    /**
     * Reads a Bot API {@code Message} object.
     *
     * @param message the {@code message} field of an update
     */
    public static IncomingMessage from(Json message) {
        long messageId = message.num("message_id", 0L);
        Integer threadId = message.has("message_thread_id")
                ? (int) message.num("message_thread_id", 0L)
                : null;
        long chatId = message.obj("chat").num("id", 0L);

        Json from = message.obj("from");
        long fromUserId = from.num("id", 0L);
        String first = from.str("first_name", "");
        String last = from.str("last_name", "");
        String username = from.str("username", "");
        String authorName = (first + " " + last).trim();
        if (authorName.isEmpty()) {
            // Channel posts and anonymous admins have no "from" - they carry sender_chat instead.
            authorName = message.obj("sender_chat").str("title", "");
        }
        if (authorName.isEmpty()) {
            authorName = username.isEmpty() ? "Telegram" : username;
        }

        String text = message.str("text", message.str("caption", ""));
        List<TextSpan> spans = TelegramText.spans(text,
                message.has("entities") ? message.arr("entities") : message.arr("caption_entities"));

        return new IncomingMessage(messageId, threadId, chatId, fromUserId, authorName, username,
                text, spans, realReplyTarget(message, threadId), mediaKind(message),
                isService(message));
    }

    /**
     * The message a human actually replied to, or {@code null}.
     *
     * <p>See the class comment: inside a forum topic, Telegram fills {@code reply_to_message} with
     * the topic-creation service message for every ordinary line. Treating that as a reply is
     * what makes a bridge announce "Вася ответил Васе" on every message.
     *
     * <p>One case stays ambiguous and cannot be resolved: a user may genuinely reply to the
     * "topic created" service message. The JSON is then identical to the implicit case, so this
     * reports "no reply". For a chat bridge that is harmless.
     */
    private static Long realReplyTarget(Json message, Integer threadId) {
        Json reply = message.obj("reply_to_message");
        if (reply.isNull()) {
            return null;
        }
        long replyId = reply.num("message_id", 0L);
        if (replyId <= 0) {
            return null;
        }
        if (threadId != null && replyId == threadId.longValue()) {
            return null; // the topic-binding artefact
        }
        if (reply.has("forum_topic_created")) {
            return null; // the same thing, confirmed the other way
        }
        return replyId;
    }

    /** A short Russian word for a non-text message, or {@code null} when there is none. */
    private static String mediaKind(Json message) {
        if (message.has("photo")) {
            return "фото";
        }
        if (message.has("sticker")) {
            return "стикер";
        }
        if (message.has("animation")) {
            return "гифка";
        }
        if (message.has("video")) {
            return "видео";
        }
        if (message.has("video_note")) {
            return "кружок";
        }
        if (message.has("voice")) {
            return "голосовое";
        }
        if (message.has("audio")) {
            return "аудио";
        }
        if (message.has("document")) {
            return "файл";
        }
        if (message.has("poll")) {
            return "опрос";
        }
        if (message.has("location") || message.has("venue")) {
            return "геопозиция";
        }
        if (message.has("contact")) {
            return "контакт";
        }
        if (message.has("dice")) {
            return "кубик";
        }
        return null;
    }

    /**
     * Service messages, which must never reach the game.
     *
     * <p>The forum-topic four are the ones a bridge meets daily; the rest arrive whenever anyone
     * administers the group. All of them have no {@code text}, so forwarding one shows players an
     * empty chat line from a stranger.
     */
    private static boolean isService(Json message) {
        return message.has("forum_topic_created")
                || message.has("forum_topic_edited")
                || message.has("forum_topic_closed")
                || message.has("forum_topic_reopened")
                || message.has("general_forum_topic_hidden")
                || message.has("general_forum_topic_unhidden")
                || message.has("new_chat_members")
                || message.has("left_chat_member")
                || message.has("new_chat_title")
                || message.has("new_chat_photo")
                || message.has("delete_chat_photo")
                || message.has("pinned_message")
                || message.has("message_auto_delete_timer_changed")
                || message.has("group_chat_created")
                || message.has("supergroup_chat_created")
                || message.has("migrate_to_chat_id")
                || message.has("migrate_from_chat_id");
    }
}
