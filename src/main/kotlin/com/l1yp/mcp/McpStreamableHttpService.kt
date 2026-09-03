package com.l1yp.mcp

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.l1yp.logging.McpToolboxLogService
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpResponse
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class McpStreamableHttpService : HttpRequestHandler() {
    private val security by lazy { McpHttpSecurity(service<McpProjectTokenRegistry>()) }
    private val dispatcher by lazy { McpJsonRpcDispatcher(pluginVersion()) }
    private val legacySessions = ConcurrentHashMap<String, LegacySseSession>()

    override fun isAccessible(request: HttpRequest): Boolean = true

    override fun isSupported(request: FullHttpRequest): Boolean =
        QueryStringDecoder(request.uri()).path() in SUPPORTED_PATHS

    override fun process(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): Boolean {
        return when (urlDecoder.path()) {
            McpProtocol.ENDPOINT_PATH -> {
                when (request.method()) {
                    HttpMethod.POST -> processStreamablePost(request, context)
                    HttpMethod.GET -> processLegacySseConnect(request, context)
                    else -> methodNotAllowed(context, request, "GET, POST")
                }
                true
            }
            McpProtocol.LEGACY_SSE_ENDPOINT_PATH -> {
                if (request.method() == HttpMethod.GET) {
                    processLegacySseConnect(request, context)
                } else {
                    methodNotAllowed(context, request, HttpMethod.GET.name())
                }
                true
            }
            McpProtocol.LEGACY_MESSAGE_ENDPOINT_PATH -> {
                if (request.method() == HttpMethod.POST) {
                    processLegacySseMessage(urlDecoder, request, context)
                } else {
                    methodNotAllowed(context, request, HttpMethod.POST.name())
                }
                true
            }
            else -> false
        }
    }

    private fun processStreamablePost(request: FullHttpRequest, context: ChannelHandlerContext) {
        if (rejectOversizedRequest(request, context)) return
        val project = authorize(request, context, McpHttpExchange.STREAMABLE_POST) ?: return
        val dispatchResult = dispatchAndLog(
            request = request,
            project = project,
            protocolVersionHeader = request.headers().get(MCP_PROTOCOL_VERSION_HEADER),
            transport = McpTransport.STREAMABLE_HTTP,
        )
        sendResponse(context, request, dispatchResult.httpStatus, dispatchResult.responseBody)
    }

    private fun processLegacySseConnect(request: FullHttpRequest, context: ChannelHandlerContext) {
        if (request.headers().contains(MCP_PROTOCOL_VERSION_HEADER)) {
            methodNotAllowed(context, request, HttpMethod.POST.name())
            return
        }
        val project = authorize(request, context, McpHttpExchange.LEGACY_SSE_GET) ?: return
        if (legacySessions.size >= MAX_LEGACY_SSE_SESSIONS) {
            sendResponse(
                context,
                request,
                503,
                errorBody(JsonRpcErrorCode.INTERNAL_ERROR, "Too many legacy SSE connections"),
            )
            return
        }

        val sessionId = UUID.randomUUID().toString()
        val session = LegacySseSession(project, context)
        legacySessions[sessionId] = session
        context.channel().closeFuture().addListener {
            if (legacySessions.remove(sessionId, session)) {
                session.heartbeat?.cancel(false)
                if (!project.isDisposed) {
                    runCatching {
                        project.service<McpToolboxLogService>().info("MCP SSE", "旧版 SSE 连接已关闭")
                    }
                }
            }
        }

        val host = requireNotNull(request.headers().get(HttpHeaderNames.HOST))
        val messageEndpoint =
            "http://$host${McpProtocol.LEGACY_MESSAGE_ENDPOINT_PATH}?$LEGACY_SESSION_QUERY=$sessionId"
        sendSseStart(context, McpLegacySseCodec.endpointEvent(messageEndpoint))
        val heartbeat = context.executor().scheduleAtFixedRate(
            {
                if (context.channel().isActive) {
                    context.writeAndFlush(sseContent(McpLegacySseCodec.heartbeat()))
                }
            },
            SSE_HEARTBEAT_SECONDS,
            SSE_HEARTBEAT_SECONDS,
            TimeUnit.SECONDS,
        )
        session.heartbeat = heartbeat
        if (!context.channel().isActive || legacySessions[sessionId] !== session) {
            heartbeat.cancel(false)
        }
        project.service<McpToolboxLogService>().success(
            "MCP SSE",
            "已建立 MCP ${McpProtocol.LEGACY_HTTP_SSE_2024_11_05} 兼容连接",
        )
    }

    private fun processLegacySseMessage(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ) {
        if (rejectOversizedRequest(request, context)) return
        val project = authorize(request, context, McpHttpExchange.LEGACY_SSE_POST) ?: return
        val sessionValues = urlDecoder.parameters()[LEGACY_SESSION_QUERY]
        val sessionId = sessionValues?.singleOrNull()?.takeIf(String::isNotBlank)
        if (sessionId == null) {
            sendResponse(
                context,
                request,
                400,
                errorBody(JsonRpcErrorCode.INVALID_REQUEST, "A single legacy SSE sessionId is required"),
            )
            return
        }
        val session = legacySessions[sessionId]
        if (session == null) {
            sendResponse(
                context,
                request,
                404,
                errorBody(JsonRpcErrorCode.INVALID_REQUEST, "Legacy SSE session was not found"),
            )
            return
        }
        if (!session.context.channel().isActive) {
            if (legacySessions.remove(sessionId, session)) {
                session.heartbeat?.cancel(false)
            }
            sendResponse(
                context,
                request,
                404,
                errorBody(JsonRpcErrorCode.INVALID_REQUEST, "Legacy SSE session was not found"),
            )
            return
        }
        if (session.project !== project) {
            sendResponse(
                context,
                request,
                403,
                errorBody(JsonRpcErrorCode.INVALID_REQUEST, "Legacy SSE session belongs to another project"),
            )
            return
        }

        val suppliedVersion = request.headers().get(MCP_PROTOCOL_VERSION_HEADER)
        val effectiveVersion = suppliedVersion ?: session.protocolVersion
        val dispatchResult = dispatchAndLog(
            request = request,
            project = project,
            protocolVersionHeader = effectiveVersion,
            transport = McpTransport.LEGACY_HTTP_SSE,
        )
        dispatchResult.negotiatedProtocolVersion?.let { negotiatedVersion ->
            session.protocolVersion = negotiatedVersion
        }
        dispatchResult.responseBody?.let { responseBody ->
            writeSseEvent(session, McpLegacySseCodec.messageEvent(responseBody))
        }
        sendResponse(context, request, 202, null)
    }

    private fun authorize(
        request: FullHttpRequest,
        context: ChannelHandlerContext,
        exchange: McpHttpExchange,
    ): Project? {
        val securityResult = security.authorize(
            McpHttpSecurityRequest(
                remoteAddress = context.channel().remoteAddress() as? InetSocketAddress,
                origin = request.headers().get(HttpHeaderNames.ORIGIN),
                host = request.headers().get(HttpHeaderNames.HOST),
                authorization = request.headers().get(HttpHeaderNames.AUTHORIZATION),
                contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE),
                accept = request.headers().get(HttpHeaderNames.ACCEPT),
                actualPort = BuiltInServerManager.getInstance().port,
                exchange = exchange,
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
            return null
        }
        return (securityResult as McpHttpSecurityResult.Authorized).project
    }

    private fun rejectOversizedRequest(request: FullHttpRequest, context: ChannelHandlerContext): Boolean {
        if (request.content().readableBytes() > MAX_REQUEST_BYTES) {
            sendResponse(
                context,
                request,
                413,
                errorBody(JsonRpcErrorCode.INVALID_REQUEST, "Request body exceeds 1 MiB"),
            )
            return true
        }
        return false
    }

    private fun dispatchAndLog(
        request: FullHttpRequest,
        project: Project,
        protocolVersionHeader: String?,
        transport: McpTransport,
    ): McpDispatchResult {
        val requestBody = request.content().toString(CharsetUtil.UTF_8)
        val requestDescription = McpRequestLogFormatter.describe(
            requestBody = requestBody,
            diagnosticClientName = request.headers().get(McpProtocol.DIAGNOSTIC_CLIENT_HEADER),
            userAgent = request.headers().get(HttpHeaderNames.USER_AGENT),
        )
        val startedAt = System.nanoTime()
        val toolboxLog = project.service<McpToolboxLogService>()
        toolboxLog.info("MCP 请求", "开始处理 $requestDescription")
        var processingFailure: String? = null
        val dispatchResult = try {
            dispatcher.dispatch(
                requestBody = requestBody,
                protocolVersionHeader = protocolVersionHeader,
                project = project,
                transport = transport,
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
        val delivery = if (transport == McpTransport.LEGACY_HTTP_SSE) {
            "SSE"
        } else {
            "HTTP ${dispatchResult.httpStatus}"
        }
        when {
            failure != null -> toolboxLog.error(
                "MCP 请求",
                "$requestDescription 失败：$failure，$delivery，耗时 ${elapsedMillis}ms",
            )
            dispatchResult.httpStatus >= 400 -> toolboxLog.warning(
                "MCP 请求",
                "$requestDescription 返回 $delivery，耗时 ${elapsedMillis}ms",
            )
            else -> toolboxLog.success(
                "MCP 请求",
                "$requestDescription 完成，$delivery，耗时 ${elapsedMillis}ms",
            )
        }
        return dispatchResult
    }

    private fun methodNotAllowed(
        context: ChannelHandlerContext,
        request: FullHttpRequest,
        allowedMethods: String,
    ) {
        sendResponse(
            context,
            request,
            405,
            errorBody(JsonRpcErrorCode.INVALID_REQUEST, "Method is not supported for this MCP endpoint"),
            mapOf(HttpHeaderNames.ALLOW.toString() to allowedMethods),
        )
    }

    private fun sendSseStart(context: ChannelHandlerContext, initialEvent: String) {
        val response = DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "$SSE_MEDIA_TYPE; charset=utf-8")
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache, no-store")
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        HttpUtil.setTransferEncodingChunked(response, true)
        context.write(response)
        context.writeAndFlush(sseContent(initialEvent))
    }

    private fun writeSseEvent(session: LegacySseSession, event: String): Boolean {
        val channel = session.context.channel()
        if (!channel.isActive) return false
        session.context.executor().execute {
            if (channel.isActive) {
                session.context.writeAndFlush(sseContent(event))
            }
        }
        return true
    }

    private fun sseContent(event: String): DefaultHttpContent =
        DefaultHttpContent(Unpooled.copiedBuffer(event, CharsetUtil.UTF_8))

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
        const val MAX_LEGACY_SSE_SESSIONS = 32
        const val SSE_HEARTBEAT_SECONDS = 15L
        const val JSON_MEDIA_TYPE = "application/json"
        const val SSE_MEDIA_TYPE = "text/event-stream"
        const val MCP_PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"
        const val LEGACY_SESSION_QUERY = "sessionId"
        val SUPPORTED_PATHS = setOf(
            McpProtocol.ENDPOINT_PATH,
            McpProtocol.LEGACY_SSE_ENDPOINT_PATH,
            McpProtocol.LEGACY_MESSAGE_ENDPOINT_PATH,
        )
        val PLUGIN_ID: PluginId = PluginId.getId("com.l1yp.mcpTools")
        val LOG = logger<McpStreamableHttpService>()
    }
}

