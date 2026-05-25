package org.moontechlab.selenetv.service

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 供 ExoPlayer 使用的 HTTP DataSource 工厂。
 * 与 ImageLoader / SearchService 一致，信任所有证书并跳过 hostname 校验，
 * 以适配用户自签名 / 自托管的视频源。
 */
object MediaHttp {

    private val client: OkHttpClient by lazy { createUnsafeOkHttpClient() }

    /** ExoPlayer DefaultMediaSourceFactory 使用的 DataSource 工厂。 */
    val dataSourceFactory: DataSource.Factory by lazy {
        OkHttpDataSource.Factory(client)
    }

    private fun createUnsafeOkHttpClient(): OkHttpClient {
        return try {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val ssl = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
            OkHttpClient.Builder()
                .sslSocketFactory(ssl.socketFactory, trustAll[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
        } catch (e: Exception) {
            OkHttpClient.Builder().build()
        }
    }
}
