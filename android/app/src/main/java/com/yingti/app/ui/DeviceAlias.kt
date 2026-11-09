package com.yingti.app.ui

/** 设备本地别名：只影响本机显示，不改 BLE 上报的 deviceName，也不上服务器。 */
object DeviceAlias {
    const val MAX_LENGTH = 20
    const val DEFAULT_NAME = "SX589B"

    /** 归一化：去首尾空白、按 Unicode 码点截到 MAX_LENGTH（不劈开代理对）；空白 → null（恢复默认名）。 */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val end = if (length(trimmed) > MAX_LENGTH) trimmed.offsetByCodePoints(0, MAX_LENGTH) else trimmed.length
        return trimmed.substring(0, end)
    }

    /** 显示长度按 Unicode 码点计，一个 emoji 算 1。 */
    fun length(input: String): Int = input.codePointCount(0, input.length)

    fun displayName(alias: String?, deviceName: String?): String =
        alias?.trim()?.takeIf { it.isNotEmpty() } ?: deviceName ?: DEFAULT_NAME
}
