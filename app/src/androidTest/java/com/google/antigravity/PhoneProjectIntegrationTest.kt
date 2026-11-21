package com.google.antigravity

import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.antigravity.device.PhoneResourceController
import com.google.antigravity.model.AgentExecutionTarget
import com.google.antigravity.model.ToolCallStatus
import com.google.antigravity.network.AntigravityBackendClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class PhoneProjectIntegrationTest {
    @Test fun offlinePcChatCreatesEditsChecksAndOpensAWebProjectOnAndroid() = runBlocking {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Owned QA emulator only" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val controller = PhoneResourceController(context)
        val name = "qa-project-${UUID.randomUUID().toString().take(8)}"
        val project = File(controller.appSandboxWorkspace, "projects/$name")
        val index = "<html><body><output id='result'></output><script type='module' src='src/main.js'></script></body></html>"
        val source = "document.getElementById('result').textContent = String(21);"
        val docs = "# QA\n```js\nconsole.log(42)\n```\n"
        val shellTest = "grep -q 'String(42)' src/main.js && echo 'shell checks passed'"
        fun action(type: String) = JSONObject().put("action", type).put("name", name)
        fun block(action: JSONObject) = "```phone_action\n$action\n```"
        fun file(path: String, content: String) = JSONObject().put("path", path).put("content", content)
        val responses = listOf(
            block(action("create_project").put("files", JSONArray().put(file("index.html", index))
                .put(file("src/main.js", source)).put(file("README.md", docs)).put(file("tests/check.sh", shellTest)))),
            block(action("read_project_file").put("path", "src/main.js")),
            block(action("patch_project_file").put("path", "src/main.js").put("target", "21").put("replacement", "42")) + "\n" +
                block(action("android_cli").put("project", name).put("command", "sh tests/check.sh")),
            block(action("check_project").put("files", JSONArray()
                .put(JSONObject().put("path", "src/main.js").put("contains", "42")))) + "\n" +
                block(action("test_web_project").put("checks", JSONArray()
                    .put(JSONObject().put("selector", "#result").put("text", "42")))) + "\n" +
                block(action("preview_project")) + "\n" + block(action("export_project")),
            "Проект проверен на телефоне."
        )
        var requestIndex = 0
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("https://openrouter.ai/api/v1/chat/completions", chain.request().url.toString())
            val body = JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
                JSONObject().put("content", responses[requestIndex++]))))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("QA")
                .body(body.toString().toResponseBody()).build()
        }.build()
        val downloads = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val written = mutableListOf<android.net.Uri>()
        try {
            val result = AntigravityBackendClient(controller, transport).executeAgentTurn("Сделай веб-проект", false,
                AgentExecutionTarget.PC_REMOTE_SESSION, "qa/text", "qa-project", "QA", emptyList(), emptyList(),
                "http://offline.invalid", "", "qa-phone-key")
            assertFalse(result.isError)
            assertEquals(responses.size, requestIndex)
            assertTrue(result.toolCalls.joinToString("\n") { "${it.toolName}: ${it.details}" },
                result.toolCalls.all { it.status == ToolCallStatus.COMPLETED })
            assertTrue(result.toolCalls.single { it.toolName == "test_web_project" }.details.contains("OK #result: 42"))
            assertTrue(result.toolCalls.single { it.toolName == "android_cli" }.details.contains("shell checks passed"))
            assertEquals(docs, File(project, "README.md").readText())
            assertEquals(source.replace("21", "42"), File(project, "src/main.js").readText())

            context.contentResolver.query(downloads, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("$name%.zip"), null)?.use { cursor ->
                while (cursor.moveToNext()) written.add(ContentUris.withAppendedId(downloads, cursor.getLong(0)))
            }
            assertTrue("The project ZIP was published in Downloads", written.isNotEmpty())
            assertTrue(written.any { uri -> context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zip ->
                    generateSequence { zip.nextEntry }.any { entry ->
                        entry.name == "src/main.js" && zip.readBytes().decodeToString().contains("42")
                    }
                }
            } == true })

            val link = result.toolCalls.single { it.toolName == "preview_project" }.details.substringAfter("](").substringBeforeLast(")")
            assertTrue(link.startsWith("http://127.0.0.1:"))
            val loaded = CountDownLatch(1)
            val resultText = AtomicReference<String>()
            lateinit var browser: WebView
            instrumentation.runOnMainSync {
                browser = WebView(context)
                browser.settings.javaScriptEnabled = true
                browser.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript("document.getElementById('result').textContent") {
                            resultText.set(it)
                            loaded.countDown()
                        }
                    }
                }
                browser.loadUrl(link)
            }
            try {
                assertTrue("Chromium rendered the project", loaded.await(15, TimeUnit.SECONDS))
                assertEquals("\"42\"", resultText.get())
            } finally {
                instrumentation.runOnMainSync { browser.destroy() }
            }
        } finally {
            written.forEach { context.contentResolver.delete(it, null, null) }
            project.deleteRecursively()
        }
    }

    @Test fun createsARealPowerPointInAndroidDownloads() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Owned QA emulator only" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa-slides-${UUID.randomUUID().toString().take(8)}"
        val result = PhoneResourceController(context).executeSinglePhoneActionObject(JSONObject()
            .put("action", "create_presentation").put("name", name).put("title", "Проверка")
            .put("slides", JSONArray().put(JSONObject().put("title", "Проверочный слайд")
                .put("bullets", JSONArray().put("Создан на телефоне")))))
        assertTrue(result?.outputOrDiff.orEmpty(), result?.success == true)
        val resolver = context.contentResolver
        val downloads = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.query(downloads, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf("$name.pptx"), null)?.use { cursor ->
            check(cursor.moveToFirst()) { "The PPTX was not published in Downloads" }
            ContentUris.withAppendedId(downloads, cursor.getLong(0))
        } ?: error("Could not query Downloads")
        try {
            assertTrue(resolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { pptx ->
                    generateSequence { pptx.nextEntry }.any { entry ->
                        entry.name == "ppt/slides/slide1.xml" && pptx.readBytes().decodeToString().contains("Создан на телефоне")
                    }
                }
            } == true)
        } finally {
            resolver.delete(uri, null, null)
        }
    }
}
