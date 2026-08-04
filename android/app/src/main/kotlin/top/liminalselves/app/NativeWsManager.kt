package top.liminalselves.app

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 原生 WebSocket 管理器：绕过 WebView JS 节流，在后台独立维持 Misskey Streaming 连接。
 *
 * 协议要点：
 * - 连接: wss://<origin>/streaming?i=<token>&_t=<timestamp>
 * - 订阅 main channel: {"type":"connect","body":{"channel":"main","id":"1"}}
 * - 心跳: 每 60s 发送字符串 "h"
 * - 通知事件: {"type":"channel","body":{"id":"1","type":"notification","body":{...}}}
 * - 私信事件: {"type":"channel","body":{"id":"1","type":"newChatMessage","body":{...}}}
 */
class NativeWsManager(
    private val onNotification: (title: String, body: String, openPath: String?) -> Unit,
) {
    companion object {
        private const val TAG = "NativeWsManager"
        private const val CHANNEL_ID = "1"
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // WebSocket 长连接不设读超时
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val running = AtomicBoolean(false)
    private val reconnectDelay = AtomicInteger(INITIAL_RECONNECT_DELAY_MS.toInt())
    private var token: String = ""
    private var origin: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            webSocket?.send("h")
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    private val reconnectRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            Log.i(TAG, "Attempting reconnect...")
            connect()
        }
    }

    /**
     * 启动原生 WS 连接。
     * @param token 用户认证 token
     * @param origin Misskey 实例 origin（如 https://misskey.example.com）
     */
    fun start(token: String, origin: String) {
        if (running.getAndSet(true)) {
            Log.d(TAG, "Already running, skip start")
            return
        }
        this.token = token
        this.origin = origin
        reconnectDelay.set(INITIAL_RECONNECT_DELAY_MS.toInt())
        Log.i(TAG, "Starting native WS connection")
        connect()
    }

    /** 停止原生 WS 连接并清理资源。 */
    fun stop() {
        if (!running.getAndSet(false)) return
        Log.i(TAG, "Stopping native WS connection")
        mainHandler.removeCallbacks(heartbeatRunnable)
        mainHandler.removeCallbacks(reconnectRunnable)
        webSocket?.close(1000, "App resumed foreground")
        webSocket = null
    }

    fun isRunning(): Boolean = running.get()

    private fun connect() {
        if (!running.get()) return
        val wsOrigin = origin.replace("https://", "wss://").replace("http://", "ws://")
        val url = "$wsOrigin/streaming?i=$token&_t=${System.currentTimeMillis()}"
        Log.d(TAG, "Connecting to $wsOrigin/streaming")

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WS connected, subscribing to main channel")
                reconnectDelay.set(INITIAL_RECONNECT_DELAY_MS.toInt())
                // 订阅 main channel
                val subscribe = JSONObject().apply {
                    put("type", "connect")
                    put("body", JSONObject().apply {
                        put("channel", "main")
                        put("id", CHANNEL_ID)
                    })
                }
                webSocket.send(subscribe.toString())
                // 启动心跳
                mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WS closed: code=$code, reason=$reason")
                mainHandler.removeCallbacks(heartbeatRunnable)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS failure: ${t.message}")
                mainHandler.removeCallbacks(heartbeatRunnable)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!running.get()) return
        val delay = reconnectDelay.get().toLong()
        Log.i(TAG, "Scheduling reconnect in ${delay}ms")
        mainHandler.postDelayed(reconnectRunnable, delay)
        // 指数退避
        reconnectDelay.set((delay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS).toInt())
    }

    private fun handleMessage(text: String) {
        // 心跳响应是纯文本 "h"，忽略
        if (text == "h") return

        runCatching {
            val json = JSONObject(text)
            if (json.optString("type") != "channel") return
            val body = json.optJSONObject("body") ?: return
            if (body.optString("id") != CHANNEL_ID) return

            when (body.optString("type")) {
                "notification" -> handleNotification(body.optJSONObject("body"))
                "newChatMessage" -> handleChatMessage(body.optJSONObject("body"))
            }
        }.onFailure {
            Log.w(TAG, "Failed to parse WS message: ${it.message}")
        }
    }

    /**
     * 安全读取字符串字段：JSONObject.optString 对 JSON null 会返回字面量字符串 "null"，
     * 需额外过滤；空串也视为缺失。
     */
    private fun JSONObject.strOrNull(key: String): String? {
        val v = opt(key)
        if (v == null || v == JSONObject.NULL) return null
        val s = v.toString()
        return s.ifBlank { null }
    }

    /** 从 UserLite 对象提取显示名：优先 name，回退 username。 */
    private fun displayName(user: JSONObject?): String? {
        if (user == null) return null
        return user.strOrNull("name") ?: user.strOrNull("username")
    }

    private fun handleNotification(data: JSONObject?) {
        if (data == null) return
        val type = data.optString("type")
        val userName = displayName(data.optJSONObject("user")) ?: "未知用户"

        val (title, bodyText, openPath) = when (type) {
            "note", "mention", "reply", "quote", "renote" -> {
                val note = data.optJSONObject("note")
                val noteText = note?.strOrNull("text")?.take(100) ?: ""
                val noteId = note?.optString("id") ?: ""
                val prefix = when (type) {
                    "mention" -> "提到了你"
                    "reply" -> "回复了你"
                    "quote" -> "引用了你的帖子"
                    "renote" -> "转发了你的帖子"
                    else -> ""
                }
                Triple(userName, if (prefix.isNotEmpty()) "$prefix: $noteText" else noteText, "/notes/$noteId")
            }
            "reaction" -> {
                val note = data.optJSONObject("note")
                val reaction = data.strOrNull("reaction") ?: ""
                val noteId = note?.optString("id") ?: ""
                Triple(userName, "反应了 $reaction", "/notes/$noteId")
            }
            "follow" -> {
                val userId = data.optString("userId")
                Triple(userName, "关注了你", "/@$userId")
            }
            "receiveFollowRequest" -> {
                val userId = data.optString("userId")
                Triple(userName, "请求关注你", "/@$userId")
            }
            "followRequestAccepted" -> {
                val userId = data.optString("userId")
                Triple(userName, "接受了你的关注请求", "/@$userId")
            }
            "pollEnded" -> {
                val note = data.optJSONObject("note")
                val noteId = note?.optString("id") ?: ""
                Triple(userName, "投票已结束", "/notes/$noteId")
            }
            else -> {
                Log.d(TAG, "Unhandled notification type: $type")
                return
            }
        }

        Log.i(TAG, "Notification: title=$title, body=${bodyText.take(30)}")
        mainHandler.post { onNotification(title, bodyText, openPath) }
    }

    private fun handleChatMessage(data: JSONObject?) {
        if (data == null) return
        val senderName = displayName(data.optJSONObject("fromUser")) ?: "新消息"
        val text = data.strOrNull("text")?.take(200) ?: "[附件]"

        // 群聊消息：标题用群聊名，正文带发送者前缀，点击进群聊；私信：标题用发送者名，点击进私信会话
        val roomId = data.strOrNull("toRoomId")
        if (roomId != null) {
            val roomName = data.optJSONObject("toRoom")?.strOrNull("name") ?: "群聊"
            Log.i(TAG, "ChatRoomMessage [$roomName] from $senderName: ${text.take(30)}")
            mainHandler.post { onNotification(roomName, "$senderName: $text", "/chat/room/$roomId") }
        } else {
            val fromUserId = data.strOrNull("fromUserId").orEmpty()
            Log.i(TAG, "ChatMessage from $senderName: ${text.take(30)}")
            mainHandler.post { onNotification(senderName, text, "/chat/user/$fromUserId") }
        }
    }
}
