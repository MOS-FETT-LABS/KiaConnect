package com.mosfettlabs.kiacompanion

import android.annotation.SuppressLint
import android.app.Activity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HTTP transport backed by an offscreen WebView. Kia USA's API sits behind
 * Cloudflare, which scores TLS fingerprints; Chrome's (via the WebView) is
 * trusted where the app's TLS stack is not. JS-side universal file access
 * lets a file:// page read the cross-origin responses.
 */
class KiaWebViewTransport(activity: Activity) {
    data class Response(val status: Int, val text: String, val headers: String)

    private data class PendingTask(val latch: CountDownLatch, var response: Response? = null)

    private val tasks = ConcurrentLinkedQueue<String>()
    private val waiters = ConcurrentHashMap<String, PendingTask>()
    private val readyLatch = CountDownLatch(1)

    @SuppressLint("SetJavaScriptEnabled")
    private val host: WebView = WebView(activity).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.userAgentString = "KIAPrimo_iOS/37 CFNetwork/1335.0.3.4 Darwin/21.6.0"
        CookieManager.getInstance().setAcceptCookie(true)
        webViewClient = WebViewClient()
        addJavascriptInterface(Interface(), "AndroidBridge")
        layoutParams = ViewGroup.LayoutParams(1, 1)
        alpha = 0f
        loadUrl("file:///android_asset/kia_transport.html")
    }

    init {
        (activity.findViewById(android.R.id.content) as? ViewGroup)
            ?.addView(host, ViewGroup.LayoutParams(1, 1))
    }

    fun awaitReady() {
        readyLatch.await(10, TimeUnit.SECONDS)
    }

    fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
    ): Response {
        val id = UUID.randomUUID().toString()
        val task = PendingTask(CountDownLatch(1))
        waiters[id] = task
        tasks.add(
            org.json.JSONObject()
                .put("id", id)
                .put("method", method)
                .put("url", url)
                .put("headers", org.json.JSONObject(headers))
                .put("body", body ?: "")
                .toString(),
        )
        if (!task.latch.await(90, TimeUnit.SECONDS)) {
            waiters.remove(id)
            throw KiaApi.ApiError("Kia request timed out inside the transport.")
        }
        task.response?.let { return it }
        throw KiaApi.ApiError("Kia transport returned no response.")
    }

    fun shutdown() {
        ((host.parent) as? ViewGroup)?.removeView(host)
        host.destroy()
    }

    inner class Interface {
        @JavascriptInterface
        fun ready() {
            readyLatch.countDown()
        }

        @JavascriptInterface
        fun next(): String? = tasks.poll()

        @JavascriptInterface
        fun onResult(json: String) {
            try {
                val obj = org.json.JSONObject(json)
                val id = obj.optString("id")
                val task = waiters.remove(id) ?: return
                task.response = Response(
                    status = obj.optInt("status", 0),
                    text = obj.optString("text"),
                    headers = obj.optString("headers"),
                )
                task.latch.countDown()
            } catch (_: Exception) {
            }
        }
    }

    fun persistCookies() {
        CookieManager.getInstance().flush()
    }
}
