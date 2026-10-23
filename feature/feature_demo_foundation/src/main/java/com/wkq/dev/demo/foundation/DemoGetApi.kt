package com.wkq.dev.demo.foundation
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Url
import okhttp3.ResponseBody
interface DemoGetApi { @GET fun get(@Url url:String):Call<ResponseBody> }
