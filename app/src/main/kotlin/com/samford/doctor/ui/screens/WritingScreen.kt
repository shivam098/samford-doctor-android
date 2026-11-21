package com.samford.doctor.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixNormal
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.samford.doctor.R
import com.samford.doctor.editor.EditorDoc
import com.samford.doctor.editor.EditorNotice
import com.samford.doctor.editor.EditorState
import com.samford.doctor.editor.EraserMode
import com.samford.doctor.editor.InkColor
import com.samford.doctor.editor.PenWidth
import com.samford.doctor.editor.ToolKind
import com.samford.doctor.editor.ToolState
import com.samford.doctor.editor.VisitEditor
import com.samford.doctor.ink.InkPage
import com.samford.doctor.ink.StampCatalog
import com.samford.doctor.ink.StampTemplate
import com.samford.doctor.model.DocType
import com.samford.doctor.model.Lab
import com.samford.doctor.render.PageCompositor
import com.samford.doctor.render.PageRasterizer
import com.samford.doctor.render.PageSpec
import com.samford.doctor.render.RenderMode
import com.samford.doctor.ui.AppViewModel
import com.samford.doctor.ui.Loading
import com.samford.doctor.ui.Screen
import com.samford.doctor.ui.StatusPill
import com.samford.doctor.ui.VisitInfo
import com.samford.doctor.ui.VisitLoad
import com.samford.doctor.ui.VisitSession
import com.samford.doctor.ui.theme.AlertRed
import com.samford.doctor.ui.theme.Desk
import com.samford.doctor.ui.theme.SamfordBlue
import com.samford.doctor.ui.write.InkSurfaceView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun VisitScreen(vm: AppViewModel, visitId: String) {
    val session = remember(visitId) { vm.visitSession(visitId) }
    val load by session.load.collectAsStateWithLifecycle()
    when (val l = load) {
        VisitLoad.Loading -> Loading()
        is VisitLoad.Failed -> Loading(l.message)
        is VisitLoad.Ready -> WritingScreen(
            session = session,
            editor = l.editor,
            info = l.info,
            onBack = { vm.leaveVisit(visitId) },
            primaryLabel = stringResource(R.string.action_review_complete),
            onPrimary = { vm.navigate(Screen.Review(visitId)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WritingScreen(
    session: VisitSession,
    editor: VisitEditor,
    info: VisitInfo,
    onBack: () -> Unit,
    primaryLabel: String,
    onPrimary: () -> Unit,
    extraHeader: @Composable () -> Unit = {},
) {
    val state by editor.state.collectAsStateWithLifecycle()
    val tool by session.tool.collectAsStateWithLifecycle()
    val showHistory by session.showHistory.collectAsStateWithLifecycle()
    val labs by session.labs.collectAsStateWithLifecycle()
    var stampSheet by remember { mutableStateOf(false) }
    val doc = state.currentDoc

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                title = { PatientHeader(info) },
                actions = {
                    if (!session.isAddendum) {
                        IconButton(onClick = { session.showHistory.value = !showHistory }, modifier = Modifier.testTag("toggle-history")) {
                            Icon(Icons.Filled.History, stringResource(R.string.history_toggle), tint = if (showHistory) SamfordBlue else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Button(onClick = onPrimary, modifier = Modifier.padding(horizontal = 8.dp).testTag("primary-action"), enabled = !editor.readOnly) { Text(primaryLabel) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            extraHeader()
            if (!session.isAddendum) DocTabs(state, editor)
            if (doc.type == DocType.INVESTIGATIONS && !session.isAddendum) LabRow(labs, doc) { session.toggleLab(doc.docId, it) }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (showHistory && !session.isAddendum) {
                    HistoryPane(session, Modifier.weight(0.42f).fillMaxHeight())
                }
                Box(Modifier.weight(1f).fillMaxHeight().background(Desk)) {
                    WritingCanvas(session, editor, info, state, tool)
                    CanvasOverlays(session, editor, state, tool, Modifier.align(Alignment.TopCenter))
                }
                ToolRail(
                    tool = tool,
                    state = state,
                    onTool = { session.setTool(it) },
                    onUndo = editor::undo,
                    onRedo = editor::redo,
                    onStamps = { stampSheet = true },
                    onFit = { session.zoomReset.value += 1 },
                    modifier = Modifier.width(84.dp).fillMaxHeight(),
                )
            }
            PageStrip(session, editor, info, state)
        }
    }
    if (stampSheet) StampSheet(onPick = { session.armStamp(it); stampSheet = false }, onDismiss = { stampSheet = false })
}

@Composable
private fun PatientHeader(info: VisitInfo) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(info.patient.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${info.patient.ageSex} · UHID ${info.patient.uhid} · ${stringResource(R.string.token_label, info.visit.token)}", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            if (info.visit.returning) StatusPill(stringResource(R.string.returning_patient), SamfordBlue)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.allergies_label, info.patient.allergyText),
                color = if (info.patient.hasAllergies) AlertRed else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (info.patient.hasAllergies) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(info.vitals?.summary() ?: stringResource(R.string.vitals_not_recorded), style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}

@Composable
private fun DocTabs(state: EditorState, editor: VisitEditor) {
    LazyRow(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(state.docs, key = { _, d -> d.docId }) { _, d ->
            val selected = d.docId == state.currentDocId
            val hasInk = editor.hasContent(d.docId)
            FilterChip(
                selected = selected,
                onClick = { editor.selectDocument(d.docId) },
                label = {
                    Text(docLabel(d.type) + if (d.pageIds.size > 1) " (${d.pageIds.size})" else "", fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                },
                leadingIcon = if (hasInk) ({ Box(Modifier.size(8.dp).background(SamfordBlue, CircleShape)) }) else null,
                modifier = Modifier.testTag("doc-${d.type.name}").semantics { this.selected = selected },
            )
        }
    }
}

@Composable
fun docLabel(type: DocType): String = when (type) {
    DocType.VISIT_SUMMARY -> stringResource(R.string.doc_visit_summary)
    DocType.PRESCRIPTION -> stringResource(R.string.doc_prescription)
    DocType.INVESTIGATIONS -> stringResource(R.string.doc_investigations)
    DocType.ADVICE_REFERRAL -> stringResource(R.string.doc_advice)
    DocType.OTHER -> stringResource(R.string.doc_other)
}

@Composable
private fun LabRow(labs: List<Lab>, doc: EditorDoc, onToggle: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.labs_send_to), style = MaterialTheme.typography.labelLarge)
        labs.forEach { lab ->
            FilterChip(
                selected = lab.labId in doc.labs,
                onClick = { onToggle(lab.labId) },
                label = { Text(lab.name) },
                modifier = Modifier.testTag("lab-${lab.labId}"),
            )
        }
        if (doc.labs.isEmpty()) Text(stringResource(R.string.labs_none_selected), color = AlertRed, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun WritingCanvas(session: VisitSession, editor: VisitEditor, info: VisitInfo, state: EditorState, tool: ToolState) {
    val zoomReset by session.zoomReset.collectAsStateWithLifecycle()
    val reason by session.addendumReason.collectAsStateWithLifecycle()
    val doc = state.currentDoc
    val pageId = editor.currentPageId
    val page = editor.page(pageId)
    val spec = remember(doc, state.pageIndex, info, reason) { session.pageSpec(info, doc, state.pageIndex) }
    AndroidView(
        factory = { ctx ->
            InkSurfaceView(ctx).apply {
                listener = session.surfaceListener
                editable = !editor.readOnly
                tag = 0
            }
        },
        update = { v ->
            v.tool = tool
            v.setContent(pageId, spec, page, state.selection)
            if (v.tag != zoomReset) {
                v.tag = zoomReset
                v.resetZoom()
            }
        },
        modifier = Modifier.fillMaxSize().testTag("ink-surface").semantics { contentDescription = "page ${state.pageIndex + 1} revision ${state.revision}" },
    )
}

@Composable
private fun CanvasOverlays(session: VisitSession, editor: VisitEditor, state: EditorState, tool: ToolState, modifier: Modifier) {
    Column(modifier.padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (tool.kind == ToolKind.STAMP && tool.stamp != null) {
            Surface(shape = RoundedCornerShape(50), color = SamfordBlue, shadowElevation = 2.dp) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.stamp_tap_to_place, tool.stamp.text), color = Color.White)
                    TextButton(onClick = { session.setTool { it.copy(kind = ToolKind.PEN, stamp = null) } }) { Text(stringResource(R.string.action_cancel), color = Color.White) }
                }
            }
        }
        if (state.selection != null) {
            Surface(shape = RoundedCornerShape(50), shadowElevation = 2.dp) {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.selection_hint), modifier = Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = editor::deleteSelection, modifier = Modifier.testTag("selection-delete")) { Icon(Icons.Filled.Delete, stringResource(R.string.action_delete)) }
                    IconButton(onClick = editor::clearSelection) { Icon(Icons.Filled.Done, stringResource(R.string.action_done)) }
                }
            }
        }
        val notice = state.notice
        if (notice is EditorNotice.PageAdded && notice.docId == state.currentDocId) {
            LaunchedEffect(notice) {
                delay(8000)
                editor.consumeNotice()
            }
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer, shadowElevation = 2.dp) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.page_added, notice.pageNumber))
                    TextButton(onClick = { editor.selectPage(notice.pageNumber - 1); editor.consumeNotice() }, modifier = Modifier.testTag("go-next-page")) {
                        Text(stringResource(R.string.page_go_next))
                    }
                }
            }
        }
        if (editor.readOnly) StatusPill(stringResource(R.string.read_only_completed), AlertRed)
    }
}

