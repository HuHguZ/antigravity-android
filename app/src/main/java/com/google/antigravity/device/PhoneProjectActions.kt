package com.google.antigravity.device

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.antigravity.device.PhoneResourceController.PhoneActionExecutionResult
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

internal class PhoneProjectActions(
    private val context: Context,
    private val root: File,
    private val readFile: (String, JSONObject) -> PhoneActionExecutionResult
) {
    fun execute(action: String, input: JSONObject): PhoneActionExecutionResult = try {
        when (action) {
            "list_projects" -> {
                val projects = listPhoneProjects(root)
                result(action, root.path, "Проекты на телефоне", projects.take(100).joinToString("\n").ifBlank { "Проектов пока нет." })
            }
            "create_project" -> {
                val files = objects(input, "files", 64).map { entry ->
                    PhoneProjectFile(entry.getString("path"), entry.getString("content"))
                }
                val project = createPhoneProject(root, input.optString("name"), files)
                val saved = "Папка проекта: ${project.path}\nФайлов записано: ${files.size}"
                val details = try {
                    "$saved\nАрхив в разделе Загрузки → Antigravity: ${export(project)}"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    "$saved\nАрхив пока недоступен. Его можно сохранить действием export_project."
                }
                result(action, project.path, "Проект создан на телефоне", details)
            }
            "create_presentation" -> createPresentation(input)
            else -> withProject(action, openPhoneProject(root, input.optString("name")), input)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IllegalArgumentException) {
        result(action, "", "Действие проекта не выполнено", failure.message.orEmpty(), success = false)
    } catch (_: org.json.JSONException) {
        result(action, "", "Действие проекта не выполнено", "Проверьте параметры действия проекта.", success = false)
    } catch (_: IOException) {
        result(action, "", "Действие проекта не выполнено", "Проверьте наличие файла и свободное место на телефоне.", success = false)
    } catch (_: SecurityException) {
        result(action, "", "Действие проекта не выполнено", "Android не разрешил доступ к выбранному месту сохранения.", success = false)
    }

    fun importZip(uri: Uri): String {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }.orEmpty()
        val candidate = displayName.substringBeforeLast('.').replace(Regex("[^\\p{L}\\p{N} ._()-]"), "_")
            .trim(' ', '.').take(72).trimEnd(' ', '.').ifBlank { "Импортированный проект" }
        val base = if (candidate.first() in "()") "_$candidate" else candidate
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }
            .first { !File(root, it).exists() }
        val stream = resolver.openInputStream(uri) ?: throw IOException("Не удалось открыть ZIP.")
        return stream.use { importPhoneProjectZip(root, name, it).name }
    }

    suspend fun testWeb(input: JSONObject): PhoneActionExecutionResult {
        val action = "test_web_project"
        return try {
            val project = openPhoneProject(root, input.optString("name"))
            val checks = objects(input, "checks", 20)
            require(checks.all { it.optString("selector").length in 1..240 && it.optString("text").length <= 400 }) {
                "Укажите CSS-селекторы и, при необходимости, ожидаемый текст."
            }
            val output = testPhoneWebProject(context, project, input.optString("entry").ifBlank { "index.html" }, JSONArray(checks))
            var passed = 0
            val details = checks.mapIndexed { index, check ->
                val observed = output.optJSONObject(index)
                val ok = observed?.optBoolean("ok") == true
                if (ok) passed++
                "${if (ok) "OK" else "Ошибка"} ${check.getString("selector")}: ${observed?.optString("actual").orEmpty()}"
            }.joinToString("\n")
            result(action, project.path, "Проверки в браузере: $passed из ${checks.size}", details, success = passed == checks.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IllegalArgumentException) {
            result(action, "", "Тест в браузере не выполнен", failure.message.orEmpty(), success = false)
        } catch (_: Exception) {
            result(action, "", "Тест в браузере не выполнен", "Не удалось загрузить локальную страницу или получить результаты.", success = false)
        }
    }

    private fun withProject(action: String, project: File, input: JSONObject): PhoneActionExecutionResult = when (action) {
        "list_project_files" -> {
            val offset = input.optInt("offset", 0).coerceAtLeast(0)
            val limit = input.optInt("limit", 50).coerceIn(1, 100)
            val paths = listPhoneProjectFiles(project, offset, limit + 1)
            val details = paths.take(limit).joinToString("\n").ifEmpty { "Файлов на этой странице нет." } +
                if (paths.size > limit) "\nСледующая страница: offset=${offset + limit}" else ""
            result(action, project.path, "Список файлов проекта", details)
        }
        "read_project_file" -> {
            val file = phoneProjectPath(project, input.getString("path"))
            require(file.isFile) { "Файл проекта не найден." }
            readFile(file.path, input).copy(actionType = action)
        }
        "write_project_file", "patch_project_file" -> {
            val path = input.getString("path")
            val file = phoneProjectPath(project, path)
            val content = if (action == "patch_project_file") {
                require(file.isFile && file.length() <= 2L * 1024 * 1024) { "Нет подходящего текстового файла для точечной замены." }
                val target = input.getString("target")
                val current = file.readText()
                val start = current.indexOf(target)
                require(target.isNotEmpty() && start >= 0 && current.indexOf(target, start + 1) < 0) {
                    "Искомый фрагмент должен встречаться в файле ровно один раз."
                }
                current.replaceRange(start, start + target.length, input.getString("replacement"))
            } else input.getString("content")
            val saved = writePhoneProjectFile(project, path, content)
            result(action, saved.path, "Файл проекта записан", "$path — ${saved.length()} Б")
        }
        "check_project" -> {
            val checks = objects(input, "files", 64)
            var passed = 0
            val details = checks.joinToString("\n") { check ->
                val path = check.getString("path")
                val file = phoneProjectPath(project, path)
                val contains = check.opt("contains") as? String
                val ok = file.isFile && (contains == null || file.length() <= 2L * 1024 * 1024 && file.readText().contains(contains))
                if (ok) passed++
                "${if (ok) "OK" else "Ошибка"} $path"
            }
            result(action, project.path, "Проверено файлов: $passed из ${checks.size}", details, success = passed == checks.size)
        }
        "preview_project" -> {
            val url = startPhoneProjectPreview(project, input.optString("entry").ifBlank { "index.html" })
            result(action, url, "Превью запущено на этом телефоне", "[Открыть проект в браузере]($url)")
        }
        "export_project" -> {
            val name = export(project)
            result(action, "Download/Antigravity/$name", "ZIP сохранён на телефоне", "Загрузки → Antigravity → $name")
        }
        else -> error("Неизвестное действие проекта")
    }

    private fun createPresentation(input: JSONObject): PhoneActionExecutionResult {
        val name = phoneArtifactName(input.optString("name").trim().let { if (it.endsWith(".pptx", true)) it.dropLast(5) else it })
        val title = input.optString("title").ifBlank { name }
        val slides = objects(input, "slides", 40).map { slide ->
            val bullets = slide.getJSONArray("bullets")
            require(bullets.length() <= 8) { "Используйте до восьми пунктов на слайде." }
            PhonePresentationSlide(slide.getString("title"), List(bullets.length()) { bullets.getString(it) })
        }
        val file = writePhoneDownload(context, "$name.pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation") { output ->
            writePhonePresentation(output, title, slides)
        }
        return result("create_presentation", "Download/Antigravity/$file", "Презентация сохранена на телефоне",
            "Загрузки → Antigravity → $file\nСлайдов: ${slides.size}")
    }

    private fun export(project: File): String = writePhoneDownload(context, "${project.name}.zip", "application/zip") { output ->
        writePhoneProjectZip(project, output)
    }

    private fun objects(input: JSONObject, key: String, max: Int): List<JSONObject> {
        val array = input.optJSONArray(key)
        require(array != null && array.length() in 1..max) { "Укажите массив $key с количеством элементов от 1 до $max." }
        return List(array.length()) { array.getJSONObject(it) }
    }

    companion object {
        val names = setOf("create_project", "list_projects", "list_project_files", "read_project_file", "write_project_file",
            "patch_project_file", "check_project", "preview_project", "export_project", "create_presentation")
    }

    private fun result(action: String, target: String, summary: String, details: String, success: Boolean = true) =
        PhoneActionExecutionResult(action, target, success, summary, details)
}
