package com.cardesk.launcher.ui

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.webkit.MimeTypeMap
import com.cardesk.launcher.core.log.LogTags
import com.cardesk.launcher.core.log.Logx
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 局域网传输：沿用 App 的「车机内置 HTTP 服务」。
 *
 * 行为
 *
 *  - 端口顺序探测 18765..18775，绑定 0.0.0.0、backlog 16（L53-54、L991-1009、L993）
 *  - 访问凭据：URL 查询参数 `t=<token>`，缺省时回退到请求头 `x-lan-token`（L377-389）
 *  - 路由：`GET /`、`GET /index.html`、`GET /download/<name>`、`GET /preview/<name>`、`POST /upload`（L390-419）
 *  - 上传必须是 `multipart/form-data`，且必须带 `Content-Length`（L437-468）
 *  - 单文件上限 512MB（L52、L469-472、L523）
 *  - 先写 `.uploading` 临时文件，成功后再改名为正式文件（L517-529）
 *  - 下载/预览响应带 `X-Content-Type-Options: nosniff`（L918）
 *  - 文件名净化与目录页 HTML 转义（L877-897、L222-224）
 *
 * 实现说明：不引入任何第三方依赖；`com.sun.net.httpserver` 不在 Android API 里，
 * 因此按一样用裸 Socket 手写 HTTP 解析。
 */
object LanTransferServer {

    /** 单文件上限 512MB（L52）。 */
    private const val MAX_UPLOAD_BYTES = 536870912L

    /** 端口探测区间（L53-54）。 */
    private const val PORT_FIRST = 18765
    private const val PORT_LAST = 18775

    /**
     * 传输目录名（放在公共 Download 目录下面）。
     *
     * 第六十九轮：传输目录从「应用私有目录」
     * （`/storage/emulated/0/Android/data/com.ecarx.cardesk/files/lan-transfer`）
     * 改到 **公共 Download 目录** —— 私有目录在文件管理器里很难找，
     * 推过去的安装包还得再拷一次才能装。
     *
     * 放在 `Download/CarDesk/` 而不是 `Download/` 根下：
     * 这样局域网里能看到的只有 CarDesk 自己的文件，
     * 不会把用户自己在 Download 里下的东西一并暴露出去。
     * （诊断包导出用的也是这个目录，见 `core/log/Diagnostics.kt`。）
     */
    private const val SHARE_DIR_NAME = "CarDesk"

    /** 单连接超时 120s（L302）。 */
    private const val SOCKET_TIMEOUT_MS = 120000

    /** 请求头上限 64KB（L330）。 */
    private const val MAX_HEADER_BYTES = 65536

    /** 目录页最多列出 40 个文件（L131）。 */
    private const val MAX_LIST_ENTRIES = 40

    /** 文件名长度上限 180（L891）。 */
    private const val MAX_NAME_LENGTH = 180

    /** 下载流式拷贝缓冲 64KB（L928）。 */
    private const val DOWNLOAD_BUFFER_BYTES = 65536

    private var server: ServerSocket? = null
    private var worker: Thread? = null
    private var errorCallback: ((String) -> Unit)? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** 访问令牌，18 位十六进制（L780-788）。 */
    @Volatile
    var token: String = ""
        private set

    /** 实际监听端口（L745-747）。 */
    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var running: Boolean = false
        private set

