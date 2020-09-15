package com.example.eattogether_neep.Network

import com.example.eattogether_neep.Network.Post.*
import com.google.gson.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.http.*

interface NetworkService {
    //방 생성
    @POST("/room/checkroomid")
    fun postMakeUrlRequest(
        @Body body : PostMakeUrlRequest
    ) : Call<PostMakeUrlResponse>

    @Multipart
    @POST("/happiness")
    fun transferImage(
        @Part image: MultipartBody.Part,
        @Part("uuid") uuid: RequestBody,
        @Part("imageOrder") imageOrder: RequestBody
    ): Call<PostEmotionResponse>

    //참여 코드 입력
    @POST("/user/join")
    fun postJoinRequest(
        @Body body : PostJoinRequest
    ) : Call<PostJoinResponse>

    //선호도 입력
    @POST("/user/preference")
    fun postPreferenceResponse(
        @Header("Content-Type") content_type: String,
        @Body() body: JsonObject
    ): Call<PostPreferenceResponse>
}
