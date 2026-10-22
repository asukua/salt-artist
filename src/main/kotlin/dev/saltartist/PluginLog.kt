package dev.saltartist

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * 宿主内没有可直接查看的插件日志窗口，因此插件把关键路径写入插件配置目录下的
 * diagnostics.log；同时打印到 stdout，方便从启动脚本重定向时排查。
 */
class PluginLog(private val file: Path?) {
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val onceKeys = ConcurrentHashMap.newKeySet<String>()

    fun log(message: String) {
        val line = "${LocalTime.now().format(formatter)} $message"
        println("[SaltArtist] $line")
        val target = file ?: return
        runCatching {
            target.parent?.let { Files.createDirectories(it) }
            Files.writeString(
                target,
                line + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    /** 每个 key 只记录一次，避免重组或滚动时刷爆日志。 */
    fun logOnce(key: String, message: String) {
        if (onceKeys.add(key)) log(message)
    }

    fun describe(error: Throwable?): String = if (error == null) "(none)" else "${error.javaClass.name}: ${error.message ?: "(no message)"}"
}
