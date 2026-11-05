package it.fast4x.piped

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.path
import io.ktor.serialization.kotlinx.json.json
import it.fast4x.piped.models.CreatedPlaylist
import it.fast4x.piped.models.Instance
import it.fast4x.piped.models.PipedResponse
import it.fast4x.piped.models.Playlist
import it.fast4x.piped.models.PlaylistPreview
import it.fast4x.piped.models.Session
import it.fast4x.piped.models.authenticatedWith
import it.fast4x.piped.utils.ProxyPreferences
import it.fast4x.piped.utils.getProxy
import it.fast4x.piped.utils.runCatchingCancellable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.logging.HttpLoggingInterceptor
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.UUID


operator fun Url.div(path: String) = URLBuilder(this).apply { path(path) }.build()
operator fun JsonElement.div(key: String) = jsonObject[key]!!

object Piped {
    @OptIn(ExperimentalSerializationApi::class)
    private val client by lazy {
        HttpClient(OkHttp) {
            //BrowserUserAgent()

            //expectSuccess = true

            install(ContentNegotiation) {
                val feature = Json {
                    ignoreUnknownKeys = true
                    //explicitNulls = false
                    //encodeDefaults = true
                    isLenient = true
                }

                json(feature)
                //json(feature, ContentType.Text.Html)
                //json(feature, ContentType.Text.Plain)
            }

            install(HttpRequestRetry) {
                exponentialDelay()
                maxRetries = 2
                retryOnExceptionOrServerErrors(maxRetries = 2)
            }

            // Old values (1s connect / 5s request) were far too short for public Piped instances
            // (often slow or overloaded), so almost every call failed and retries piled up.
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000L
                requestTimeoutMillis = 30_000L
                socketTimeoutMillis = 30_000L
            }

            /*
            install(ContentEncoding) {
                gzip()
                deflate()
            }
             */

            ProxyPreferences.preference?.let {
                engine {
                    proxy = getProxy(it)
                }
            }

            expectSuccess = true

            engine {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.NONE
                    }
                )
            }

            defaultRequest {
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.Json)
            }



        }
    }


    // NOTE: requests used to be wrapped in a global Mutex, so one slow request blocked every
    // other Piped call (infinite loading). Requests now run concurrently.

    private suspend fun request(
        session: Session,
        endpoint: String,
        block: HttpRequestBuilder.() -> Unit = { }
    ) = client.request(url = session.apiBaseUrl / endpoint) {
        block()
        header("Authorization", session.token)
    }

    private suspend fun HttpResponse.isOk() =
        (body<JsonElement>() / "message").jsonPrimitive.content == "ok"

    private suspend fun HttpResponse.bodyAsText() = body<String>()

    /**
     * Fallback API endpoints used when the instances registry is unreachable.
     * These are public instances and may change; users can still type a custom URL.
     */
    val fallbackInstanceUrls = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
        "https://pipedapi.leptons.xyz",
        "https://pipedapi.drgns.space",
    )

    suspend fun getInstances() = runCatchingCancellable {
        client.get("https://piped-instances.kavin.rocks/").body<List<Instance>>()
    }

    /** Returns the first instance URL (from [candidates]) whose /healthcheck answers. */
    suspend fun firstWorkingInstance(candidates: List<String> = fallbackInstanceUrls): String? {
        for (url in candidates) {
            val ok = runCatchingCancellable {
                client.get("${url.trimEnd('/')}/healthcheck").status.value in 200..299
            }?.getOrNull() == true
            if (ok) return url
        }
        return null
    }

    /**
     * Tries to log in on [apiBaseUrl] first and, if that fails for network reasons,
     * on each fallback instance. Returns the first successful session.
     */
    suspend fun loginWithFallback(apiBaseUrl: Url, username: String, password: String) =
        runCatchingCancellable {
            val candidates = (listOf(apiBaseUrl.toString()) + fallbackInstanceUrls)
                .filter { it.isNotBlank() }
                .distinct()
            var last: Throwable? = null
            for (candidate in candidates) {
                val r = login(Url(candidate), username, password)
                if (r?.isSuccess == true) return@runCatchingCancellable r.getOrThrow()
                last = r?.exceptionOrNull()
            }
            throw last ?: IllegalStateException("No Piped instance reachable")
        }

    suspend fun login(apiBaseUrl: Url, username: String, password: String) =
        runCatchingCancellable {
            apiBaseUrl authenticatedWith (
                    client.post(apiBaseUrl / "login") {
                        setBody(
                            mapOf(
                                "username" to username,
                                "password" to password
                            )
                        )
                    }.body<JsonElement>() / "token"
                    ).jsonPrimitive.content
        }

    val playlist = Playlists()
    val media = Media()

    @Serializable
    data class Message(
        val error: String? = null,
        val message: String? = null
    )

    class Playlists internal constructor() {
        suspend fun list(session: Session) = runCatchingCancellable {
            request(session, "user/playlists").body<List<PlaylistPreview>>()
        }
        suspend fun listTest(session: Session) = runCatchingCancellable {
            println("pipedInfo piped.playlists.listTest: " + request(session, "user/playlists").bodyAsText())
        }

        suspend fun create(session: Session, name: String) = runCatchingCancellable {
            request(session, "user/playlists/create") {
                method = HttpMethod.Post
                setBody(mapOf("name" to name))
            }.body<CreatedPlaylist>()
        }

        suspend fun rename(session: Session, id: UUID, name: String) = runCatchingCancellable {
            request(session, "user/playlists/rename") {
                method = HttpMethod.Post
                setBody(
                    mapOf(
                        "playlistId" to id.toString(),
                        "newName" to name
                    )
                )
            }.isOk()
        }

        suspend fun delete(session: Session, id: UUID) = runCatchingCancellable {
            request(session, "user/playlists/delete") {
                method = HttpMethod.Post
                setBody(mapOf("playlistId" to id.toString()))
            }.isOk()
        }

        suspend fun add(session: Session, id: UUID, videos: List<String>) = runCatchingCancellable {

            var body =
                "\"session\":\"${session.token}\"," +
                "\"playlistId\":\"${id}\"," +
                if (videos.size == 1)
                    "\"videoId\":\"${videos.first()}\""
                else
                    "\"videoIds\":" + videos.joinToString(prefix = "[", postfix = "]") { it -> "\"${it}\"" }

            body = "{$body}"

            withContext(NonCancellable) {
                client.post(session.apiBaseUrl / "user/playlists/add") {
                    header("Authorization", session.token)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }.isOk()
            }
        }?.onFailure {
            println("pipedInfo piped.playlists.add general failed:  ${it.message}")
        }

        suspend fun remove(session: Session, id: UUID, idx: Int) = runCatchingCancellable {
            request(session, "user/playlists/remove") {
                method = HttpMethod.Post
                setBody(
                    mapOf(
                        "playlistId" to id.toString(),
                        "index" to idx.toString()
                    )
                )
            }.isOk()
        }?.onFailure {
            println("pipedInfo piped.playlists.remove: failed ${it.message}")
        }

        suspend fun songs(session: Session, id: UUID) = runCatchingCancellable {
            request(session, "playlists/$id").body<Playlist>()
        }?.onFailure {
            println("pipedInfo piped.playlists.songs: failed ${it.message}")
        }

    }

    class Media internal constructor() {
        suspend fun audioStreams(session: Session, videoId: String) = runCatchingCancellable {
            request(session, "/streams/$videoId").body<PipedResponse>().audioStreams
        }
    }


}
