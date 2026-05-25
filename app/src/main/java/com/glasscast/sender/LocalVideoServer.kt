package com.glasscast.sender

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class LocalVideoServer(private val context: Context) {
    private val appContext = context.applicationContext
    private val random = SecureRandom()
    private val running = AtomicBoolean(false)
    private val clientExecutor: ExecutorService = Executors.newCachedThreadPool()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var currentVideo: ServedVideo? = null

    @Volatile
    var port: Int = PREFERRED_PORT
        private set

    fun serve(uri: Uri): LocalVideoResult {
        ensureStarted()
        val localIp = localWifiIpAddress() ?: throw IOException("No local Wi-Fi IP address found")
        val token = shortToken()
        val mimeType = appContext.contentResolver.getType(uri)
            ?.takeIf { it.isNotBlank() }
            ?: "application/octet-stream"
        val length = contentLength(uri)
        val name = displayName(uri) ?: "Local video"

        currentVideo = ServedVideo(
            uri = uri,
            token = token,
            mimeType = mimeType,
            length = length,
            displayName = name
        )

        return LocalVideoResult(
            url = "http://$localIp:$port/video?token=$token",
            displayName = name,
            contentLength = length
        )
    }

    fun stop() {
        currentVideo = null
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun ensureStarted() {
        if (running.get() && serverSocket != null) return

        val socket = runCatching { bindSocket(PREFERRED_PORT) }
            .getOrElse { bindSocket(0) }
        serverSocket = socket
        port = socket.localPort
        running.set(true)

        Thread({
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                clientExecutor.execute { handleClient(client) }
            }
        }, "GlassCastLocalVideoServer").apply {
            isDaemon = true
            start()
        }
    }

    private fun bindSocket(port: Int): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
        }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1))
            val requestLine = reader.readLine().orEmpty()
            if (requestLine.isBlank()) return

            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0).orEmpty()
            val target = parts.getOrNull(1).orEmpty()
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase(Locale.US)] =
                        line.substring(separator + 1).trim()
                }
            }

            if (method != "GET" && method != "HEAD") {
                writeStatus(client, 405, "Method Not Allowed")
                return
            }

            val video = currentVideo
            val requestUri = runCatching { URI("http://localhost$target") }.getOrNull()
            val token = requestUri?.rawQuery
                ?.split("&")
                ?.firstOrNull { it.startsWith("token=") }
                ?.substringAfter("=")
            if (requestUri?.path != "/video" || video == null || token != video.token) {
                writeStatus(client, 404, "Not Found")
                return
            }

            writeVideo(client, video, headers["range"], method == "HEAD")
        }
    }

    private fun writeVideo(socket: Socket, video: ServedVideo, rangeHeader: String?, headOnly: Boolean) {
        val range = parseRange(rangeHeader, video.length)
        if (range == InvalidRange) {
            writeRangeNotSatisfiable(socket, video.length)
            return
        }

        val start = range?.start ?: 0L
        val knownLength = video.length >= 0
        val end = when {
            range?.end != null -> if (knownLength) min(range.end, video.length - 1) else range.end
            knownLength -> video.length - 1
            else -> null
        }
        val partial = range != null
        val contentLength = when {
            end != null -> (end - start + 1).coerceAtLeast(0)
            knownLength -> (video.length - start).coerceAtLeast(0)
            else -> null
        }

        val out = BufferedOutputStream(socket.getOutputStream())
        val status = if (partial) "206 Partial Content" else "200 OK"
        out.writeAscii("HTTP/1.1 $status\r\n")
        out.writeAscii("Content-Type: ${video.mimeType}\r\n")
        out.writeAscii("Accept-Ranges: bytes\r\n")
        if (partial) {
            val rangeEnd = end?.toString() ?: "*"
            val total = if (knownLength) video.length.toString() else "*"
            out.writeAscii("Content-Range: bytes $start-$rangeEnd/$total\r\n")
        }
        contentLength?.let { out.writeAscii("Content-Length: $it\r\n") }
        out.writeAscii("Connection: close\r\n")
        out.writeAscii("\r\n")
        out.flush()

        if (headOnly) return

        openVideoInput(video.uri, start).use { stream ->
            copyLimited(stream, out, contentLength)
        }
        out.flush()
    }

    private fun openVideoInput(uri: Uri, start: Long): InputStream {
        val descriptor = runCatching {
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")
        }.getOrNull()

        if (descriptor != null) {
            val stream = FileInputStream(descriptor.fileDescriptor)
            val absoluteStart = descriptor.startOffset + start
            stream.channel.position(absoluteStart)
            return object : InputStream() {
                override fun read(): Int = stream.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
                override fun close() {
                    stream.close()
                    descriptor.close()
                }
            }
        }

        val stream = appContext.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open content URI")
        skipFully(stream, start)
        return stream
    }

    private fun copyLimited(input: InputStream, output: BufferedOutputStream, maxBytes: Long?) {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = maxBytes
        while (remaining == null || remaining > 0) {
            val readSize = remaining?.let { min(buffer.size.toLong(), it).toInt() } ?: buffer.size
            val read = input.read(buffer, 0, readSize)
            if (read == -1) break
            output.write(buffer, 0, read)
            if (remaining != null) remaining -= read
        }
    }

    private fun parseRange(header: String?, length: Long): ByteRange? {
        if (header.isNullOrBlank()) return null
        val match = RANGE_PATTERN.matchEntire(header.trim()) ?: return InvalidRange
        val startText = match.groupValues[1]
        val endText = match.groupValues[2]
        if (startText.isBlank() && endText.isBlank()) return InvalidRange

        val start = if (startText.isNotBlank()) {
            startText.toLongOrNull() ?: return InvalidRange
        } else {
            val suffix = endText.toLongOrNull() ?: return InvalidRange
            if (length <= 0) return InvalidRange
            (length - suffix).coerceAtLeast(0)
        }

        val end = endText.takeIf { it.isNotBlank() && startText.isNotBlank() }?.toLongOrNull()
        if (start < 0 || (end != null && end < start)) return InvalidRange
        if (length >= 0 && start >= length) return InvalidRange
        return ByteRange(start, end)
    }

    private fun contentLength(uri: Uri): Long {
        val assetLength = runCatching {
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()
        if (assetLength != null && assetLength >= 0) return assetLength

        return queryOpenable(uri, OpenableColumns.SIZE)
            ?.toLongOrNull()
            ?: -1L
    }

    private fun displayName(uri: Uri): String? =
        queryOpenable(uri, OpenableColumns.DISPLAY_NAME)
            ?: uri.lastPathSegment?.substringAfterLast('/')

    private fun queryOpenable(uri: Uri, column: String): String? {
        val cursor = appContext.contentResolver.query(uri, arrayOf(column), null, null, null) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val index = it.getColumnIndex(column)
            if (index < 0 || it.isNull(index)) return null
            return it.getString(index)
        }
    }

    private fun shortToken(): String {
        val bytes = ByteArray(9)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun localWifiIpAddress(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (networkInterface in interfaces) {
            if (!networkInterface.isUp || networkInterface.isLoopback) continue
            val addresses = networkInterface.inetAddresses.toList()
            for (address in addresses) {
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                    return address.hostAddress
                }
            }
        }
        return null
    }

    private fun writeStatus(socket: Socket, code: Int, reason: String) {
        val body = "$code $reason\n"
        val out = BufferedOutputStream(socket.getOutputStream())
        out.writeAscii("HTTP/1.1 $code $reason\r\n")
        out.writeAscii("Content-Type: text/plain; charset=utf-8\r\n")
        out.writeAscii("Content-Length: ${body.toByteArray().size}\r\n")
        out.writeAscii("Connection: close\r\n")
        out.writeAscii("\r\n")
        out.write(body.toByteArray())
        out.flush()
    }

    private fun writeRangeNotSatisfiable(socket: Socket, length: Long) {
        val out = BufferedOutputStream(socket.getOutputStream())
        out.writeAscii("HTTP/1.1 416 Range Not Satisfiable\r\n")
        if (length >= 0) out.writeAscii("Content-Range: bytes */$length\r\n")
        out.writeAscii("Content-Length: 0\r\n")
        out.writeAscii("Connection: close\r\n")
        out.writeAscii("\r\n")
        out.flush()
    }

    private fun BufferedOutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.ISO_8859_1))
    }

    private fun skipFully(stream: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            if (stream.read() == -1) break
            remaining--
        }
    }

    data class LocalVideoResult(
        val url: String,
        val displayName: String,
        val contentLength: Long
    )

    private data class ServedVideo(
        val uri: Uri,
        val token: String,
        val mimeType: String,
        val length: Long,
        val displayName: String
    )

    private open class ByteRange(val start: Long, val end: Long?)
    private object InvalidRange : ByteRange(-1, -1)

    companion object {
        private const val PREFERRED_PORT = 8989
        private const val SOCKET_TIMEOUT_MS = 20_000
        private const val BUFFER_SIZE = 64 * 1024
        private val RANGE_PATTERN = Regex("""bytes=(\d*)-(\d*)""")
    }
}
