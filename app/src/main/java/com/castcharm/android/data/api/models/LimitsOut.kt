package com.castcharm.android.data.api.models

/**
 * Raw /api/limits response. See [com.castcharm.android.data.api.ServerLimits] for
 * the cached, defaulted view the rest of the app reads.
 *
 * Every field has a default so that a server which predates one of them — the
 * endpoint is additive-only — deserialises rather than failing outright.
 */
data class LimitsOut(
    val max_page_size: Int = 1_000,
    val max_ids_in_url: Int = 200,
    val max_bulk_ids: Int = 200,
    val max_index_ids: Int = 50_000,
    val max_search_len: Int = 200,
    val max_request_bytes: Long = 10L * 1024 * 1024,
)
