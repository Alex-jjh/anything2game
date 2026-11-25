package com.anything2game.app.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.anything2game.app.R
import com.anything2game.app.data.api.ApiException
import com.anything2game.contract.ErrorCodes

/** What a failure is called inside the app: the server's error code, or a code of our own for local problems. */
fun problemCode(e: Throwable): String = when (e) {
    is ApiException -> e.code
    is java.io.IOException -> "CONNECTION_OR_FILE_ERROR"
    else -> "UNKNOWN"
}

/** The plain sentence for an error code. Raw codes, exception names and server messages never reach the screen. */
@StringRes
fun problemRes(code: String?): Int = when (code) {
    "INVALID_PDF", ErrorCodes.UNSUPPORTED_TYPE -> R.string.import_invalid_pdf
    ErrorCodes.FILE_TOO_LARGE -> R.string.import_oversized_pdf
    ErrorCodes.ENCRYPTED_PDF -> R.string.import_encrypted_pdf
    ErrorCodes.NO_TEXT_LAYER -> R.string.import_scanned_pdf
    "FILE_UNAVAILABLE" -> R.string.import_unreadable_pdf
    "CONNECTION_OR_FILE_ERROR", "CONNECTION_OR_STORAGE", "INSTALL_UNAVAILABLE" -> R.string.problem_offline
    "NOT_CONFIGURED" -> R.string.problem_not_connected
    ErrorCodes.INTERNAL_ERROR, "RESPONSE_TOO_LARGE" -> R.string.problem_server
    ErrorCodes.MODEL_TIMEOUT, ErrorCodes.PDF_TIMEOUT -> R.string.problem_slow
    ErrorCodes.QUOTA_EXCEEDED -> R.string.problem_quota
    ErrorCodes.NOT_FOUND -> R.string.problem_not_found
    ErrorCodes.PACKAGE_NOT_READY -> R.string.problem_not_ready
    ErrorCodes.UNAUTHORIZED -> R.string.problem_denied
    ErrorCodes.GENERATION_FAILED -> R.string.problem_make_failed
    "SPACE_STALE" -> R.string.problem_space_changed
    "DOCUMENT_VERSION_CHANGED", "SOURCE_CHANGED", "SOURCE_REMOVED" -> R.string.problem_file_changed
    "UPLOAD_OUTCOME_UNKNOWN" -> R.string.problem_unsure_upload
    "TUTOR_STALE", "TUTOR_MODE_CHANGED", "REQUEST_CONFLICT" -> R.string.problem_conversation_changed
    "TUTOR_CANCELLED" -> R.string.problem_cancelled
    "TUTOR_ALREADY_ASSESSED" -> R.string.problem_answered
    "CANCEL_UNCONFIRMED" -> R.string.problem_cancel_unsure
    "INVALID_OR_UNREADABLE_DATA" -> R.string.problem_saved_data
    "NO_AVAILABLE_ACTIVITIES", "INVALID_ACTIVITY" -> R.string.problem_activity
    else -> if (code != null && code.startsWith("HTTP_5")) R.string.problem_server else R.string.problem_generic
}

@Composable
fun problemText(code: String?): String = stringResource(problemRes(code))
