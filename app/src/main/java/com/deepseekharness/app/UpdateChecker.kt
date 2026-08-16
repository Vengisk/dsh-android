package com.deepseekharness.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 静默检查更新：查询 GitHub Releases 最新版本号。
 * 直连失败（被墙）时 fallback ghfast.top 代理；全部失败静默返回 null。
 */
object UpdateChecker {

    private val URLS = arrayOf(
        // 直连 GitHub API（魔法环境可用）
        "https://api.github.com/repos/Vengisk/dsh-android/releases/latest",
        // jsdelivr CDN 读仓库 VERSION 文件（国内直连稳定）
        "https://cdn.jsdelivr.net/gh/Vengisk/dsh-android@main/VERSION",
        // 代理 fallback（API 可能被代理拒，放最后兜底）
        "https://ghfast.top/https://api.github.com/repos/Vengisk/dsh-android/releases/latest"
    )

    /** 查询最新版本号（vX.Y.Z），失败返回 null */
    @JvmStatic
    fun checkLatestVersion(): String? {
        for (url in URLS) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection)
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "dsh-android/1.0.2")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val code = conn.responseCode
                if (code != 200) {
                    conn.disconnect()
                    continue
                }
                val sb = StringBuilder()
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        sb.append(line)
                        if (sb.length > 262144) break
                    }
                }
                conn.disconnect()
                val tag = extractTag(sb.toString())
                if (tag != null && tag.matches(Regex("v?\\d+(\\.\\d+)*"))) return tag
                // 兼容纯文本（VERSION 文件：单行 "v1.0.11"）
                val plain = sb.toString().trim()
                if (plain.matches(Regex("v?\\d+(\\.\\d+)*"))) return plain
            } catch (ignored: Exception) {
            }
        }
        return null
    }

    private fun extractTag(json: String): String? {
        val i = json.indexOf("\"tag_name\"")
        if (i < 0) return null
        val s = json.indexOf('"', i + 10)
        if (s < 0) return null
        val e = json.indexOf('"', s + 1)
        if (e < 0) return null
        return json.substring(s + 1, e)
    }

    /** 比较最新版（v1.2.3）是否比当前（1.0.0）新 */
    @JvmStatic
    fun isNewer(latestTag: String, current: String): Boolean {
        val a = latestTag.replaceFirst("^v", "").split(".")
        val b = current.replaceFirst("^v", "").split(".")
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = if (i < a.size) parseInt(a[i]) else 0
            val y = if (i < b.size) parseInt(b[i]) else 0
            if (x != y) return x > y
        }
        return false
    }

    private fun parseInt(s: String): Int {
        return try {
            Integer.parseInt(s.replace("[^0-9]".toRegex(), ""))
        } catch (e: Exception) {
            0
        }
    }
}
