package com.lagradost.models

import com.fasterxml.jackson.annotation.JsonProperty

data class TranslationItem(
    @JsonProperty("translation") val translation: Translation,
    @JsonProperty("player") val player: List<TranslationPlayer>
)

data class Translation(
    @JsonProperty("id") val id: Int,
    @JsonProperty("name") val name: String,
    @JsonProperty("isSub") val isSub: Boolean
)

data class TranslationPlayer(
    @JsonProperty("name") val name: String,
    @JsonProperty("id") val id: Int,
    @JsonProperty("episodesCount") val episodesCount: Int
)
