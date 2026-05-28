package org.moontechlab.selenetv

import android.app.Application
import android.util.Log
import coil.EventListener
import org.moontechlab.selenetv.data.local.LocalStore
import org.moontechlab.selenetv.service.AppDataService
import org.moontechlab.selenetv.server.RemoteControlServer
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.moontechlab.selenetv.service.SubscriptionService
import org.moontechlab.selenetv.ui.MoonToast

/**
 * 自定义 Application 类，用于配置 Coil 图片加载
 */
class SeleneApplication : Application(), ImageLoaderFactory {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 初始化 AppDataService
        AppDataService.init(this)
        LocalStore.init(this)
        Log.d("SeleneApplication", "Application initialized")
        
        // 启动遥控器 Web 服务器
        startRemoteControlServer()

        // 本地模式启动后台刷新订阅：不阻塞 onCreate，失败仅 toast
        if (AppDataService.isLocalModeSync()) {
            applicationScope.launch {
                val ok = SubscriptionService.refreshAndPersist()
                if (!ok) MoonToast.show("订阅刷新失败")
            }
        }
    }
    
    private fun startRemoteControlServer() {
        thread {
            if (RemoteControlServer.start()) {
                Log.i("SeleneApplication", "Remote control available at http://<device-ip>:${RemoteControlServer.getPort()}/remote_control")
            }
        }
    }
    
    override fun onTerminate() {
        super.onTerminate()
        RemoteControlServer.stop()
    }
    
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient {
                createUnsafeOkHttpClient()
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .eventListener(ImageEventListener())
            .build()
    }
    
    /**
     * 创建信任所有证书的 OkHttpClient
     * 用于解决某些 CDN 证书不被信任的问题
     */
    private fun createUnsafeOkHttpClient(): OkHttpClient {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            
            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS) // 图片整体加载上限,超时即落「暂无封面」
                .addInterceptor(ImageHeaderInterceptor())
                .addInterceptor(ImageLoggingInterceptor())
                .build()
        } catch (e: Exception) {
            Log.e("SeleneApplication", "Failed to create unsafe OkHttpClient", e)
            return OkHttpClient.Builder()
                .callTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .addInterceptor(ImageHeaderInterceptor())
                .addInterceptor(ImageLoggingInterceptor())
                .build()
        }
    }
    
    /**
     * 图片请求头拦截器
     * 添加 User-Agent 和 Referer 用于豆瓣图片防盗链
     */
    private class ImageHeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            val newRequest = originalRequest.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                .header("Referer", "https://movie.douban.com/")
                .build()
            
            return chain.proceed(newRequest)
        }
    }
    
    /**
     * 图片加载日志拦截器
     * 记录失败的图片请求
     */
    private class ImageLoggingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            
            return response
        }
    }
    
    /**
     * 图片事件监听器
     * 记录图片解码失败等错误
     */
    private class ImageEventListener : EventListener {
        override fun onError(request: ImageRequest, result: ErrorResult) {
            Log.w("CoilImg", "图片加载失败 data=${request.data} cause=${result.throwable}")
        }
    }
}
