package com.lagradost.models

import com.fasterxml.jackson.annotation.JsonProperty

data class FundubEpisode(
    @JsonProperty("id") val id: Int,
    @JsonProperty("episode") val episode: Int,
    @JsonProperty("subtitles") val subtitles: Boolean,
    @JsonProperty("poster") val poster: String?,
    @JsonProperty("fileUrl") val fileUrl: String?,
    @JsonProperty("videoUrl") val videoUrl: String?
)
