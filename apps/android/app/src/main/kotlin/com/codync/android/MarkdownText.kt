package com.codync.android

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.ImageLoader
import coil3.disk.DiskCache
import java.io.File
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension

private val markdown = Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
    .maxOpenBlockParsers(100).build()
val LocalNetworkImages = staticCompositionLocalOf<ImageLoader> { error("Missing image loader") }

@Composable fun MarkdownText(text: String, selectable: Boolean = true) {
    val document = remember(text) { markdown.parse(text) }
    val content: @Composable () -> Unit = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { MarkdownNodes(document, selectable) } }
    if (selectable) SelectionContainer(content = content) else content()
}

private fun children(node: Node): List<Node> = buildList {
    var child = node.firstChild
    while (child != null) { add(child); child = child.next }
}

@Composable private fun MarkdownNodes(parent: Node, selectable: Boolean) {
    for (node in children(parent)) when (node) {
        is Heading -> Text(inlines(node), style = when (node.level) {
            1 -> MaterialTheme.typography.headlineMedium
            2 -> MaterialTheme.typography.titleLarge
            else -> MaterialTheme.typography.titleMedium
        })
        is FencedCodeBlock -> CodeText(node.literal, selectable)
        is IndentedCodeBlock -> CodeText(node.literal, selectable)
        is Paragraph -> {
            Text(inlines(node))
            for (image in images(node)) MarkdownImage(image)
        }
        is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            children(node).forEachIndexed { index, item -> Row {
                Text(if (node is OrderedList) (index + (node.markerStartNumber ?: 1)).toString() + ". " else "• ")
                Column(Modifier.weight(1f)) { MarkdownNodes(item, selectable) }
            } }
        }
        is BlockQuote -> Column(Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) { MarkdownNodes(node, selectable) }
        is ThematicBreak -> Text("────", color = MaterialTheme.colorScheme.onSurfaceVariant)
        is HtmlBlock -> Text(node.literal) // Untrusted HTML is shown as text, never executed.
        else -> if (node.javaClass.simpleName == "TableBlock") {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                for (section in children(node)) for (row in children(section)) Row {
                    for (cell in children(row)) Text(inlines(cell), Modifier.widthIn(min = 100.dp, max = 220.dp).padding(8.dp))
                }
            }
        } else MarkdownNodes(node, selectable)
    }
}

private fun images(node: Node): List<Image> = children(node).flatMap { if (it is Image) listOf(it) else images(it) }

@Composable private fun MarkdownImage(image: Image) {
    val url = image.destination.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return
    val loader = LocalNetworkImages.current
    var error by remember(url) { mutableStateOf(false) }
    AsyncImage(model = url, imageLoader = loader, contentDescription = inlines(image).text,
        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp), onError = { error = true }, onSuccess = { error = false })
    if (error) Text("Image unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable fun CodeText(text: String, selectable: Boolean = true) {
    val content: @Composable () -> Unit = {
        Text(text.trimEnd(), Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState()).padding(12.dp), fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall)
    }
    if (selectable) SelectionContainer(content = content) else content()
}

private fun inlines(node: Node): AnnotatedString = buildAnnotatedString {
    fun visit(current: Node) {
        val style = when (current) {
            is StrongEmphasis -> SpanStyle(fontWeight = FontWeight.Bold)
            is Emphasis -> SpanStyle(fontStyle = FontStyle.Italic)
            is Code -> SpanStyle(fontFamily = FontFamily.Monospace)
            is Strikethrough -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            else -> null
        }
        if (style != null) pushStyle(style)
        val link = (current as? Link)?.destination?.takeIf { it.startsWith("https://") || it.startsWith("http://") || it.startsWith("mailto:") }
        if (link != null) pushLink(LinkAnnotation.Url(link))
        when (current) {
            is org.commonmark.node.Text -> append(current.literal)
            is Code -> append(current.literal)
            is SoftLineBreak, is HardLineBreak -> append("\n")
            is HtmlInline -> append(current.literal)
            is Image -> { append("[Image: "); children(current).forEach(::visit); append("]") }
            else -> children(current).forEach(::visit)
        }
        if (link != null) pop()
        if (style != null) pop()
    }
    children(node).forEach(::visit)
}