    /** 传输状态回调（Listener.onStatus，L67-69、L749-755），在主线程回调。 */
    @Volatile
    var statusListener: ((String) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---------------------------------------------------------------- 生命周期

    /**
 * 传输目录：公共 Download 目录下的 CarDesk 子目录。
 *
 * 第六十九轮从应用私有目录（`getExternalFilesDir`）搬到了公共 Download 目录。
 *
 * 权限：Android 9 及以下靠 `WRITE_EXTERNAL_STORAGE`（主 manifest 里已声明，
 * `maxSdkVersion=28`）；system 变体是 uid 1000，不受 scoped storage 限制。
 */
    private fun shareDirOf(context: Context): File {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(base, SHARE_DIR_NAME)
    }

    /** 共享目录（不存在则创建），供设置界面列举文件使用。 */
    fun rootDir(context: Context): File {
        val dir = shareDirOf(context)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 共享目录绝对路径（L948-951）。 */
    fun sharedDirectoryPath(context: Context): String = shareDirOf(context).absolutePath

    /** 启动服务；成功回调 URL（形如 http://192.168.43.1:18765/?t=abc）。 */
    fun start(context: Context, onStarted: (String) -> Unit, onError: (String) -> Unit) {
        errorCallback = onError
        if (running) {
            // 排障场景：重复点「启动」时服务已在跑，这里把当前地址再报一次
            Logx.i(LogTags.LAN, "局域网传输已在运行", "url" to url(context), "port" to port)
            onStarted(url(context))
            return
        }
        token = randomToken()
        val shareDir = shareDirOf(context)
        if (!shareDir.exists() && !shareDir.mkdirs()) {
            // 排障场景：服务起不来 —— 共享目录建不出来（存储权限/路径问题）
            Logx.w(LogTags.LAN, "启动失败：无法创建传输目录", "dir" to shareDir.absolutePath)
            onError("无法创建传输目录")
            return
        }
        var socket: ServerSocket? = null
        var lastError: String? = null
        for (p in PORT_FIRST..PORT_LAST) {
            try {
                socket = ServerSocket(p, 16, InetAddress.getByName("0.0.0.0"))
                port = p
                break
            } catch (e: Exception) {
                lastError = e.message
                // 排障场景：端口被占用是常见问题，逐个端口记下来
                Logx.d(LogTags.LAN, "端口探测失败", "port" to p, "reason" to e.message)
            }
        }
        if (socket == null) {
            // 排障场景：18765..18775 全被占用
            Logx.w(
                LogTags.LAN,
                "启动失败：端口区间被占满",
                "portFirst" to PORT_FIRST,
                "portLast" to PORT_LAST,
                "reason" to (lastError ?: ""),
            )
            onError("端口占用，无法启动：" + (lastError ?: ""))
            return
        }
        server = socket
        running = true
        acquireTransferLocks(context)
        worker = thread(isDaemon = true, name = "LanTransferHttp") {
            acceptLoop(socket, shareDir)
        }
        // 排障场景：电脑连不上 —— 监听地址、端口、token、目录都要能对得上
        Logx.i(
            LogTags.LAN,
            "局域网传输已启动",
            "bind" to "0.0.0.0",
            "port" to port,
            "token" to token,
            "dir" to shareDir.absolutePath,
            "url" to url(context),
        )
        postStatus(if (resolveLanIp(context).isEmpty()) "服务已开，等待热点 IP…" else "服务已开，等待手机扫码…")
        onStarted(url(context))
    }

    fun stop() {
        val wasRunning = running
        running = false
        releaseTransferLocks()
        token = ""
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        worker = null
        // 排障场景：服务被谁停掉的（手动点停 / accept 异常自停）
        Logx.i(LogTags.LAN, "局域网传输已停止", "wasRunning" to wasRunning)
    }

    /** 浏览器可用地址（L826-838、L834）。 */
    fun url(context: Context): String {
        val ip = resolveLanIp(context)
        val host = if (ip.isEmpty()) "127.0.0.1" else ip
        return "http://$host:$port/?t=$token"
    }

    /** 已接收文件列表：排除 `.uploading` 临时文件，按修改时间倒序（L675-689）。 */
    fun listSharedFiles(context: Context): List<File> {
        return rootDir(context).listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".uploading") }
            .sortedByDescending { it.lastModified() }
    }

    /** 删除已接收文件：仅允许删除共享目录下的普通文件（L206-216）。 */
    fun deleteSharedFile(context: Context, file: File): Boolean {
        if (file.name.endsWith(".uploading")) {
            // 排障场景：正在上传的临时文件不允许删
            Logx.w(LogTags.LAN, "删除文件失败：上传中的临时文件", "name" to file.name)
            return false
        }
        val ok = try {
            val dir = rootDir(context).canonicalFile
            dir == file.canonicalFile.parentFile && file.isFile && file.delete()
        } catch (e: IOException) {
            // 排障场景：路径越界或 IO 异常
            Logx.w(LogTags.LAN, "删除文件失败：路径非法或 IO 异常", e, "name" to file.name)
            false
        }
        // 排障场景：删除没生效
        if (ok) {
            Logx.i(LogTags.LAN, "已删除共享文件", "name" to file.name, "bytes" to file.length())
        } else {
            Logx.w(LogTags.LAN, "删除共享文件失败", "name" to file.name)
        }
        return ok
    }

    // ---------------------------------------------------------------- 网络