@Composable
private fun ToolRail(
    tool: ToolState,
    state: EditorState,
    onTool: ((ToolState) -> ToolState) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onStamps: () -> Unit,
    onFit: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Undo/redo first: the most-used actions stay visible on an 800 dp tall landscape tablet.
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(onClick = onUndo, enabled = state.canUndo, modifier = Modifier.size(38.dp).testTag("tool-undo")) {
                Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.tool_undo))
            }
            IconButton(onClick = onRedo, enabled = state.canRedo, modifier = Modifier.size(38.dp).testTag("tool-redo")) {
                Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.tool_redo))
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 12.dp))
        RailButton(Icons.Filled.Edit, stringResource(R.string.tool_pen), tool.kind == ToolKind.PEN, "tool-pen") { onTool { it.copy(kind = ToolKind.PEN, stamp = null) } }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            InkColor.entries.forEach { color ->
                val selected = tool.color == color && tool.kind == ToolKind.PEN
                Box(
                    Modifier
                        .size(24.dp)
                        .border(if (selected) 3.dp else 1.dp, if (selected) SamfordBlue else Color.LightGray, CircleShape)
                        .padding(4.dp)
                        .background(Color(com.samford.doctor.render.StrokePainter.parseColor(color.hex)), CircleShape)
                        .clickable { onTool { it.copy(kind = ToolKind.PEN, color = color, stamp = null) } }
                        .testTag("color-${color.name}"),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            PenWidth.entries.forEach { width ->
                val selected = tool.width == width && tool.kind == ToolKind.PEN
                Box(
                    Modifier.size(24.dp).clickable { onTool { it.copy(kind = ToolKind.PEN, width = width, stamp = null) } }.testTag("width-${width.name}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size((width.cu / 30).dp).background(if (selected) SamfordBlue else Color.Gray, CircleShape))
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 12.dp))
        RailButton(Icons.Filled.BorderColor, stringResource(R.string.tool_highlighter), tool.kind == ToolKind.HIGHLIGHTER, "tool-highlighter") { onTool { it.copy(kind = ToolKind.HIGHLIGHTER, stamp = null) } }
        RailButton(
            Icons.Filled.AutoFixNormal,
            if (tool.eraserMode == EraserMode.STROKE) stringResource(R.string.tool_eraser_stroke) else stringResource(R.string.tool_eraser_area),
            tool.kind == ToolKind.ERASER,
            "tool-eraser",
        ) {
            onTool { if (it.kind == ToolKind.ERASER) it.copy(eraserMode = if (it.eraserMode == EraserMode.STROKE) EraserMode.AREA else EraserMode.STROKE) else it.copy(kind = ToolKind.ERASER, stamp = null) }
        }
        RailButton(Icons.Filled.Gesture, stringResource(R.string.tool_lasso), tool.kind == ToolKind.LASSO, "tool-lasso") { onTool { it.copy(kind = ToolKind.LASSO, stamp = null) } }
        RailButton(Icons.Filled.LocalOffer, stringResource(R.string.tool_stamps), tool.kind == ToolKind.STAMP, "tool-stamps", onClick = onStamps)
        HorizontalDivider(Modifier.padding(horizontal = 12.dp))
        RailButton(
            Icons.Filled.PanTool,
            if (tool.fingerWriting) stringResource(R.string.tool_finger_on) else stringResource(R.string.tool_finger_off),
            tool.fingerWriting,
            "tool-finger",
        ) { onTool { it.copy(fingerWriting = !it.fingerWriting) } }
        RailButton(Icons.Filled.FitScreen, stringResource(R.string.tool_fit), false, "tool-fit", onClick = onFit)
    }
}

@Composable
private fun RailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    tag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(76.dp)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp)
            .testTag(tag)
            .semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (!enabled) Color.LightGray else if (selected) SamfordBlue else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, fontSize = 11.sp, maxLines = 2, color = if (enabled) MaterialTheme.colorScheme.onSurface else Color.LightGray)
    }
}

