package com.vigil.android

import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun updatePackageNames() {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "app").exists()) {
            dir = dir.parentFile
        }
        if (dir != null) {
            val modifiedFiles = mutableListOf<String>()
            dir.walkTopDown().forEach { file ->
                if (file.isFile && (file.extension == "kt" || file.extension == "java" || file.extension == "json" || file.extension == "xml" || file.extension == "pro")) {
                    val content = file.readText(Charsets.UTF_8)
                    if (content.contains("com.vigil.android")) {
                        val newContent = content.replace("com.vigil.android", "com.vigil.android")
                        file.writeText(newContent, Charsets.UTF_8)
                        modifiedFiles.add(file.absolutePath)
                    }
                }
            }
            println("Modified ${modifiedFiles.size} files:")
            modifiedFiles.forEach { println(it) }
        }
    }
}