private class LegacySseSession(
    val project: Project,
    val context: ChannelHandlerContext,
) {
    @Volatile
    var protocolVersion: String? = null

    @Volatile
    var heartbeat: ScheduledFuture<*>? = null
}

internal object McpLegacySseCodec {
    fun endpointEvent(endpoint: String): String = event("endpoint", endpoint)

    fun messageEvent(jsonRpcMessage: String): String = event("message", jsonRpcMessage)

    fun heartbeat(): String = ": keepalive\n\n"

    private fun event(type: String, data: String): String = buildString {
        append("event: ").append(type).append('\n')
        data.lineSequence().forEach { line -> append("data: ").append(line).append('\n') }
        append('\n')
    }
}

internal object McpRequestLogFormatter {
    fun describe(
        requestBody: String,
        diagnosticClientName: String?,
        userAgent: String?,
    ): String {
        val payload = runCatching { JsonParser.parseString(requestBody).asJsonObject }.getOrNull()
        val method = payload?.stringValue("method") ?: "无法解析的请求"
        val operation = if (method == "tools/call") {
            val toolName = payload?.objectValue("params")?.stringValue("name")
            if (toolName.isNullOrBlank()) method else "$method($toolName)"
        } else {
            method
        }
        val identity = buildList {
            diagnosticClientName.normalizedIdentity()?.let { add("客户端标记=$it") }
            if (method == "initialize") {
                payload?.objectValue("params")
                    ?.objectValue("clientInfo")
                    ?.let(::formatClientInfo)
                    ?.let { add("clientInfo=$it") }
            }
            userAgent.normalizedIdentity()?.let { add("User-Agent=$it") }
        }
        return if (identity.isEmpty()) "$operation · 客户端=未知" else "$operation · ${identity.joinToString(" · ")}"
    }

    private fun formatClientInfo(clientInfo: JsonObject): String? {
        val name = clientInfo.stringValue("name").normalizedIdentity() ?: return null
        val version = clientInfo.stringValue("version").normalizedIdentity()
        return if (version == null) name else "$name/$version"
    }

    private fun String?.normalizedIdentity(): String? = this
        ?.replace(Regex("[\\r\\n\\t]+"), " ")
        ?.trim()
        ?.take(MAX_IDENTITY_LENGTH)
        ?.takeIf(String::isNotEmpty)

    private const val MAX_IDENTITY_LENGTH = 256
}

private fun JsonObject.stringValue(name: String): String? = get(name)
    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
    ?.asString

private fun JsonObject.objectValue(name: String): JsonObject? = get(name)
    ?.takeIf { it.isJsonObject }
    ?.asJsonObject
