import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.AnnotatedString.Range
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.github.difflib.DiffUtils
import com.github.difflib.patch.AbstractDelta
import com.github.difflib.patch.Chunk
import com.github.difflib.patch.DeltaType
import com.github.difflib.text.DiffRowGenerator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.io.FilenameUtils
import java.io.File

sealed interface FileSelector {
    data class SingleFile(val name: String) : FileSelector

    data class AliasFile(val alias: List<String>) : FileSelector
}


@Serializable
data class Config(
    val dirs: List<String> = emptyList(),
    val dirAlias: List<String> = emptyList(),
    val ignoreHiddenFile: Boolean = true,
    val reuseVersionControlIgnoreConfig: Boolean = true,
    val ignoreFiles: List<String> = emptyList(),
    val fileAlias: List<String> = emptyList()
)

data class DiffDecoration(val text: String, val annotations: List<Range<String>>)

data class DeltaRow(
    val delta: AbstractDelta<String>,
    val oldDiffDecorations: List<DiffDecoration>,
    val newDiffDecorations: List<DiffDecoration>
) {
    val action: AnnotatedString.Builder.(DiffDecoration, Boolean) -> Unit = { it, isEnd ->
        val lastLength = length
        if (isEnd)
            append(it.text)
        else
            appendLine(it.text)
        it.annotations.forEach {
            addStyle(
                SpanStyle(background = if (it.item == "red") Color.Red else Color.Green),
                it.start + lastLength,
                it.end + lastLength
            )
        }
    }
    val oldString =
        buildAnnotatedString {
            oldDiffDecorations.forEachIndexed { index, it ->
                action(it, index == oldDiffDecorations.size - 1)
            }
        }

    val newString =
        buildAnnotatedString {
            newDiffDecorations.forEachIndexed { i, it ->
                action(it, i == newDiffDecorations.size - 1)
            }
        }
}

data class Result(val row: DeltaRow) {
    val source: Chunk<String> get() = row.delta.source
    val target: Chunk<String> get() = row.delta.target
    val type: DeltaType get() = row.delta.type
}

@Composable
@Preview
fun App() {
    var config by remember {
        mutableStateOf(Config())
    }
    var allDiffResult by remember {
        mutableStateOf<List<Pair<FileSelector, List<Result>>>>(emptyList())
    }
    var current by remember {
        mutableStateOf<FileSelector?>(null)
    }
    val generator by rememberUpdatedState(DiffRowGenerator.create()
        .mergeOriginalRevised(true)
        .oldTag { f: Boolean? -> if (f == true) """<annotation color="red">""" else "</annotation>" } //introduce markdown style for strikethrough
        .newTag { f: Boolean? -> if (f == true) """<annotation color="green">""" else "</annotation>" } //introduce markdown style for bold
        .build())
    val currentDiffResult by remember {
        derivedStateOf {
            allDiffResult.firstOrNull {
                current == it.first
            }?.second
        }
    }
    DisposableEffect(config) {
        if (config.dirs.isNotEmpty()) {
            allDiffResult = parseDiffTree(config, generator)
        }

        onDispose {

        }
    }

    println(File(".").absolutePath)
    MaterialTheme {
        Column {
            Button(onClick = {
                config = Json.decodeFromString<Config>(File("1.diffm").readText())
            }) {
                Text("Reload")
            }
            Row {
                LazyColumn {
                    items(allDiffResult, key = {
                        it.first
                    }) {
                        Text(it.first.toString(), modifier = Modifier.clickable {
                            current = it.first
                        })
                    }
                }
                currentDiffResult?.let {
                    Row {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(it, key = {
                                it.source.position
                            }) {
                                Text(
                                    it.row.oldString, modifier = Modifier.padding(8.dp).background(
                                        Color.Gray
                                    )
                                )
                                Text("${it.source.position} ${it.source.changePosition} ${it.source.size()} ${it.type}")
                            }
                        }
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(it, key = {
                                it.target.position
                            }) {
                                Text(
                                    it.row.newString, modifier = Modifier.padding(8.dp).background(
                                        Color.Gray
                                    )
                                )
                                Text("${it.target.position} ${it.target.changePosition} ${it.target.size()} ${it.type}")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun parseDiffTree(
    config: Config,
    generator: DiffRowGenerator
): List<Pair<FileSelector, List<Result>>> {
    val first = config.dirs.first()
    val last = config.dirs.last()
    val firstChildren = allChild(first, first, config)
    val lastChildren = allChild(last, last, config)
    val firstSet = firstChildren.toSet()
    val lastSet = lastChildren.toSet()
    val firstOnly = firstSet.minus(lastSet)
    val rightOnly = lastSet.minus(firstSet)
    val common = lastSet.minus(firstOnly + rightOnly)
    println(common)
    println(firstOnly)
    println(rightOnly)
    return (common + firstOnly + rightOnly).map { selector ->
        val diff = DiffUtils.diff(readFileContent(first, selector), readFileContent(last, selector), null)
        selector to diff.deltas.map { delta ->
            if (delta.type == DeltaType.EQUAL) {
                Result(DeltaRow(delta, emptyList(), emptyList()))
            } else {
                val diffRows = generator.generateDiffRows(delta.source.lines, delta.target.lines)
                Result(
                    DeltaRow(delta, diffRows.map {
                        parse(it.oldLine)
                    }, diffRows.map {
                        parse(it.newLine)
                    }),
                )
            }
        }
    }.filter { it.second.isNotEmpty() }
}

fun readFileContent(first: String, selector: FileSelector): String {
    return when (selector) {
        is FileSelector.AliasFile -> {
            selector.alias.firstNotNullOf {
                readFileContent(first, it)
            }
        }

        is FileSelector.SingleFile -> {
            readFileContent(first, selector.name).orEmpty()
        }
    }
}

private fun readFileContent(first: String, name: String) =
    File(first, name).takeIf { it.exists() }?.readText()

val annotationRegex = Regex("""<annotation color="(\w+)">([\w\W]*?)</annotation>""")

fun parse(text: String): DiffDecoration {
    val annotations = mutableListOf<Range<String>>()
    return DiffDecoration(text.loop {
        val result = annotationRegex.find(it)
        if (result != null) {
            val start = result.range.first
            val color = result.groupValues[1]
            val content = result.groupValues[2]
            annotations.add(Range(color, start, start + content.length))
            StringBuilder(it).apply {
                replace(start, result.range.last, content)
            }.toString()
        } else {
            null
        }
    }, annotations)
}

fun <T : Any> T.loop(block: (T) -> T?): T {
    var t = this
    while (true) {
        val block1 = block(t) ?: break
        t = block1
    }
    return t
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication) {
        App()
    }
}

/**
 * 返回的结果是相对路径
 */
fun allChild(parent: String, root: String, config: Config): List<FileSelector> {
    return File(parent).listFiles().orEmpty().flatMap { file ->
        val element = file.absolutePath.substring(root.length + 1)
        when {
            config.ignoreHiddenFile && file.isHidden -> emptyList()
            config.ignoreFiles.any {
                FilenameUtils.wildcardMatch(element, it)
            } -> emptyList()

            file.isFile -> {
                if (config.fileAlias.contains(element)) {
                    listOf(FileSelector.AliasFile(config.fileAlias))
                } else {
                    listOf(FileSelector.SingleFile(element))
                }
            }

            else -> allChild(file.absolutePath, root, config)
        }
    }
}