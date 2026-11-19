package dev.vibex.companion.app.data

import android.content.Context
import dev.vibex.remote.v1.JsonValue
import dev.vibex.companion.asObject
import dev.vibex.companion.asText
import dev.vibex.companion.asArray
import dev.vibex.companion.asNumber
import dev.vibex.companion.encodeObject
import dev.vibex.companion.app.ui.theme.AppLanguage
import dev.vibex.companion.app.ui.theme.Appearance
import dev.vibex.companion.app.ui.theme.AccentChoice
import java.io.File

class ProfileStore(context: Context) {
    private val file = File(context.filesDir, "host-profiles.json")

    @Synchronized
    fun load(): StoredState {
        if (!file.exists()) return StoredState()
        val root = JsonValue.parse(file.readText()) as? JsonValue.Object ?: return StoredState()
        val selected = root.value["selected"]?.asText()
        val appearance = Appearance.from(root.value["appearance"]?.asText())
        val language = AppLanguage.from(root.value["language"]?.asText())
        val confirmHighRiskApproval = (root.value["confirmHighRiskApproval"] as? JsonValue.Bool)?.value ?: true
        val muteForegroundNotifications = (root.value["muteForegroundNotifications"] as? JsonValue.Bool)?.value ?: true
        val customRelayOrigin = root.value["customRelayOrigin"]?.asText()?.trim().orEmpty()
        val accent = AccentChoice.from(root.value["accent"]?.asText())
        val streamDisplay = StreamDisplayPrefs.from(root.value["streamDisplay"])
        val profiles = root.value["profiles"]?.asArray()?.value.orEmpty().mapNotNull { item ->
            val obj = item.asObject() ?: return@mapNotNull null
            HostProfile(
                hostId = obj.value["hostId"]?.asText() ?: return@mapNotNull null,
                name = obj.value["name"]?.asText() ?: "Host",
                deviceId = obj.value["deviceId"]?.asText().orEmpty(),
                reachability = obj.value["reachability"]?.asArray()?.value.orEmpty().mapNotNull { r ->
                    val o = r.asObject() ?: return@mapNotNull null
                    StoredReachability(
                        o.value["origin"]?.asText() ?: return@mapNotNull null,
                        o.value["kind"]?.asText() ?: "unknown",
                    )
                },
                lastSuccessOrigin = obj.value["lastSuccessOrigin"]?.asText(),
                lastSyncAt = obj.value["lastSyncAt"]?.asNumber()?.toLong(),
                selectedProjectId = obj.value["selectedProjectId"]?.asText(),
            )
        }
        return StoredState(
            selectedHostId = selected,
            profiles = profiles,
            appearance = appearance,
            streamDisplay = streamDisplay,
            accent = accent,
            language = language,
            confirmHighRiskApproval = confirmHighRiskApproval,
            muteForegroundNotifications = muteForegroundNotifications,
            customRelayOrigin = customRelayOrigin,
        )
    }

    @Synchronized
    fun save(state: StoredState) {
        val body = encodeObject(
            mapOf(
                "selected" to state.selectedHostId,
                "appearance" to state.appearance.id,
                "accent" to state.accent.id,
                "language" to state.language.id,
                "confirmHighRiskApproval" to state.confirmHighRiskApproval,
                "muteForegroundNotifications" to state.muteForegroundNotifications,
                "customRelayOrigin" to state.customRelayOrigin,
                "streamDisplay" to state.streamDisplay.toMap(),
                "profiles" to state.profiles.map { profile ->
                    mapOf(
                        "hostId" to profile.hostId,
                        "name" to profile.name,
                        "deviceId" to profile.deviceId,
                        "lastSuccessOrigin" to profile.lastSuccessOrigin,
                        "lastSyncAt" to profile.lastSyncAt,
                        "selectedProjectId" to profile.selectedProjectId,
                        "reachability" to profile.reachability.map {
                            mapOf("origin" to it.origin, "kind" to it.kind)
                        },
                    )
                },
            ),
        )
        file.writeText(body)
    }
}

data class StoredState(
    val selectedHostId: String? = null,
    val profiles: List<HostProfile> = emptyList(),
    val appearance: Appearance = Appearance.System,
    val streamDisplay: StreamDisplayPrefs = StreamDisplayPrefs(),
    val accent: AccentChoice = AccentChoice.Ink,
    val language: AppLanguage = AppLanguage.ZhHans,
    val confirmHighRiskApproval: Boolean = true,
    val muteForegroundNotifications: Boolean = true,
    val customRelayOrigin: String = "",
)
