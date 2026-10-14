package com.wireless.audiostreamer.network

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

object FileTransfer {
    fun listDir(ctx: Context): File {
        val d = File(ctx.getExternalFilesDir(null), "shared")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun startServer(ctx: Context, onLog: (String) -> Unit = {}): Thread {
        return thread(isDaemon = true, name = "was-file-srv") {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(Protocol.PORT_FILE))
                while (!Thread.currentThread().isInterrupted) {
                    val sock = ss.accept()
                    thread(isDaemon = true) { handleClient(ctx, sock) }
                }
            } catch (e: Exception) {
                onLog("file server stop: ${e.message}")
            } finally {
                try { ss?.close() } catch (_: Exception) { }
            }
        }
    }

    private fun handleClient(ctx: Context, sock: Socket) {
        try {
            sock.soTimeout = 30000
            val din = DataInputStream(BufferedInputStream(sock.getInputStream()))
            val dout = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
            val line = din.readUTF()
            val dir = listDir(ctx)
            when {
                line == "LIST" -> {
                    val files = dir.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()
                    dout.writeInt(files.size)
                    for (n in files) dout.writeUTF(n)
                    dout.flush()
                }
                line.startsWith("GET ") -> {
                    val name = line.removePrefix("GET ").trim()
                    val f = File(dir, name)
                    if (!f.exists() || !f.isFile || name.contains("..") || name.contains('/')) {
                        dout.writeInt(-1)
                    } else {
                        dout.writeInt(f.length().toInt().coerceAtMost(Int.MAX_VALUE / 2))
                        FileInputStream(f).use { ins ->
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = ins.read(buf)
                                if (n <= 0) break
                                dout.write(buf, 0, n)
                            }
                        }
                    }
                    dout.flush()
                }
                line.startsWith("DELETE ") -> {
                    val name = line.removePrefix("DELETE ").trim()
                    val f = File(dir, name)
                    val ok = !name.contains("..") && f.exists() && f.delete()
                    dout.writeUTF(if (ok) "OK" else "ERR")
                    dout.flush()
                }
                line.startsWith("PUT ") -> {
                    val parts = line.split(' ')
                    if (parts.size < 3) { dout.writeUTF("ERR"); dout.flush(); return }
                    val name = parts[1]
                    val size = parts[2].toIntOrNull() ?: -1
                    if (size < 0 || size > 50_000_000 || name.contains("..") || name.contains('/')) {
                        dout.writeUTF("ERR"); dout.flush(); return
                    }
                    val f = File(dir, name)
                    FileOutputStream(f).use { outs ->
                        val buf = ByteArray(8192)
                        var left = size
                        while (left > 0) {
                            val n = din.read(buf, 0, minOf(buf.size, left))
                            if (n <= 0) break
                            outs.write(buf, 0, n)
                            left -= n
                        }
                    }
                    dout.writeUTF("OK")
                    dout.flush()
                }
                else -> { dout.writeUTF("ERR"); dout.flush() }
            }
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) { }
        }
    }

    fun listRemote(host: String): List<String> {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, Protocol.PORT_FILE), 5000)
            val din = DataInputStream(BufferedInputStream(s.getInputStream()))
            val dout = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            dout.writeUTF("LIST"); dout.flush()
            val n = din.readInt()
            if (n < 0 || n > 5000) return emptyList()
            return (0 until n).map { din.readUTF() }
        }
    }

    fun download(host: String, name: String, dest: File): Boolean {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, Protocol.PORT_FILE), 5000)
            val din = DataInputStream(BufferedInputStream(s.getInputStream()))
            val dout = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            dout.writeUTF("GET $name"); dout.flush()
            val size = din.readInt()
            if (size < 0) return false
            FileOutputStream(dest).use { outs ->
                val buf = ByteArray(8192)
                var left = size
                while (left > 0) {
                    val n = din.read(buf, 0, minOf(buf.size, left))
                    if (n <= 0) break
                    outs.write(buf, 0, n)
                    left -= n
                }
            }
            return true
        }
    }

    fun deleteRemote(host: String, name: String): Boolean {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, Protocol.PORT_FILE), 5000)
            val din = DataInputStream(BufferedInputStream(s.getInputStream()))
            val dout = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            dout.writeUTF("DELETE $name"); dout.flush()
            return din.readUTF() == "OK"
        }
    }

    fun upload(host: String, file: File): Boolean {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, Protocol.PORT_FILE), 5000)
            val din = DataInputStream(BufferedInputStream(s.getInputStream()))
            val dout = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            dout.writeUTF("PUT ${file.name} ${file.length()}"); dout.flush()
            FileInputStream(file).use { ins ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    dout.write(buf, 0, n)
                }
            }
            dout.flush()
            return din.readUTF() == "OK"
        }
    }
}
