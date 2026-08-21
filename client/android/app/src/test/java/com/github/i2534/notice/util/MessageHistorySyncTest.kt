package com.github.i2534.notice.util

import org.junit.Assert.*
import org.junit.Test

class MessageHistorySyncTest {

    @Test
    fun resolveHttpBaseUrl_prefersServerUrl() {
        assertEquals(
            "https://notice.example.com",
            MessageHistorySync.resolveHttpBaseUrl(
                serverUrl = "https://notice.example.com/",
                brokerUrl = "wss://other.example.com/ws"
            )
        )
    }

    @Test
    fun resolveHttpBaseUrl_derivesFromWssBroker() {
        assertEquals(
            "https://notice.9e6.xyz",
            MessageHistorySync.resolveHttpBaseUrl(
                serverUrl = "",
                brokerUrl = "wss://notice.9e6.xyz/ws"
            )
        )
    }

    @Test
    fun resolveHttpBaseUrl_derivesFromWsBroker() {
        assertEquals(
            "http://192.168.1.10:9090",
            MessageHistorySync.resolveHttpBaseUrl(
                serverUrl = "",
                brokerUrl = "ws://192.168.1.10:9090/ws"
            )
        )
    }

    @Test
    fun resolveHttpBaseUrl_returnsNullForBlank() {
        assertNull(MessageHistorySync.resolveHttpBaseUrl("", ""))
        assertNull(MessageHistorySync.resolveHttpBaseUrl("", "tcp://host:9091"))
    }

    @Test
    fun parseHistoryPage_readsMessagesAndHasMore() {
        val json = """
            {
              "success": true,
              "data": {
                "messages": [
                  {
                    "id": 12,
                    "topic": "notice/nga",
                    "title": "虾维斯",
                    "content": "hello",
                    "timestamp": "2026-07-14T07:00:02.56+08:00"
                  },
                  {
                    "id": 11,
                    "topic": "notice/quark",
                    "title": "夸克网盘签到结果",
                    "content": "ok",
                    "timestamp": "2026-07-14T08:02:27+08:00"
                  }
                ],
                "has_more": true,
                "next_id": 10,
                "page_size": 20,
                "total": 100
              }
            }
        """.trimIndent()

        val page = MessageHistorySync.parseHistoryPage(json)
        assertNotNull(page)
        assertEquals(2, page!!.messages.size)
        assertTrue(page.hasMore)
        assertEquals(10L, page.nextId)
        assertEquals("notice/nga", page.messages[0].topic)
        assertEquals("虾维斯", page.messages[0].title)
        assertEquals(12L, page.messages[0].id)
        assertTrue(page.messages[0].timestampMs > 0)
    }

    @Test
    fun filterMissed_keepsOnlyNewerAndNotDuplicateById() {
        val remote = listOf(
            RemoteHistoryMessage(3, "notice/a", "t3", "c3", 3000),
            RemoteHistoryMessage(2, "notice/a", "t2", "c2", 2000),
            RemoteHistoryMessage(1, "notice/a", "t1", "c1", 1000)
        )
        val missed = MessageHistorySync.filterMissed(
            remote = remote,
            afterTimestampMs = 1500,
            localIds = setOf("2", "hist-2"),
            localFingerprints = emptySet()
        )
        assertEquals(1, missed.size)
        assertEquals(3L, missed[0].id)
    }

    @Test
    fun filterMissed_dedupsByNormalizedFingerprintWhenIdMissingLocally() {
        val nested = """{"title":"t","content":"hello"}"""
        val remote = listOf(
            RemoteHistoryMessage(9, "notice/a", "t", nested, 3000)
        )
        val missed = MessageHistorySync.filterMissed(
            remote = remote,
            afterTimestampMs = 0,
            localIds = emptySet(),
            localFingerprints = setOf(
                MessageHistorySync.normalizedFingerprint("notice/a", "hello")
            )
        )
        assertTrue(missed.isEmpty())
    }

    @Test
    fun serverIdKeys_includesHistCompat() {
        assertEquals(setOf("12", "hist-12"), MessageHistorySync.serverIdKeys(12))
        assertTrue(MessageHistorySync.localHasServerId(setOf("hist-12"), 12))
        assertTrue(MessageHistorySync.localHasServerId(setOf("12"), 12))
    }

    @Test
    fun keepAliveDecision_reconnectWhenDisconnected() {
        assertEquals(
            KeepAliveAction.RECONNECT,
            KeepAliveDecision.decide(
                userDisconnected = false,
                stateConnected = false,
                mqttConnected = false
            )
        )
        assertEquals(
            KeepAliveAction.RECONNECT,
            KeepAliveDecision.decide(
                userDisconnected = false,
                stateConnected = false,
                mqttConnected = true
            )
        )
        assertEquals(
            KeepAliveAction.RECONNECT,
            KeepAliveDecision.decide(
                userDisconnected = false,
                stateConnected = true,
                mqttConnected = false
            )
        )
    }

    @Test
    fun keepAliveDecision_probeWhenLocalFlagsConnected() {
        // 本地标志 connected 不代表 TCP 真实连通（Paho isConnected 是本地标志），必须主动探活
        assertEquals(
            KeepAliveAction.PROBE,
            KeepAliveDecision.decide(
                userDisconnected = false,
                stateConnected = true,
                mqttConnected = true
            )
        )
    }

    @Test
    fun shouldForceReconnect_trueWhenConnectionOlderThanInterval() {
        val intervalMs = 12 * 60 * 60 * 1000L
        assertTrue(shouldForceReconnect(true, true, 1000L, 1000L + intervalMs, intervalMs))
        assertTrue(shouldForceReconnect(true, true, 1000L, 1000L + intervalMs + 1, intervalMs))
    }

    @Test
    fun shouldForceReconnect_falseWhenRecentOrNotConnected() {
        val intervalMs = 12 * 60 * 60 * 1000L
        assertFalse(shouldForceReconnect(true, true, 1000L, 1000L + intervalMs - 1, intervalMs))
        assertFalse(shouldForceReconnect(false, true, 1000L, 999999999L, intervalMs))
        assertFalse(shouldForceReconnect(true, false, 1000L, 999999999L, intervalMs))
    }

    @Test
    fun unwrapNestedNoticeContent_expandsHermesDoubleWrappedPayload() {
        val nested = """{"content":"⚠️ 语音转写未配置","client":"hermes","timestamp":1710000000000,"extra":{"toUser":"android"}}"""
        val unwrapped = MessageHistorySync.unwrapNestedNoticeContent(nested)
        assertNotNull(unwrapped)
        assertEquals("⚠️ 语音转写未配置", unwrapped!!.content)
        assertEquals("hermes", unwrapped.client)
        assertNull(unwrapped.title)
    }

    @Test
    fun unwrapNestedNoticeContent_expandsTitleAndContent() {
        val nested = """{"title":"中文标题","content":"中文内容"}"""
        val unwrapped = MessageHistorySync.unwrapNestedNoticeContent(nested)
        assertNotNull(unwrapped)
        assertEquals("中文标题", unwrapped!!.title)
        assertEquals("中文内容", unwrapped.content)
    }

    @Test
    fun unwrapNestedNoticeContent_ignoresPlainTextAndNonEnvelopeJson() {
        assertNull(MessageHistorySync.unwrapNestedNoticeContent("plain text"))
        assertNull(MessageHistorySync.unwrapNestedNoticeContent("{not json}"))
        assertNull(MessageHistorySync.unwrapNestedNoticeContent("""{"type":"asr_confirm","text":"hi"}"""))
        assertNull(MessageHistorySync.unwrapNestedNoticeContent("short"))
    }
}
