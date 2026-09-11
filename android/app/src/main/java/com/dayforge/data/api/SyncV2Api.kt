package com.dayforge.data.api

import com.dayforge.data.api.dto.DeviceRegisterRequest
import com.dayforge.data.api.dto.DeviceResponse
import com.dayforge.data.api.dto.DeviceEditingUpdate
import com.dayforge.data.api.dto.ServerIdentityResponse
import com.dayforge.data.api.dto.SyncV2BootstrapResponse
import com.dayforge.data.api.dto.SyncV2PullResponse
import com.dayforge.data.api.dto.SyncV2PushRequest
import com.dayforge.data.api.dto.SyncV2PushResponse
import com.dayforge.data.api.dto.TimerCommandBatchRequest
import com.dayforge.data.api.dto.TimerCommandBatchResponse
import com.dayforge.data.api.dto.TimerHeartbeatRequest
import com.dayforge.data.api.dto.TimerHeartbeatResponse
import com.dayforge.data.api.dto.TimerStatusResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PATCH
import retrofit2.http.Query
import retrofit2.http.Path

interface SyncV2Api {
    @GET("/api/v2/system/identity")
    suspend fun identity(): ServerIdentityResponse

    @POST("/api/v2/devices/register")
    suspend fun registerDevice(@Body request: DeviceRegisterRequest): DeviceResponse

    @GET("/api/v2/devices")
    suspend fun listDevices(): List<DeviceResponse>

    @POST("/api/v2/devices/{deviceId}/make-primary")
    suspend fun makePrimaryDevice(@Path("deviceId") deviceId: String): DeviceResponse

    @PATCH("/api/v2/devices/{deviceId}/editing")
    suspend fun updateDeviceEditing(
        @Path("deviceId") deviceId: String,
        @Body request: DeviceEditingUpdate
    ): DeviceResponse

    @POST("/api/v2/timers/commands")
    suspend fun pushTimerCommands(
        @Body request: TimerCommandBatchRequest
    ): TimerCommandBatchResponse

    @GET("/api/v2/timers/{sessionId}")
    suspend fun timerStatus(
        @Path("sessionId") sessionId: String,
        @Query("device_id") deviceId: String
    ): TimerStatusResponse

    @POST("/api/v2/timers/{sessionId}/heartbeat")
    suspend fun heartbeatTimer(
        @Path("sessionId") sessionId: String,
        @Body request: TimerHeartbeatRequest
    ): TimerHeartbeatResponse

    @POST("/api/v2/sync/push")
    suspend fun push(@Body request: SyncV2PushRequest): SyncV2PushResponse

    @GET("/api/v2/sync/changes")
    suspend fun pull(
        @Query("device_id") deviceId: String,
        @Query("cursor") cursor: Long,
        @Query("limit") limit: Int = 500
    ): SyncV2PullResponse

    @GET("/api/v2/sync/bootstrap")
    suspend fun bootstrap(@Query("device_id") deviceId: String): SyncV2BootstrapResponse
}