    /** 局域网 IPv4 解析（L226-286、L859-875）。 */
    private fun resolveLanIp(context: Context): String {
        firstIpv4Matching(arrayOf("ap0", "wlan1", "softap", "swlan")).let { if (it.isNotEmpty()) return it }
        firstPrivateIpv4().let { if (it.isNotEmpty()) return it }
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = wm?.connectionInfo?.ipAddress ?: 0
            if (ip == 0) "" else formatIpAddress(ip)
        } catch (e: Throwable) {
            ""
        }
    }

    /** 优先取热点/无线网卡的私有 IPv4（L226-254）。 */
    private fun firstIpv4Matching(keys: Array<String>): String {
        return try {
            val nics = NetworkInterface.getNetworkInterfaces() ?: return ""
            while (nics.hasMoreElements()) {
                val nic = nics.nextElement() ?: continue
                if (!nic.isUp || nic.isLoopback) continue
                val name = nic.name?.lowercase(Locale.US) ?: ""
                if (keys.none { name.contains(it) }) continue
                val ip = ipv4Of(nic)
                if (isPrivateIpv4(ip)) return ip
            }
            ""
        } catch (e: Throwable) {
            ""
        }
    }

    /** 回退：任一私有 IPv4，优先 192.168.43/42/12.x 的.1 网关（L256-286）。 */
    private fun firstPrivateIpv4(): String {
        return try {
            val nics = NetworkInterface.getNetworkInterfaces() ?: return ""
            val list = ArrayList<String>()
            while (nics.hasMoreElements()) {
                val nic = nics.nextElement() ?: continue
                if (!nic.isUp || nic.isLoopback) continue
                val ip = ipv4Of(nic)
                if (ip.isEmpty()) continue
                val name = nic.name?.lowercase(Locale.US) ?: ""
                if (name.contains("tun") || name.contains("vpn") ||
                    name.contains("rmnet") || name.contains("ccmni")
                ) continue
                if (isPrivateIpv4(ip)) list.add(ip)
            }
            for (ip in list) {
                if (ip.endsWith(".1") &&
                    (ip.startsWith("192.168.43.") || ip.startsWith("192.168.42.") || ip.startsWith("192.168.12."))
                ) {
                    return ip
                }
            }
            list.firstOrNull() ?: ""
        } catch (e: Throwable) {
            ""
        }
    }

    /** 网卡第一个非回环 IPv4（L624-633）。 */
    private fun ipv4Of(nic: NetworkInterface): String {
        val addresses = nic.inetAddresses
        while (addresses.hasMoreElements()) {
            val addr = addresses.nextElement()
            if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress.orEmpty()
        }
        return ""
    }

    /** 私有网段判定（L635-651）。 */
    private fun isPrivateIpv4(ip: String?): Boolean {
        if (ip == null) return false
        if (ip.startsWith("192.168.") || ip.startsWith("10.")) return true
        if (!ip.startsWith("172.")) return false
        return try {
            val second = ip.split(".")[1].toInt()
            second in 16..31
        } catch (e: Exception) {
            false
        }
    }

    /** WifiManager 返回的是小端 int（L871 的 Formatter.formatIpAddress 等价实现）。 */
    private fun formatIpAddress(ip: Int): String {
        return String.format(
            Locale.US, "%d.%d.%d.%d",
            ip and 0xff, (ip shr 8) and 0xff, (ip shr 16) and 0xff, (ip shr 24) and 0xff
        )
    }

    /** 传输期间持有唤醒锁与 WiFi 锁（L96-113、L840-857）。 */
    private fun acquireTransferLocks(context: Context) {
        try {
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lynkco:LanTransfer")
                wakeLock?.acquire()
            }
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "Lynkco:LanTransfer")
                wifiLock?.acquire()
            }
        } catch (e: Throwable) {
            // 缺少 WAKE_LOCK / CHANGE_WIFI_STATE 权限时忽略（L110-112 同样只记日志）
        }
    }

    private fun releaseTransferLocks() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Throwable) {
        }
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (e: Throwable) {
        }
        wakeLock = null
        wifiLock = null
    }

    // ---------------------------------------------------------------- 服务循环

    private fun acceptLoop(socket: ServerSocket, shareDir: File) {
        while (running && socket === server) {
            try {
                val client = socket.accept()
                // 排障场景：电脑连不上时，先确认车机这边有没有收到连接
                Logx.d(
                    LogTags.LAN,
                    "客户端已连接",
                    "remote" to client.remoteSocketAddress?.toString(),
                    "port" to port,
                )
                thread(isDaemon = true, name = "LanTransfer-worker") {
                    handleClient(client, shareDir)
                }
            } catch (e: Exception) {
                if (running) {
                    // 排障场景：服务自己挂了（socket 被关、端口被抢）
                    Logx.e(LogTags.LAN, "服务循环异常，服务即将停止", e, "port" to port)
                    errorCallback?.invoke("服务异常：" + (e.message ?: ""))
                    stop()
                } else {
                    Logx.d(LogTags.LAN, "服务循环结束（正常停止）", "port" to port)
                }
                break
            }
        }
    }

    private fun handleClient(client: Socket, shareDir: File) {
        var out: OutputStream? = null
        // 排障场景：一次请求的完整耗时（慢在哪一步）
        val started = System.currentTimeMillis()
        var reqDesc = "-"
        var code = 0
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            out = output

            // 读请求头（L305-340）：超过 64KB 直接 413。
            val head = ByteArrayOutputStream(2048)
            var prev = 0
            while (true) {
                val b = input.read()
                if (b < 0) {
                    // 排障场景：客户端连上就断了（没发请求头）
                    Logx.w(
                        LogTags.LAN,
                        "连接中断：客户端未发送完整请求头",
                        "remote" to client.remoteSocketAddress?.toString(),
                    )
                    return
                }
                head.write(b)
                if (prev == 10 && b == 10) break
                if (prev == 10 && b == 13) {
                    val n = input.read()
                    if (n == 10) {
                        head.write(n)
                        break
                    } else if (n >= 0) {
                        head.write(n)
                    }
                }
                if (head.size() > MAX_HEADER_BYTES) {
                    code = 413
                    writeResponse(output, 413, "text/plain; charset=utf-8", "header too large")
                    return
                }
                prev = b
            }

            val lines = head.toString("ISO-8859-1").split(Regex("\r?\n"))
            val parts = (lines.firstOrNull() ?: "").split(" ")
            if (parts.size < 2) {
                code = 400
                writeResponse(output, 400, "text/plain; charset=utf-8", "bad request")
                return
            }
            val method = parts[0].uppercase(Locale.US)
            val target = parts[1]
            reqDesc = "$method $target"
            val headers = HashMap<String, String>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                val idx = line.indexOf(':')
                if (line.isNotEmpty() && idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase(Locale.US)] =
                        line.substring(idx + 1).trim()
                }
            }

            var path = target
            var query = ""
            val qIdx = target.indexOf('?')
            if (qIdx >= 0) {
                path = target.substring(0, qIdx)
                query = target.substring(qIdx + 1)
            }
            var t = parseQuery(query)["t"]
            val tokenFromQuery = !t.isNullOrEmpty()
            if (t.isNullOrEmpty()) t = headers["x-lan-token"]
            // 排障场景：电脑打开地址没反应 —— 先看请求到底发了什么
            Logx.i(
                LogTags.LAN,
                "收到请求",
                "method" to method,
                "path" to path,
                "tokenInQuery" to tokenFromQuery,
                "remote" to client.remoteSocketAddress?.toString(),
            )
            if (token != t) {
                // 排障场景：鉴权失败 —— 地址里的 token 与车机当前 token 不一致（多半是服务重启过）
                code = 403
                Logx.w(
                    LogTags.LAN,
                    "鉴权失败：token 不匹配",
                    "method" to method,
                    "path" to path,
                    "sentToken" to (t ?: "<none>"),
                    "expectedToken" to token,
                    "tokenInQuery" to tokenFromQuery,
                )
                writeResponse(output, 403, "text/plain; charset=utf-8", "forbidden", reqDesc)
                return
            }

            when {
                method == "GET" && (path == "/" || path == "/index.html") -> {
                    code = 200
                    postStatus("手机已打开传输页")
                    writeResponse(output, 200, "text/html; charset=utf-8", buildIndexHtml(shareDir), reqDesc)
                }
                method == "GET" && path.startsWith("/download/") -> {
                    code = serveDownload(
                        output,
                        shareDir,
                        URLDecoder.decode(path.substring(10), "UTF-8"),
                        false,
                        reqDesc,
                    )
                }
                method == "GET" && path.startsWith("/preview/") -> {
                    code = serveDownload(
                        output,
                        shareDir,
                        URLDecoder.decode(path.substring(9), "UTF-8"),
                        true,
                        reqDesc,
                    )
                }
                method == "POST" && path == "/upload" -> {
                    code = handleUpload(input, headers, output, shareDir, reqDesc)
                }
                else -> {
                    code = 404
                    writeResponse(output, 404, "text/plain; charset=utf-8", "not found", reqDesc)
                }
            }
        } catch (e: Throwable) {
            // 排障场景：断连 / 超时 / 客户端提前断开 —— 一定要留异常
            Logx.w(
                LogTags.LAN,
                "请求处理异常",
                e,
                "req" to reqDesc,
                "elapsedMs" to (System.currentTimeMillis() - started),
                "reason" to (e.message ?: e.javaClass.simpleName),
            )
            try {
                out?.let {
                    writeResponse(
                        it, 500, "text/plain; charset=utf-8",
                        "Request failed; please refresh. See system diagnostics for details.",
                        reqDesc,
                    )
                }
            } catch (_: Throwable) {
            }
        } finally {
            // 排障场景：一次请求的响应码与耗时
            Logx.i(
                LogTags.LAN,
                "请求结束",
                "req" to reqDesc,
                "code" to code,
                "elapsedMs" to (System.currentTimeMillis() - started),
            )
            try {
                client.close()
            } catch (_: Throwable) {
            }
        }
    }

    /** 目录页（L119-165）。 */
    private fun buildIndexHtml(shareDir: File): String {
        val rows = StringBuilder()
        val files = shareDir.listFiles()?.toMutableList() ?: ArrayList()
        files.sortWith { a, b -> b.lastModified().compareTo(a.lastModified()) }
        var count = 0
        for (f in files) {
            if (!f.isFile || f.name.endsWith(".uploading")) continue
            if (count >= MAX_LIST_ENTRIES) break
            val enc = urlEncode(f.name)
            rows.append("<li><a href=\"/download/").append(enc).append("?t=").append(token)
                .append("\">").append(escapeHtml(f.name)).append("</a> <span class=meta>")
                .append(formatSize(f.length())).append("</span> <a href=\"/preview/").append(enc)
                .append("?t=").append(token).append("\">预览</a></li>")
            count++
        }
        if (rows.isEmpty()) rows.append("<li class=meta>暂无文件</li>")
        return """<!DOCTYPE html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><title>局域网传输</title><style>body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;background:#111;color:#eee;margin:0;padding:20px;}h1{font-size:20px;margin:16px 0 8px;}.card{background:#1c1c1e;border-radius:12px;padding:16px;margin-bottom:16px;}button,.btn{background:#0a84ff;color:#fff;border:0;border-radius:10px;padding:12px 18px;font-size:16px;}input[type=file]{width:100%;margin:12px 0;color:#ccc;}li{margin:8px 0;} a{color:#64d2ff;}.meta{color:#888;font-size:13px;}</style></head><body><div class=card><h1>传文件到车机</h1><form method=post action="/upload?t=$token" enctype="multipart/form-data"><input type=file name=file multiple><button type=submit>上传</button></form><p class=meta>单文件最大 512MB；APK 上传后请在车机对话框列表里点选安装</p></div><div class=card><h1>从车机下载</h1><ul>$rows</ul></div></body></html>"""
    }

    /** 下载 / 预览（L899-946）。 */
    private fun serveDownload(
        output: OutputStream,
        shareDir: File,
        rawName: String,
        preview: Boolean,
        req: String,
    ): Int {
        val safeName = sanitizeName(rawName)
        val file = File(shareDir, safeName)
        if (!file.isFile || !file.canonicalPath.startsWith(shareDir.canonicalPath)) {
            // 排障场景：路径穿越（文件名里带..）被拦截
            Logx.w(
                LogTags.LAN,
                "拦截非法下载路径",
                "req" to req,
                "rawName" to rawName,
                "safeName" to safeName,
                "canonical" to runCatching { file.canonicalPath }.getOrNull(),
            )
            writeResponse(output, 404, "text/plain; charset=utf-8", "not found", req)
            return 404
        }
        var mime = mimeTypeOf(file.name)
        val inline = preview && mime != null && isPreviewable(mime)
        if (!inline) mime = "application/octet-stream"
        postStatus("正在下载：" + file.name)
        // 排障场景：下载慢/中断 —— 记录文件名、大小与耗时
        val started = System.currentTimeMillis()
        Logx.i(
            LogTags.LAN,
            "下载开始",
            "name" to file.name,
            "bytes" to file.length(),
            "preview" to preview,
            "req" to req,
        )
        val head = StringBuilder()
        head.append("HTTP/1.1 200 OK\r\nContent-Type: ").append(mime)
            .append("\r\nX-Content-Type-Options: nosniff\r\nContent-Length: ").append(file.length())
            .append("\r\nContent-Disposition: ").append(if (inline) "inline" else "attachment")
            .append("; filename*=UTF-8''").append(urlEncode(file.name))
            .append("\r\nConnection: close\r\n\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        try {
            file.inputStream().use { input ->
                val buf = ByteArray(DOWNLOAD_BUFFER_BYTES)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                }
            }
            output.flush()
            Logx.i(
                LogTags.LAN,
                "下载完成",
                "name" to file.name,
                "bytes" to file.length(),
                "elapsedMs" to (System.currentTimeMillis() - started),
            )
            return 200
        } catch (e: Throwable) {
            // 排障场景：下载过程中断连
            Logx.w(
                LogTags.LAN,
                "下载中断",
                e,
                "name" to file.name,
                "elapsedMs" to (System.currentTimeMillis() - started),
            )
            throw e
        }
    }

    /** 允许内联预览的 MIME（L909-912）。 */
    private fun isPreviewable(mime: String): Boolean {
        return (mime.startsWith("image/") && mime != "image/svg+xml") ||
            mime.startsWith("audio/") ||
            mime.startsWith("video/") ||
            mime == "application/pdf" ||
            mime == "text/plain"
    }

    /** 扩展名 → MIME（L909）。 */
    private fun mimeTypeOf(fileName: String): String? {
        val ext = fileName.substring(fileName.lastIndexOf('.') + 1).lowercase(Locale.US)
        return try {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        } catch (e: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- 上传

    /** multipart/form-data 上传（L437-601）。 */
    private fun handleUpload(
        input: InputStream,
        headers: Map<String, String>,
        output: OutputStream,
        shareDir: File,
        req: String,
    ): Int {
        val contentType = headers["content-type"]
        if (contentType == null || !contentType.lowercase(Locale.US).contains("multipart/form-data")) {
            // 排障场景：上传报 400 —— 请求不是 multipart/form-data
            Logx.w(LogTags.LAN, "上传失败：缺少 multipart/form-data", "req" to req, "contentType" to contentType)
            writeResponse(output, 400, "text/plain; charset=utf-8", "need multipart", req)
            return 400
        }
        var boundary: String? = null
        for (piece in contentType.split(";")) {
            val p = piece.trim()
            if (p.lowercase(Locale.US).startsWith("boundary=")) {
                var b = p.substring(9).trim()
                if (b.length >= 2 && b.startsWith("\"") && b.endsWith("\"")) {
                    b = b.substring(1, b.length - 1)
                }
                boundary = b
            }
        }
        if (boundary.isNullOrEmpty()) {
            Logx.w(LogTags.LAN, "上传失败：缺少 boundary", "req" to req, "contentType" to contentType)
            writeResponse(output, 400, "text/plain; charset=utf-8", "no boundary", req)
            return 400
        }
        val contentLength = headers["content-length"]?.trim()?.toLongOrNull() ?: -1L
        if (contentLength <= 0L || headers.containsKey("transfer-encoding")) {
            // 排障场景：上传报 400 —— 没有 Content-Length（chunked 传输不被支持）
            Logx.w(
                LogTags.LAN,
                "上传失败：需要 Content-Length",
                "req" to req,
                "contentLength" to contentLength,
                "chunked" to headers.containsKey("transfer-encoding"),
            )
            writeResponse(output, 400, "text/plain; charset=utf-8", "Content-Length required", req)
            return 400
        }
        if (contentLength > MAX_UPLOAD_BYTES) {
            // 排障场景：文件超过 512MB 上限
            Logx.w(
                LogTags.LAN,
                "上传失败：文件超过上限",
                "req" to req,
                "contentLength" to contentLength,
                "max" to MAX_UPLOAD_BYTES,
            )
            writeResponse(output, 413, "text/plain; charset=utf-8", "file too large", req)
            return 413
        }
        if (headers["expect"].equals("100-continue", ignoreCase = true)) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }

        val body = LimitedInputStream(input, contentLength)
        val boundaryBytes = ("--" + boundary).toByteArray(Charsets.ISO_8859_1)
        if (!skipUntil(body, boundaryBytes)) {
            Logx.w(LogTags.LAN, "上传失败：multipart 内容格式错误", "req" to req)
            writeResponse(output, 400, "text/plain; charset=utf-8", "bad multipart", req)
            return 400
        }
        readLineIso(body)

        val saved = ArrayList<String>()
        val uploadStarted = System.currentTimeMillis()
        while (true) {
            val partHeaders = readHeaders(body)
            if (partHeaders.isEmpty()) break
            val filename = parseFilename(partHeaders["content-disposition"])
            if (!filename.isNullOrEmpty()) {
                val safeName = sanitizeName(filename)
                val target = uniqueFile(shareDir, safeName)
                val temp = File.createTempFile("receive-", ".uploading", shareDir)
                // 排障场景：上传没动静 —— 开始落盘前先记一条
                Logx.i(
                    LogTags.LAN,
                    "上传开始",
                    "name" to safeName,
                    "rawName" to filename,
                    "contentLength" to contentLength,
                    "req" to req,
                )
                val partStarted = System.currentTimeMillis()
                try {
                    val written = FileOutputStream(temp).use { fos ->
                        BufferedOutputStream(fos).use { bos ->
                            copyPartBody(body, bos, boundaryBytes, MAX_UPLOAD_BYTES)
                        }
                    }
                    if (written > 0L && !temp.renameTo(target)) {
                        temp.delete()
                        throw IOException("Cannot save received file")
                    }
                    if (written <= 0L) temp.delete()
                    if (written < 0L) {
                        target.delete()
                        Logx.w(
                            LogTags.LAN,
                            "上传失败：文件超过 512MB 上限",
                            "name" to safeName,
                            "elapsedMs" to (System.currentTimeMillis() - partStarted),
                        )
                        writeResponse(
                            output, 413, "text/html; charset=utf-8",
                            htmlMsg("文件过大（上限 512MB）", false),
                            req,
                        )
                        return 413
                    }
                    if (written == 0L) {
                        target.delete()
                        Logx.w(LogTags.LAN, "上传失败：收到空文件", "name" to safeName)
                    } else {
                        saved.add(target.name + " (" + formatSize(written) + ")")
                        // 排障场景：文件传上来了没 —— 记文件名、大小、耗时
                        Logx.i(
                            LogTags.LAN,
                            "上传完成",
                            "name" to target.name,
                            "bytes" to written,
                            "elapsedMs" to (System.currentTimeMillis() - partStarted),
                            "req" to req,
                        )
                    }
                    val b1 = body.read()
                    val b2 = body.read()
                    if ((b1 == 45 && b2 == 45) || b1 < 0) break
                } catch (e: Exception) {
                    temp.delete()
                    // 排障场景：落盘失败（存储满、权限、文件名冲突等）
                    Logx.e(
                        LogTags.LAN,
                        "上传失败：写入文件时异常",
                        e,
                        "name" to safeName,
                        "elapsedMs" to (System.currentTimeMillis() - partStarted),
                    )
                    throw e
                }
            } else {
                if (!drainPart(body, boundaryBytes)) break
                val b1 = body.read()
                val b2 = body.read()
                if (b1 == 45 && b2 == 45) break
                else if (b1 != 13 || b2 != 10) throw IOException("Invalid multipart separator")
            }
        }
        // 丢弃剩余请求体（L582-583）
        while (body.read() != -1) {
        }

        if (saved.isEmpty()) {
            // 排障场景：上传返回失败但浏览器没提示 —— 这里记明是空文件
            Logx.w(
                LogTags.LAN,
                "上传失败：没有收到任何文件",
                "req" to req,
                "elapsedMs" to (System.currentTimeMillis() - uploadStarted),
            )
            writeResponse(output, 400, "text/html; charset=utf-8", htmlMsg("未收到文件，请重选后上传", false), req)
            postStatus("上传失败：空文件")
            return 400
        }
        val summary = "上传成功：" + saved.joinToString("、")
        postStatus(summary)
        Logx.i(
            LogTags.LAN,
            "上传批次完成",
            "files" to saved.joinToString(","),
            "count" to saved.size,
            "elapsedMs" to (System.currentTimeMillis() - uploadStarted),
        )
        writeResponse(
            output, 200, "text/html; charset=utf-8",
            htmlMsg(summary + "<br><a href=\"/?t=" + token + "\">返回</a>", true),
            req,
        )
        return 200
    }

    /**
     * 拷贝一个 multipart 分段体，返回写入字节数。
     * 返回 -1 表示超过上限（L167-204）。
     */
    private fun copyPartBody(
        input: InputStream,
        output: OutputStream?,
        boundary: ByteArray,
        max: Long
    ): Long {
        val length = boundary.size + 2
        val marker = ByteArray(length)
        marker[0] = 13
        marker[1] = 10
        System.arraycopy(boundary, 0, marker, 2, boundary.size)
        val window = ByteArray(length)
        var count = 0L
        var filled = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Missing multipart boundary")
            if (filled < length) {
                val next = filled + 1
                window[filled] = b.toByte()
                if (next == length && startsWith(window, marker, length)) return count
                filled = next
            } else {
                output?.write(window[0].toInt())
                count++
                if (count > max) return -1L
                System.arraycopy(window, 1, window, 0, length - 1)
                window[length - 1] = b.toByte()
                if (startsWith(window, marker, length)) return count
            }
        }
    }

    /** 跳过一个没有文件名的分段（L218-220、L569-580）。 */
    private fun drainPart(input: InputStream, boundary: ByteArray): Boolean {
        return copyPartBody(input, null, boundary, MAX_UPLOAD_BYTES) >= 0L
    }

    private fun startsWith(a: ByteArray, b: ByteArray, len: Int): Boolean {
        for (i in 0 until len) {
            if (a[i] != b[i]) return false
        }
        return true
    }

    /** 查找字节序列（L953-969）。 */
    private fun skipUntil(input: InputStream, pattern: ByteArray): Boolean {
        var i = 0
        while (true) {
            val b = input.read()
            if (b < 0) return false
            if (b == pattern[i].toInt()) {
                i++
                if (i == pattern.size) return true
            } else {
                i = if (b != pattern[0].toInt()) 0 else 1
            }
        }
    }

    /** 读取一行（ISO-8859-1，最多 8KB）（L805-824）。 */
    private fun readLineIso(input: InputStream): String? {
        val buf = ByteArrayOutputStream(256)
        do {
            val b = input.read()
            if (b >= 0) {
                if (b == 10) break
                if (b != 13) buf.write(b)
            } else {
                if (buf.size() != 0) break
                return null
            }
        } while (buf.size() <= 8192)
        return buf.toString("ISO-8859-1")
    }

    /** 读取分段头（L790-803）。 */
    private fun readHeaders(input: InputStream): Map<String, String> {
        val map = HashMap<String, String>()
        while (true) {
            val line = readLineIso(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                map[line.substring(0, idx).trim().lowercase(Locale.US)] = line.substring(idx + 1).trim()
            }
        }
        return map
    }

    // ---------------------------------------------------------------- 工具

    /** 文件名净化（L877-897）。 */
    private fun sanitizeName(name: String?): String {
        var result = "file.bin"
        if (name == null) {
            Logx.w(LogTags.LAN, "文件名为空，改用默认名")
            return result
        }
        var s = name.replace('\\', '/')
        val lastSlash = s.lastIndexOf('/')
        if (lastSlash >= 0) s = s.substring(lastSlash + 1)
        s = s.replace(Regex("[\\x00-\\x1f<>:\"|?*]"), "_").trim()
        if (s.isNotEmpty() && s != "." && s != "..") result = s
        if (result != name) {
            // 排障场景：文件名被清洗过（去掉目录、替换非法字符）——记下原名与清洗结果
            Logx.d(LogTags.LAN, "文件名清洗", "raw" to name, "result" to result)
        }
        if (result.length <= MAX_NAME_LENGTH) return result
        val dot = result.lastIndexOf('.')
        val ext = if (dot > 0) result.substring(dot) else ""
        val keep = (MAX_NAME_LENGTH - ext.length).coerceAtLeast(0).coerceAtMost(result.length)
        val truncated = result.substring(0, keep) + ext
        // 排障场景：文件名被截断/替换过 —— 记下原名与清洗结果，文件对不上时能查
        Logx.i(
            LogTags.LAN,
            "文件名过长已截断",
            "raw" to name,
            "result" to truncated,
            "max" to MAX_NAME_LENGTH,
        )
        return truncated
    }

    /** 同名文件自动加后缀（L1042-1060）。 */
    private fun uniqueFile(dir: File, name: String): File {
        val file = File(dir, name)
        if (!file.exists()) return file
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 2 until 1000) {
            val candidate = File(dir, base + "-" + i + ext)
            if (!candidate.exists()) return candidate
        }
        return File(dir, base + "-" + System.currentTimeMillis() + ext)
    }

    /** 解析 Content-Disposition 里的文件名，支持 RFC 5987（L691-726）。 */
    private fun parseFilename(disposition: String?): String? {
        if (disposition.isNullOrEmpty()) return null
        val lower = disposition.lowercase(Locale.US)
        val starIdx = lower.indexOf("filename*=")
        if (starIdx < 0) {
            val idx = lower.indexOf("filename=")
            if (idx < 0) return null
            var v = disposition.substring(idx + 9).trim()
            val semi = v.indexOf(';')
            if (semi > 0) v = v.substring(0, semi).trim()
            return if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) v.substring(1, v.length - 1) else v
        }
        var v = disposition.substring(starIdx + 10).trim()
        val semi = v.indexOf(';')
        if (semi > 0) v = v.substring(0, semi).trim()
        if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length - 1)
        val q = v.indexOf("''")
        if (q >= 0) v = v.substring(q + 2)
        return try {
            URLDecoder.decode(v, "UTF-8")
        } catch (e: Throwable) {
            v
        }
    }

    /** 解析查询串（L728-743）。 */
    private fun parseQuery(query: String): Map<String, String> {
        val map = HashMap<String, String>()
        if (query.isEmpty()) return map
        for (pair in query.split("&")) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                try {
                    map[URLDecoder.decode(pair.substring(0, idx), "UTF-8")] =
                        URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                } catch (e: Throwable) {
                }
            }
        }
        return map
    }

    /** HTML 转义（L222-224）。 */
    private fun escapeHtml(text: String?): String {
        if (text == null) return ""
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }

    /** 体积格式化（L288-296）。 */
    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024L -> when {
                bytes < 1048576L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
                bytes < 1073741824L -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
                else -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
            }
            else -> "$bytes B"
        }
    }

    /** URL 编码（空格用 %20）（L135、L923）。 */
    private fun urlEncode(value: String): String {
        return try {
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        } catch (e: Throwable) {
            value
        }
    }

    /** 18 位十六进制令牌（L780-788）。 */
    private fun randomToken(): String {
        val bytes = ByteArray(9)
        SecureRandom().nextBytes(bytes)
        val sb = StringBuilder(18)
        for (b in bytes) {
            sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xff))
        }
        return sb.toString()
    }

    /** 结果页（L603-610）。 */
    private fun htmlMsg(message: String, success: Boolean): String {
        return "<!DOCTYPE html><html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>局域网传输</title><style>body{font-family:sans-serif;background:#111;color:#eee;padding:24px;}a{color:#64d2ff;}</style></head><body><h2>" +
            (if (success) "完成" else "失败") + "</h2><p>" + message + "</p></body></html>"
    }

    /** 写响应（L1062-1077）。 */
    private fun writeResponse(output: OutputStream, code: Int, type: String, body: String, req: String? = null) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val text = when (code) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            413 -> "Too Large"
            else -> "Error"
        }
        output.write(
            ("HTTP/1.1 " + code + " " + text + "\r\nContent-Type: " + type +
                "\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        )
        output.write(bytes)
        output.flush()
        // 排障场景：浏览器/电脑看到的响应码 —— 与请求行一起就能还原整条链路
        Logx.d(LogTags.LAN, "写出响应", "code" to code, "req" to (req ?: "-"), "bytes" to bytes.size)
    }

    /** 状态回调（L749-755）。 */
    private fun postStatus(message: String) {
        val cb = statusListener ?: return
        mainHandler.post {
            try {
                cb(message)
            } catch (e: Throwable) {
            }
        }
    }

    /** 最多读 Content-Length 字节的上传流（L477-500）。 */
    private class LimitedInputStream(private val source: InputStream, limit: Long) : InputStream() {
        private var remaining = limit

        override fun read(): Int {
            if (remaining <= 0L) return -1
            val b = source.read()
            if (b < 0) throw EOFException("Incomplete upload")
            remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val n = source.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("Incomplete upload")
            remaining -= n
            return n
        }

        override fun available(): Int = minOf(source.available().toLong(), remaining).toInt()
    }
}
