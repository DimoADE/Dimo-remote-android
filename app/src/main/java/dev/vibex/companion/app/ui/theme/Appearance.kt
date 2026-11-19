package dev.vibex.companion.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

enum class Appearance(val id: String) {
    Light("light"),
    Dark("dark"),
    System("system");

    companion object {
        fun from(id: String?): Appearance = entries.firstOrNull { it.id == id } ?: System
    }
}

enum class AppLanguage(val id: String) {
    ZhHans("zh-Hans"),
    En("en");

    companion object {
        fun from(id: String?): AppLanguage = entries.firstOrNull { it.id == id } ?: ZhHans
    }
}

val LocalAppLanguage = compositionLocalOf { AppLanguage.ZhHans }

val LocalConfirmHighRiskApproval = compositionLocalOf { true }

@Composable
fun l(zh: String, en: String): String =
    if (LocalAppLanguage.current == AppLanguage.En) en else zh
