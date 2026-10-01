package dev.memoh.android

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.memoh.core.data.CredentialStore
import dev.memoh.core.data.SessionRepository
import dev.memoh.core.data.SettingsStore
import dev.memoh.core.network.CloudAuth
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Application-wide bindings.
 *
 * The JSON and HTTP clients are singletons on purpose: both are thread-safe and
 * expensive to build, and the socket shares the client so its connection pool is
 * reused across REST and WebSocket traffic.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // The server adds fields over time and occasionally omits whole objects;
        // a strict decoder would crash on both.
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        isLenient = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Redirects are refused so a 3xx can never carry the bearer token to a
        // host we did not intend to talk to.
        .followRedirects(false)
        .followSslRedirects(false)
        // WebSockets stay open for long-running runs; OkHttp's own ping keeps
        // the connection alive through intermediaries.
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun provideCredentialStore(
        @ApplicationContext context: Context,
        json: Json,
    ): CredentialStore = CredentialStore(context, json)

    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext context: Context): SettingsStore =
        SettingsStore(context)

    @Provides
    @Singleton
    fun provideSessionRepository(
        credentials: CredentialStore,
        json: Json,
        cloudAuth: CloudAuth,
    ): SessionRepository = SessionRepository(credentials, json, cloudAuth)

    @Provides
    @Singleton
    fun provideCloudAuth(
        client: OkHttpClient,
        json: Json,
        credentials: CredentialStore,
    ): CloudAuth = CloudAuth(client, json, storage = credentials)
}
