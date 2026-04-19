package com.todoapp.backend.common

data class BaseResponse<T>(
    val code: Int,
    val message: String,
    val data: T?,
) {
    companion object {
        fun <T> ok(data: T, message: String = "Operation completed successfully"): BaseResponse<T> =
            BaseResponse(code = 200, message = message, data = data)

        fun ok(message: String = "Operation completed successfully"): BaseResponse<Unit> =
            BaseResponse(code = 200, message = message, data = Unit)

        fun <T> error(code: Int, message: String): BaseResponse<T> =
            BaseResponse(code = code, message = message, data = null)
    }
}
