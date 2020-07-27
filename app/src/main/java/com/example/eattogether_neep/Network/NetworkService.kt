package com.example.eattogether_neep.Network

import com.example.eattogether_neep.Network.Get.GetRankingResponse
import com.example.eattogether_neep.Network.Post.*
import com.google.gson.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.http.*

interface NetworkService {
    //참여 코드 입력
    @POST("/user/join")
    fun postJoinResponse(
        @Body body : PostJoinRequest
    ) : Call<PostJoinResponse>

    //방 생성
    @POST("/user/makeurl")
    fun postMakeUrlResponse(
        @Body body : PostMakeUrlRequest
    ) : Call<PostMakeUrlResponse>

    //선호도 입력
    @POST("/user/preference")
    fun postPreferenceResponse(
        @Header("Content-Type") content_type: String,
        @Body() body: JsonObject
    ): Call<PostPreferenceResponse>

    //랭킹 조회
    @GET("/rank")
    fun getRankingResponse(
        @Header("Content-Type") content_type:String
    ):Call<GetRankingResponse>
}
