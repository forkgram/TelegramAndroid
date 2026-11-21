package org.telegram.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.telegram.messenger.support.LongSparseIntArray
import org.telegram.tgnet.TLRPC

class BlockedGroupMessageTest {
    private val peers = LongSparseIntArray()
    private val group = TLRPC.TL_chat()
    private val authorId = 5_000_000_000L
    private val message = TLRPC.TL_message().apply {
        from_id = TLRPC.TL_peerUser().apply { user_id = authorId }
    }

    @Test
    fun groupHistoryUsesCurrentBlockList() {
        val supergroup = TLRPC.TL_channel().apply { megagroup = true }
        assertFalse(isHidden(group))

        peers.put(authorId, 1)
        assertTrue(isHidden(group))
        assertTrue(isHidden(supergroup))

        peers.delete(authorId)
        assertFalse(isHidden(group))
        assertFalse(isHidden(supergroup))
    }

    @Test
    fun otherChatsAndNonUserSendersStayVisible() {
        peers.put(authorId, 1)
        assertFalse(isHidden(null))
        assertFalse(isHidden(TLRPC.TL_channel()))
        assertFalse(ChatActivity.isBlockedGroupMessage(ChatActivity.MODE_SEARCH, group, message, peers))

        peers.put(-authorId, 1)
        message.from_id = TLRPC.TL_peerChannel().apply { channel_id = authorId }
        assertFalse(isHidden(group))

        message.from_id = null
        assertFalse(isHidden(group))
    }

    private fun isHidden(chat: TLRPC.Chat?) =
        ChatActivity.isBlockedGroupMessage(ChatActivity.MODE_DEFAULT, chat, message, peers)
}
