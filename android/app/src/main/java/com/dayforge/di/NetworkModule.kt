package com.dayforge.di

import com.dayforge.BuildConfig
import com.dayforge.data.api.AdminApi
import com.dayforge.data.api.AuthApi
import com.dayforge.data.api.SyncV2Api
import com.dayforge.data.api.TokenApi
import com.dayforge.data.api.SelectedNetworkTransport
import com.dayforge.data.api.authenticator.TokenAuthenticator
import com.dayforge.data.api.interceptor.AuthInterceptor
import com.dayforge.data.api.interceptor.BaseUrlInterceptor
import com.dayforge.data.local.PreferencesManager
import com.dayforge.data.local.TokenManager
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import kotlinx.serialization.ExperimentalSerializationApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@OptIn(ExperimentalSerializationApi::class)
object NetworkModule {

    // Default base URL - overridden by BaseUrlInterceptor when user sets custom server
    private const val BASE_URL = "http://localhost:8000/api/v1/"

    /**
     * Provides JSON serializer for Retrofit.
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Provides AuthApi with a simple client (no authenticator to avoid circular dependency).
     * Used for login, health check, and token refresh endpoints.
     */
    @Provides
    @Singleton
    fun provideAuthApi(
        json: Json,
        preferencesManager: PreferencesManager,
        selectedTransport: SelectedNetworkTransport
    ): AuthApi {
        val client = OkHttpClient.Builder()
            .socketFactory(selectedTransport.socketFactory)
            .dns(selectedTransport.dns)
            .addInterceptor(BaseUrlInterceptor(preferencesManager))
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BASIC
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
                redactHeader("Authorization")
                redactHeader("Cookie")
            })
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .client(client)
            .build()

        return retrofit.create(AuthApi::class.java)
    }

    /**
     * Provides OkHttpClient with auth interceptor and token authenticator.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(
        tokenManager: TokenManager,
        tokenAuthenticator: TokenAuthenticator,
        preferencesManager: PreferencesManager,
        selectedTransport: SelectedNetworkTransport
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .socketFactory(selectedTransport.socketFactory)
            .dns(selectedTransport.dns)
            .addInterceptor(BaseUrlInterceptor(preferencesManager))
            .addInterceptor(AuthInterceptor(tokenManager))
            .authenticator(tokenAuthenticator)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BASIC
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
                redactHeader("Authorization")
                redactHeader("Cookie")
            })
            .build()
    }

    /**
     * Provides Retrofit instance for API services.
     */
    @Provides
    @Singleton
    fun provideRetrofit(
        client: OkHttpClient,
        json: Json
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .client(client)
            .build()
    }

    @Provides
    @Singleton
    fun provideSyncV2Api(retrofit: Retrofit): SyncV2Api {
        return retrofit.create(SyncV2Api::class.java)
    }

    @Provides
    @Singleton
    fun provideAdminApi(retrofit: Retrofit): AdminApi {
        return retrofit.create(AdminApi::class.java)
    }

    @Provides
    @Singleton
    fun provideTokenApi(retrofit: Retrofit): TokenApi {
        return retrofit.create(TokenApi::class.java)
    }
}
