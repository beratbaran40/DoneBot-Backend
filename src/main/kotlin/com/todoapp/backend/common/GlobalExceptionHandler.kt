package com.todoapp.backend.common

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

/**
 * Single funnel for every exception thrown from a controller, so the API always answers in the
 * [BaseResponse] envelope and an internal detail / stack trace never reaches the client. Specific
 * handlers map known failures to their proper status; the catch-all logs the real cause on the
 * server and returns a generic 500.
 *
 * Note: the catch-all [handleGeneric] would otherwise also swallow [ResponseStatusException]
 * (chat 429/502, group 404, the auth rate-limit 429) and flatten it to 500 — [handleStatus]
 * intercepts those first to preserve the intended status + reason.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    // NB: AuthException is intentionally handled by AuthController's own @ExceptionHandler, which
    // distinguishes oauth-account conflicts (409) from bad credentials (401). A controller-local
    // handler wins over this advice, so we deliberately don't duplicate that logic here.

    // Bean-validation failure on a @Valid @RequestBody → 400 with the first offending field.
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ResponseEntity<BaseResponse<Nothing>> {
        val msg = ex.bindingResult.fieldErrors.firstOrNull()
            ?.let { "${it.field}: ${it.defaultMessage}" }
            ?: "Validation failed"
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(BaseResponse.error(400, msg))
    }

    // Missing / malformed JSON body → 400 (don't echo the parser's internal detail).
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException): ResponseEntity<BaseResponse<Nothing>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(BaseResponse.error(400, "Malformed or missing request body"))

    // Statuses a service threw on purpose — re-emit the intended status + reason in the envelope.
    @ExceptionHandler(ResponseStatusException::class)
    fun handleStatus(ex: ResponseStatusException): ResponseEntity<BaseResponse<Nothing>> {
        val status = ex.statusCode.value()
        val message = ex.reason ?: HttpStatus.resolve(status)?.reasonPhrase ?: "Error"
        return ResponseEntity.status(status).body(BaseResponse.error(status, message))
    }

    // Anything unmapped (NPE, IllegalState, DB error, …) → generic 500. The real cause + stack
    // trace is logged on the server only, never serialized to the client.
    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ResponseEntity<BaseResponse<Nothing>> {
        log.error("Unhandled exception", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(BaseResponse.error(500, "An unexpected error occurred"))
    }
}
