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
 * - 智能体消息事件: {"type":"channel","body":{"id":"1","type":"newAgentMessage","body":{...}}}
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
    @Volatile private var token: String = ""
    @Volatile private var origin: String = ""
    /**
     * 当前连接代数：start（身份变化）/stop 时递增。
     * 旧 socket 的回调携带建立时的代数，过期即整体失效，
     * 杜绝旧连接的 onClosed/onFailure 触发新一轮重连的竞态。
     */
    @Volatile private var generation = 0L
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
     * 启动原生 WS 连接（身份感知）：
     * - 同一 token+origin 且已在运行：幂等，直接返回；
     * - token/origin 变化（换账号/换实例）：取消旧连接与全部待执行回调，
     *   立即按新身份重建连接（旧 socket 回调因代数失效，不会触发重连竞态）。
     * @param token 用户认证 token
     * @param origin Misskey 实例 origin（如 https://misskey.example.com）
     */
    fun start(token: String, origin: String) {
        if (running.get() && token == this.token && origin == this.origin) {
            Log.d(TAG, "Already running with same identity, start is idempotent")
            return
        }
        if (running.get()) {
            Log.i(TAG, "Identity changed, restarting native WS connection")
            cancelCurrentConnection()
        } else {
            running.set(true)
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
        cancelCurrentConnection()
    }

    fun isRunning(): Boolean = running.get()

    /** 使当前 socket、心跳与待重连回调全部失效（不改变 running 状态）。 */
    private fun cancelCurrentConnection() {
        generation++
        mainHandler.removeCallbacks(heartbeatRunnable)
        mainHandler.removeCallbacks(reconnectRunnable)
        webSocket?.close(1000, "stopped or identity changed")
        webSocket = null
    }

    private fun connect() {
        if (!running.get()) return
        val wsOrigin = origin.replace("https://", "wss://").replace("http://", "ws://")
        val url = "$wsOrigin/streaming?i=$token&_t=${System.currentTimeMillis()}"
        Log.d(TAG, "Connecting to $wsOrigin/streaming")

        val request = Request.Builder().url(url).build()
        val myGeneration = generation
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            /** 回调所属 socket 已过期（stop/重启/被新连接替换）时整体失效。 */
            private fun isStale(ws: WebSocket): Boolean {
                val stale = !running.get() || generation != myGeneration || webSocket !== ws
                if (stale) runCatching { ws.close(1000, "stale callback") }
                return stale
            }

            override fun onOpen(ws: WebSocket, response: Response) {
                if (isStale(ws)) return
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
                ws.send(subscribe.toString())
                // 启动心跳（先清掉可能残留的调度，防止重复心跳）
                mainHandler.removeCallbacks(heartbeatRunnable)
                mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (isStale(ws)) return
                handleMessage(text)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (isStale(ws)) return
                Log.w(TAG, "WS closed: code=$code, reason=$reason")
                mainHandler.removeCallbacks(heartbeatRunnable)
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (isStale(ws)) return
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
                "newAgentMessage" -> handleAgentMessage(body.optJSONObject("body"))
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

    /**
     * 智能体消息事件（Misskey 定制渠道 newAgentMessage，与私信 newChatMessage 对等）。
     * payload: sessionId / sessionName / messageId / messageText / agentAvatarUrl
     */
    private fun handleAgentMessage(data: JSONObject?) {
        if (data == null) return
        val sessionName = data.strOrNull("sessionName") ?: "智能体"
        val text = data.strOrNull("messageText")?.take(200) ?: "[消息]"
        val sessionId = data.strOrNull("sessionId").orEmpty()

        Log.i(TAG, "AgentMessage from $sessionName: ${text.take(30)}")
        mainHandler.post { onNotification(sessionName, text, "/chat/agent/$sessionId") }
    }
}
