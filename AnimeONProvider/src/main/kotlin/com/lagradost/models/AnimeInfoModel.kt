package com.lagradost.models

import com.fasterxml.jackson.annotation.JsonProperty

data class Genres(
    @JsonProperty("nameEn") val nameEn: String,
    @JsonProperty("nameUa") val nameUa: String
)

data class Image(
    @JsonProperty("preview") val preview: String
)
