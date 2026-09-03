package com.l1yp.mcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

@Service
internal class McpProjectTokenRegistry {
    private val tokensByProject = ConcurrentHashMap<Project, String>()

    fun bind(project: Project, token: String) {
        tokensByProject[project] = token
    }

    fun unbind(project: Project) {
        tokensByProject.remove(project)
    }

    fun resolve(candidate: String): Project? {
        val candidateBytes = candidate.toByteArray(StandardCharsets.UTF_8)
        return tokensByProject.entries.firstOrNull { (project, token) ->
            !project.isDisposed && MessageDigest.isEqual(
                token.toByteArray(StandardCharsets.UTF_8),
                candidateBytes,
            )
        }?.key
    }
}

@Service(Service.Level.PROJECT)
@State(
    name = "McpToolboxProjectToken",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
internal class McpProjectTokenService(private val project: Project) :
    PersistentStateComponent<McpProjectTokenState>,
    Disposable {
    @Volatile
    private var currentState = McpProjectTokenState()

    override fun getState(): McpProjectTokenState = currentState

    override fun loadState(state: McpProjectTokenState) {
        currentState = state
    }

    @Synchronized
    fun token(): String {
        val existing = currentState.token.takeIf { it.isValidToken() }
        val value = existing ?: McpProjectTokenGenerator.generate().also { currentState.token = it }
        service<McpProjectTokenRegistry>().bind(project, value)
        return value
    }

    @Synchronized
    fun rotate(): String {
        val value = McpProjectTokenGenerator.generate()
        currentState.token = value
        service<McpProjectTokenRegistry>().bind(project, value)
        return value
    }

    override fun dispose() {
        service<McpProjectTokenRegistry>().unbind(project)
    }

    private fun String.isValidToken(): Boolean = length >= MIN_ENCODED_TOKEN_LENGTH

    private companion object {
        const val MIN_ENCODED_TOKEN_LENGTH = 43
    }
}

internal object McpProjectTokenGenerator {
    private const val TOKEN_BYTES = 32
    private val secureRandom = java.security.SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

internal class McpProjectTokenState {
    var token: String = ""
}

internal class McpProjectTokenStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<McpProjectTokenService>().token()
    }
}
