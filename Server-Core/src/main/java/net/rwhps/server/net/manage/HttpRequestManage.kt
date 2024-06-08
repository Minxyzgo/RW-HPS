/*
 * Copyright 2020-2024 RW-HPS Team and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/RW-HPS/RW-HPS/blob/master/LICENSE
 */

package net.rwhps.server.net.manage

import net.rwhps.server.util.log.Log.error
import net.rwhps.server.util.log.exp.VariableException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request.Builder
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.*
import java.util.concurrent.TimeUnit


/**
 * HTTP
 * @author Dr (dr@der.kim)
 */
object HttpRequestManage {
    private val CLIENT = OkHttpClient()
    private val RwClient = OkHttpClient.Builder().also { builder ->
        builder.retryOnConnectionFailure(true)
        builder.addInterceptor(MyOkHttpRetryInterceptor.Builder().executionCount(5).retryInterval(2500).build())
        builder.connectTimeout(10, TimeUnit.SECONDS)
        builder.readTimeout(10, TimeUnit.SECONDS)
        builder.writeTimeout(10, TimeUnit.SECONDS)
    }.build()

    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 Edg/114.0.1823.51"

    /**
     * Send a GET request and get back
     * @param url HTTP URL
     * @return    Data
     */
    @JvmStatic
    fun doGet(url: String?): String {
        if (url.isNullOrBlank()) {
            error("[GET URL] NULL")
            return ""
        }

        val request: Request = Builder().url(url).addHeader("User-Agent", USER_AGENT).build()
        try {
            CLIENT.newCall(request).execute().use { response -> return response.body?.string() ?: "" }
        } catch (e: Exception) {
            error(e)
        }
        return ""
    }

    /**
     * Send a POST request with Parameter and get back
     * @param url    HTTP URL
     * @param param  Parameter (A=B&C=D)
     * @return       Data
     */
    @JvmStatic
    fun doPost(url: String?, param: String): String {
        return doPost(url, parameterConversion(param))
    }

    /**
     * Send a POST request with Parameter and get back
     * @param url    HTTP URL
     * @param data    FormBody.Builder Parameter
     * @return        Data
     */
    @JvmStatic
    fun doPost(url: String?, data: FormBody.Builder): String {
        if (url.isNullOrBlank()) {
            error("[POST URL] NULL")
            return ""
        }

        val request: Request = Builder().url(url).addHeader("User-Agent", USER_AGENT).post(data.build()).build()
        return getHttpResultString(request)
    }

    /**
     * Send POST request with JSON and return
     * @param url    HTTP URL
     * @param param  JSON
     * @return       Data
     */
    @JvmStatic
    fun doPostJson(url: String?, param: String?): String {
        if (url.isNullOrBlank() || param.isNullOrBlank()) {
            error("[POST Json URL] NULL")
            return ""
        }

        val body: RequestBody = param.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request: Request = Builder().url(url).addHeader("User-Agent", USER_AGENT).post(body).build()
        return getHttpResultString(request)
    }

    @JvmStatic
    fun doPostRw(url: String, param: String): String {
        val request: Request = Builder()
            .url(url)
            .addHeader("User-Agent", "rw android 151 zh")
            .addHeader("Language", "zh")
            .addHeader("Connection", "close")
            .post(parameterConversion(param).build()).build()
        try {
            return getRwHttpResultString(request, true)
        } catch (e: Exception) {
            error("[UpList Error] CF CDN Error? (Ignorable)")
        }
        return ""
    }

    private fun parameterConversion(param: String): FormBody.Builder {
        val formBody = FormBody.Builder()
        val paramArray = param.split("&".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        for (pam in paramArray) {
            val keyValue = pam.split("=".toRegex()).toTypedArray()
            try {
                formBody.add(keyValue[0], keyValue[1])
            } catch (e: Exception) {
                error("invalid param: $keyValue. skip.")
            }
        }
        return formBody
    }

    /**
     * Request and Return
     * @param request     Request
     * @return            Result
     */
    private fun getHttpResultString(request: Request): String {
        return try {
            getHttpResultString(request, false)
        } catch (e: Exception) {
            error("[HttpResult]", e)
            ""
        }
    }

    /**
     * Request and Return
     * @param request     Request
     * @param resultError Print Error
     * @return            Result
     */
    @Throws(IOException::class)
    private fun getHttpResultString(request: Request, resultError: Boolean): String {
        var result = ""
        try {
            CLIENT.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Unexpected code", IOException())
                }
                result = response.body?.string() ?: ""
                response.body?.close()
            }
        } catch (e: Exception) {
            if (resultError) {
                throw e
            }
        }
        return result
    }

    /**
     * Request and Return
     * @param request     Request
     * @param resultError Print Error
     * @return            Result
     */
    @Throws(IOException::class)
    private fun getRwHttpResultString(request: Request, resultError: Boolean): String {
        var result = ""
        try {
            RwClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Unexpected code", IOException())
                }
                result = response.body?.string() ?: ""
                response.body?.close()
            }
        } catch (e: Exception) {
            if (resultError) {
                throw e
            }
        }
        return result
    }
}
