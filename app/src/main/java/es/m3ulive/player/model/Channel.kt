package es.m3ulive.player.model

data class Channel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val group: String,
    val logoUrl: String,
    val tvgId: String,
    val headers: Map<String, String> = emptyMap()
)
