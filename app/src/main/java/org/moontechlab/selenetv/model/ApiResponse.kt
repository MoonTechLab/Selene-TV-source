package org.moontechlab.selenetv.model

import kotlinx.serialization.Serializable

/**
 * API 响应数据结构
 */
@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
    val statusCode: Int? = null
)

/**
 * API 响应辅助类
 * 提供便捷的成功和失败响应构造方法
 */
object ApiResponseHelper {
    
    /**
     * 创建成功响应
     * @param data 响应数据
     * @param statusCode HTTP 状态码（可选）
     */
    fun <T> success(data: T, statusCode: Int? = null): ApiResponse<T> {
        return ApiResponse(
            success = true,
            data = data,
            statusCode = statusCode
        )
    }
    
    /**
     * 创建失败响应
     * @param error 错误信息
     * @param statusCode HTTP 状态码（可选）
     */
    fun <T> error(error: String, statusCode: Int? = null): ApiResponse<T> {
        return ApiResponse(
            success = false,
            error = error,
            statusCode = statusCode
        )
    }
}

/**
 * ApiResponse 扩展函数
 */

/**
 * 判断响应是否成功且包含数据
 */
fun <T> ApiResponse<T>.isSuccessWithData(): Boolean {
    return success && data != null
}

/**
 * 获取数据或返回默认值
 */
fun <T> ApiResponse<T>.getDataOrDefault(default: T): T {
    return if (success && data != null) data else default
}

/**
 * 获取数据或执行失败回调
 */
inline fun <T> ApiResponse<T>.getDataOrElse(onError: (String) -> T): T {
    return if (success && data != null) {
        data
    } else {
        onError(error ?: "Unknown error")
    }
}

/**
 * 对成功的响应执行操作
 */
inline fun <T> ApiResponse<T>.onSuccess(action: (T) -> Unit): ApiResponse<T> {
    if (success && data != null) {
        action(data)
    }
    return this
}

/**
 * 对失败的响应执行操作
 */
inline fun <T> ApiResponse<T>.onError(action: (String) -> Unit): ApiResponse<T> {
    if (!success) {
        action(error ?: "Unknown error")
    }
    return this
}

/**
 * 转换响应数据类型
 */
inline fun <T, R> ApiResponse<T>.map(transform: (T) -> R): ApiResponse<R> {
    return if (success && data != null) {
        ApiResponse(
            success = true,
            data = transform(data),
            statusCode = statusCode
        )
    } else {
        ApiResponse(
            success = false,
            error = error,
            statusCode = statusCode
        )
    }
}
