package com.mm.smartcgm.core

object UserNumber {
    fun normalize(text: String): String = text.trim().map {
        when (it) {
            in '٠'..'٩' -> '0' + (it - '٠')
            in '۰'..'۹' -> '0' + (it - '۰')
            '٫', ',' -> '.'
            else -> it
        }
    }.joinToString("")
    fun parse(text: String): Double? = normalize(text).toDoubleOrNull()?.takeIf { it.isFinite() }
}