@Composable
private fun PageStrip(session: VisitSession, editor: VisitEditor, info: VisitInfo, state: EditorState) {
    val doc = state.currentDoc
    LazyRow(
        Modifier.fillMaxWidth().height(112.dp).background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(doc.pageIds, key = { _, id -> id }) { index, pageId ->
            val selected = index == state.pageIndex
            val page = editor.page(pageId)
            val spec = session.pageSpec(info, doc, index)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { editor.selectPage(index) }.testTag("page-thumb-$index")) {
                PageThumbnail(spec, page, Modifier.height(78.dp).aspectRatio(1000f / 1414f).border(if (selected) 3.dp else 1.dp, if (selected) SamfordBlue else Color.LightGray))
                Text(stringResource(R.string.page_number, index + 1), fontSize = 11.sp)
            }
        }
        item {
            FilledTonalButton(onClick = { editor.addPage() }, enabled = !editor.readOnly, modifier = Modifier.testTag("add-page")) {
                Icon(Icons.Filled.Add, null)
                Text(stringResource(R.string.page_add))
            }
        }
    }
}

@Composable
fun PageThumbnail(spec: PageSpec, page: InkPage, modifier: Modifier, widthPx: Int = 140, mode: RenderMode = RenderMode.SCREEN) {
    val image by produceState<ImageBitmap?>(null, spec, page) {
        delay(250)
        value = withContext(Dispatchers.Default) {
            PageRasterizer.render(spec, page, PageCompositor(), mode, widthPx).asImageBitmap()
        }
    }
    Box(modifier.background(Color.White).testTag(if (image == null) "page-image-loading" else "page-image-ready"), contentAlignment = Alignment.Center) {
        val img = image
        if (img == null) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp))
        } else {
            Image(img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun StampSheet(onPick: (StampTemplate) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.stamps_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.stamps_hint), style = MaterialTheme.typography.bodyMedium)
            StampGroup(stringResource(R.string.stamps_dosage), StampCatalog.dosage, onPick)
            StampGroup(stringResource(R.string.stamps_food), StampCatalog.food, onPick)
            StampGroup(stringResource(R.string.stamps_duration), StampCatalog.duration, onPick)
            StampGroup(stringResource(R.string.stamps_medicines), StampCatalog.medicines, onPick)
            StampGroup(stringResource(R.string.stamps_tests), StampCatalog.tests, onPick)
            StampGroup(stringResource(R.string.stamps_advice), StampCatalog.advice, onPick)
            StampGroup(stringResource(R.string.stamps_diagrams), StampCatalog.diagrams, onPick)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StampGroup(title: String, items: List<StampTemplate>, onPick: (StampTemplate) -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.labelLarge, color = SamfordBlue)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { t ->
            SuggestionChip(
                onClick = { onPick(t) },
                label = { Text(t.text) },
                border = BorderStroke(1.dp, SamfordBlue.copy(alpha = 0.4f)),
                modifier = Modifier.testTag("stamp-${t.key}"),
            )
        }
    }
}

@Composable
fun CountBadge(count: Int) {
    if (count > 0) Badge { Text("$count") }
}
