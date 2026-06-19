package io.liriliri.aya.ui.device

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.liriliri.aya.adb.DeviceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory

@HiltViewModel
class LayoutViewModel @Inject constructor(
    private val deviceManager: DeviceManager
) : ViewModel() {
    private val _screenshot = MutableStateFlow<ByteArray?>(null)
    val screenshot: StateFlow<ByteArray?> = _screenshot.asStateFlow()

    private val _hierarchy = MutableStateFlow<List<LayoutNode>>(emptyList())
    val hierarchy: StateFlow<List<LayoutNode>> = _hierarchy.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _selectedNode = MutableStateFlow<LayoutNode?>(null)
    val selectedNode: StateFlow<LayoutNode?> = _selectedNode.asStateFlow()

    fun refresh(deviceId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val sc = deviceManager.screencap(deviceId)
                _screenshot.value = sc

                val xml = deviceManager.dumpWindowHierarchy(deviceId)
                _hierarchy.value = parseHierarchyXml(xml)
            } catch (_: Exception) {} finally {
                _isLoading.value = false
            }
        }
    }

    fun selectNode(node: LayoutNode?) { _selectedNode.value = node }

    private fun parseHierarchyXml(xml: String): List<LayoutNode> {
        val cleanXml = xml.substringAfter("UI hierarchy dumped to:").trim()
        if (!cleanXml.startsWith("<")) return emptyList()

        return try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(cleanXml.toByteArray()))
            val root = doc.documentElement
            parseNode(root, 0)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseNode(element: Element, depth: Int): List<LayoutNode> {
        val nodes = mutableListOf<LayoutNode>()
        val className = element.getAttribute("class").substringAfterLast(".")
        val resourceId = element.getAttribute("resource-id")
        val text = element.getAttribute("text")
        val bounds = element.getAttribute("bounds")
        val contentDesc = element.getAttribute("content-desc")
        val enabled = element.getAttribute("enabled") == "true"
        val clickable = element.getAttribute("clickable") == "true"

        nodes.add(LayoutNode(
            depth = depth,
            className = className,
            resourceId = resourceId,
            text = text,
            contentDescription = contentDesc,
            bounds = bounds,
            enabled = enabled,
            clickable = clickable
        ))

        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element && child.tagName == "node") {
                nodes.addAll(parseNode(child, depth + 1))
            }
        }

        return nodes
    }
}

data class LayoutNode(
    val depth: Int,
    val className: String,
    val resourceId: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val bounds: String = "",
    val enabled: Boolean = true,
    val clickable: Boolean = false
)

@Composable
fun LayoutPanel(
    deviceId: String,
    viewModel: LayoutViewModel = hiltViewModel()
) {
    val screenshot by viewModel.screenshot.collectAsState()
    val hierarchy by viewModel.hierarchy.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedNode by viewModel.selectedNode.collectAsState()

    LaunchedEffect(deviceId) {
        viewModel.refresh(deviceId)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { viewModel.refresh(deviceId) },
                enabled = !isLoading,
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("刷新")
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "${hierarchy.size} 个元素",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(modifier = Modifier.fillMaxSize()) {
            // Hierarchy tree
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                items(hierarchy) { node ->
                    val isSelected = selectedNode == node
                    Card(
                        onClick = { viewModel.selectNode(node) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (node.depth * 12).dp, end = 4.dp, top = 1.dp, bottom = 1.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Column(modifier = Modifier.padding(4.dp)) {
                            Text(
                                node.className,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                fontSize = 11.sp,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (node.resourceId.isNotBlank()) {
                                Text(
                                    node.resourceId.substringAfter("/"),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (node.text.isNotBlank()) {
                                Text(
                                    "\"${node.text}\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // Detail pane
            selectedNode?.let { node ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(8.dp)
                ) {
                    Text("节点详情", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))

                    screenshot?.let { data ->
                        val bitmap = remember(data) {
                            BitmapFactory.decodeByteArray(data, 0, data.size)?.asImageBitmap()
                        }
                        bitmap?.let {
                            Image(
                                bitmap = it,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn {
                        item { DetailRow("类名", node.className) }
                        item { DetailRow("ID", node.resourceId) }
                        item { DetailRow("文本", node.text) }
                        item { DetailRow("描述", node.contentDescription) }
                        item { DetailRow("边界", node.bounds) }
                        item { DetailRow("可点击", if (node.clickable) "是" else "否") }
                        item { DetailRow("已启用", if (node.enabled) "是" else "否") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
