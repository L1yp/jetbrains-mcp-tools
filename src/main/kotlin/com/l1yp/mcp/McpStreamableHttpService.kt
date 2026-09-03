package com.l1yp.mcp

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.l1yp.logging.McpToolboxLogService
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.util.CharsetUtil
import org.jetbrains.ide.BuiltInServerManager
import org.jetbrains.ide.HttpRequestHandler
import java.net.InetSocketAddress

internal class McpStreamableHttpService : HttpRequestHandler() {
    private val security by lazy { McpHttpSecurity(service<McpProjectTokenRegistry>()) }
    private val dispatcher by lazy { McpJsonRpcDispatcher(pluginVersion()) }

    override fun isAccessible(request: HttpRequest): Boolean = true

    override fun isSupported(request: FullHttpRequest): Boolean =
        QueryStringDecoder(request.uri()).path() == McpProtocol.ENDPOINT_PATH

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): Boolean {
        if (urlDecoder.path() != McpProtocol.ENDPOINT_PATH) return false
        if (request.method() != HttpMethod.POST) {
            sendResponse(
                context,
                request,
                405,
                errorBody(JsonRpcErrorCode.INVALID_REQUEST, "Only POST is supported"),
                mapOf(HttpHeaderNames.ALLOW.toString() to HttpMethod.POST.name()),
            )
            return true
        }
        if (request.content().readableBytes() > MAX_REQUEST_BYTES) {
            sendResponse(
                context,
                request,
                413,
                errorBody(JsonRpcErrorCode.INVALID_REQUEST, "Request body exceeds 1 MiB"),
            )
            return true
        }

        val securityResult = security.authorize(
            McpHttpSecurityRequest(
                remoteAddress = context.channel().remoteAddress() as? InetSocketAddress,
                origin = request.headers().get(HttpHeaderNames.ORIGIN),
                host = request.headers().get(HttpHeaderNames.HOST),
                authorization = request.headers().get(HttpHeaderNames.AUTHORIZATION),
                contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE),
                accept = request.headers().get(HttpHeaderNames.ACCEPT),
                actualPort = BuiltInServerManager.getInstance().port,
            ),
        )
        if (securityResult is McpHttpSecurityResult.Rejected) {
            val headers = if (securityResult.httpStatus == 401) {
                mapOf(HttpHeaderNames.WWW_AUTHENTICATE.toString() to "Bearer")
            } else {
                emptyMap()
            }
            sendResponse(
                context,
                request,
                securityResult.httpStatus,
                errorBody(JsonRpcErrorCode.INVALID_REQUEST, securityResult.message),
                headers,
            )
            return true
        }

        val project = (securityResult as McpHttpSecurityResult.Authorized).project
        val requestBody = request.content().toString(CharsetUtil.UTF_8)
        val requestDescription = describeRequest(requestBody)
        val startedAt = System.nanoTime()
        val toolboxLog = project.service<McpToolboxLogService>()
        toolboxLog.info("MCP 请求", "开始处理 $requestDescription")
        var processingFailure: String? = null
        val dispatchResult = try {
            dispatcher.dispatch(
                requestBody = requestBody,
                protocolVersionHeader = request.headers().get(MCP_PROTOCOL_VERSION_HEADER),
                project = project,
            )
        } catch (error: Exception) {
            LOG.warn("MCP request processing failed", error)
            processingFailure = "处理异常：${error.javaClass.simpleName}"
            McpDispatchResult(
                httpStatus = 500,
                responseBody = errorBody(JsonRpcErrorCode.INTERNAL_ERROR, "Internal error"),
            )
        }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        val failure = processingFailure ?: dispatchFailure(dispatchResult.responseBody)
        when {
            failure != null -> toolboxLog.error(
                "MCP 请求",
                "$requestDescription 失败：$failure，HTTP ${dispatchResult.httpStatus}，耗时 ${elapsedMillis}ms",
            )
            dispatchResult.httpStatus >= 400 -> toolboxLog.warning(
                "MCP 请求",
                "$requestDescription 返回 HTTP ${dispatchResult.httpStatus}，耗时 ${elapsedMillis}ms",
            )
            else -> toolboxLog.success(
                "MCP 请求",
                "$requestDescription 完成，HTTP ${dispatchResult.httpStatus}，耗时 ${elapsedMillis}ms",
            )
        }
        sendResponse(context, request, dispatchResult.httpStatus, dispatchResult.responseBody)
        return true
    }

    private fun describeRequest(requestBody: String): String = runCatching {
        val payload = JsonParser.parseString(requestBody).asJsonObject
        val method = payload.stringValue("method") ?: "未知方法"
        if (method == "tools/call") {
            val toolName = payload.getAsJsonObject("params")?.stringValue("name")
            if (toolName.isNullOrBlank()) method else "$method($toolName)"
        } else {
            method
        }
    }.getOrDefault("无法解析的请求")

    private fun dispatchFailure(responseBody: String?): String? = runCatching {
        val payload = responseBody?.let(JsonParser::parseString)?.asJsonObject ?: return@runCatching null
        payload.getAsJsonObject("error")?.stringValue("message")?.let { return@runCatching it }
        val result = payload.getAsJsonObject("result") ?: return@runCatching null
        if (result.get("isError")?.asBoolean != true) return@runCatching null
        result.getAsJsonArray("content")
            ?.firstOrNull()
            ?.asJsonObject
            ?.stringValue("text")
            ?: "工具返回错误"
    }.getOrNull()

    private fun sendResponse(
        context: ChannelHandlerContext,
        request: FullHttpRequest,
        statusCode: Int,
        body: String?,
        additionalHeaders: Map<String, String> = emptyMap(),
    ) {
        val content = body?.let { Unpooled.copiedBuffer(it, CharsetUtil.UTF_8) } ?: Unpooled.EMPTY_BUFFER
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.valueOf(statusCode),
            content,
        )
        if (body != null) {
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "$JSON_MEDIA_TYPE; charset=utf-8")
        }
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store")
        HttpUtil.setContentLength(response, content.readableBytes().toLong())
        additionalHeaders.forEach { (name, value) -> response.headers().set(name, value) }

        val keepAlive = HttpUtil.isKeepAlive(request)
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        }
        val future = context.writeAndFlush(response)
        if (!keepAlive) future.addListener(ChannelFutureListener.CLOSE)
    }

    private fun errorBody(code: Int, message: String): String = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", JsonNull.INSTANCE)
        add("error", JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
        })
    }.toString()

    private fun pluginVersion(): String =
        PluginManagerCore.getPlugin(PLUGIN_ID)?.version ?: "unknown"

    private companion object {
        const val MAX_REQUEST_BYTES = 1024 * 1024
        const val JSON_MEDIA_TYPE = "application/json"
        const val MCP_PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"
        val PLUGIN_ID: PluginId = PluginId.getId("com.l1yp.mcpTools")
        val LOG = logger<McpStreamableHttpService>()
    }
}

private fun JsonObject.stringValue(name: String): String? = get(name)
    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
    ?.asString
