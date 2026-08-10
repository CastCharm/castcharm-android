package com.castcharm.android.data.api

// Pulls the human-readable reason, and any structured context, out of a failed call.
//
// Retrofit surfaces a non-2xx response as an HttpException whose message is only the
// status line ("HTTP 409 Conflict"). The reason the server actually gave is in the
// response body, which Retrofit does not touch. Showing localizedMessage therefore
// tells the user nothing — "Could not add feed: HTTP 409 " — even when the server has
// said something genuinely useful.

import org.json.JSONObject
import retrofit2.HttpException

/**
 * A folder-name clash the user has to resolve.
 *
 * The server refuses rather than silently filing a new podcast into a directory that
 * already holds another podcast's files, and returns what's needed to ask: the folder
 * name, where it is, and how much is in it.
 */
data class FolderConflict(
    val folderName: String,
    val folderPath: String?,
    val fileCount: Int,
)

/** Everything worth knowing about a failed request, parsed in one pass. */
data class ApiError(
    val message: String?,
    val folderConflict: FolderConflict?,
)

/**
 * Parse a failure once.
 *
 * The error body is a one-shot stream — `ResponseBody.string()` consumes it, and a
 * second read comes back empty. So everything is extracted in a single call rather
 * than through separate helpers that would silently starve each other.
 *
 * FastAPI puts the reason in `detail`, which is either a plain string or an object
 * carrying at least a `message`; CastCharm uses the object form where it also returns
 * structured context.
 */
fun Throwable.parseApiError(): ApiError {
    val http = this as? HttpException ?: return ApiError(null, null)
    val body = runCatching { http.response()?.errorBody()?.string() }.getOrNull()
    if (body.isNullOrBlank()) return ApiError(null, null)

    return runCatching {
        when (val detail = JSONObject(body).opt("detail")) {
            is String -> ApiError(detail.takeIf { it.isNotBlank() }, null)
            is JSONObject -> ApiError(
                message = detail.optString("message").takeIf { it.isNotBlank() },
                folderConflict = if (detail.optBoolean("folder_conflict")) {
                    FolderConflict(
                        folderName = detail.optString("conflict_title"),
                        folderPath = detail.optString("folder_path").takeIf { it.isNotBlank() },
                        fileCount = detail.optInt("file_count"),
                    )
                } else {
                    null
                },
            )
            else -> ApiError(null, null)
        }
    }.getOrDefault(ApiError(null, null))
}
