@file:kotlinx.serialization.UseSerializers(com.dayforge.domain.model.ContractStringSerializer::class,
    com.dayforge.domain.model.ContractBooleanSerializer::class)

package com.dayforge.data.api

import com.dayforge.data.api.dto.RefreshRequest
import com.dayforge.data.api.dto.TokenResponse
import com.dayforge.data.appearance.strictAppearanceJson
import com.dayforge.domain.model.isContractUuid
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.http.Body
import retrofit2.http.POST

internal fun materialTokenSafe(value: String) = value.isNotEmpty() && value.all { it.code in 33..126 }

internal interface MaterialRefreshApi {
    @POST("auth/refresh") suspend fun refresh(@Body request: RefreshRequest): MaterialRefreshResponse
}

@Serializable
internal class MaterialRefreshResponse(
    @SerialName("access_token") private val access: String,
    @SerialName("refresh_token") private val refresh: String,
    @SerialName("token_type") private val type: String = "bearer",
    @SerialName("user_id") private val user: String,
    private val username: String, @SerialName("is_admin") private val admin: Boolean
) {
    init {
        // Prove header-safe opaque tokens BEFORE saving either credential; never include them in errors.
        require(materialTokenSafe(access) && materialTokenSafe(refresh) &&
            type == "bearer" && isContractUuid(user) && username.isNotBlank())
    }
    fun value() = TokenResponse(access, refresh, type, user, username, admin)
}

/** Retrofit buffers errors too: bound every status before its converter can allocate a full body. */
internal class MaterialRefreshBoundary : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        chain.proceed(chain.request().newBuilder().header("Accept-Encoding", "identity").build()).use { response ->
            if (response.header("Content-Encoding")?.let { it != "identity" } == true) invalid()
            val body = response.body ?: invalid()
            if (body.contentLength() > 65_536) invalid()
            val output = ByteArrayOutputStream(8192)
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 65_537 - output.size()))
                    if (count == -1) break
                    if (count == 0 || output.size() + count > 65_536) invalid()
                    output.write(buffer, 0, count)
                }
            }
            val bytes = output.toByteArray()
            if (response.isSuccessful) {
                val type = body.contentType() ?: invalid()
                if (type.type != "application" || type.subtype != "json" || type.charset(Charsets.UTF_8) != Charsets.UTF_8) invalid()
                strictAppearanceJson(bytes, 65_536, {}, { invalid() })
            }
            return response.newBuilder().body(bytes.toResponseBody(body.contentType())).build()
        }
    }
    private fun invalid(): Nothing = throw IOException("MATERIAL_REFRESH_INVALID")
}
