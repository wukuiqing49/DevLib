package com.wkq.dev.demo.foundation

import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Url

/** 供网络演示调用的 Retrofit 动态 GET 接口，响应体由调用方负责关闭。 */
interface DemoGetApi {
    /** 按调用方提供的 URL 创建 GET 请求，返回未执行的原始响应体调用。 */
    @GET fun get(@Url url: String): Call<ResponseBody>
}
