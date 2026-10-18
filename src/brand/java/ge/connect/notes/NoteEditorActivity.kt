/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ge.connect.notes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ge.connect.notes.NoteMarkdown.Kind
import ge.connect.ui.theme.AppTheme
import ge.connect.ui.theme.LocalAppSurfaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import org.kde.kdeconnect_tp.R

/**
 * One note (R29/R30). The text is Markdown; it's shown formatted with the syntax hidden (on the cursor's line headings
 * and inline markers show, like Obsidian's live preview). Bullets and checkboxes are drawn; boxes tick on tap, links
 * open on tap. A format bar above the keyboard sets styles. Saved ~0.4 s after typing stops; an empty new note is
 * dropped on leaving.
 */
class NoteEditorActivity : AppCompatActivity() {
    private lateinit var noteId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        noteId = savedInstanceState?.getString(EXTRA_ID) ?: intent.getStringExtra(EXTRA_ID)?.takeIf { NotesStore.get(it) != null }
            ?: NotesStore.create().id
        val isNew = (intent.getStringExtra(EXTRA_ID) == null || intent.getBooleanExtra(EXTRA_EDIT, false)) && savedInstanceState == null
        setContent { AppTheme { Editor(isNew) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_ID, noteId)
    }

    override fun onDestroy() {
        if (isFinishing) NotesStore.discardIfEmpty(noteId)
        super.onDestroy()
    }

    @Composable
    private fun Editor(isNew: Boolean) {
        val all by NotesStore.all.collectAsState()
        val note = all.firstOrNull { it.id == noteId }
        var value by remember { mutableStateOf(TextFieldValue(note?.text.orEmpty(), TextRange(note?.text?.length ?: 0))) }
        // What we last saved: a different text in the store means the note changed elsewhere (e.g. on the Mac).
        var saved by remember { mutableStateOf(note?.text.orEmpty()) }
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        var focused by remember { mutableStateOf(false) }
        var linkDialog by remember { mutableStateOf(false) }
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        val readOnly = note?.deleted != null
        // R30 attachments: decoded previews (images, a PDF's first page), keyed by stored name.
        val images = remember { mutableStateMapOf<String, ImageBitmap>() }
        val filesChanged by NoteAttachments.changed.collectAsState()
        val scope = rememberCoroutineScope()
        val textMeasurer = rememberTextMeasurer()
        val density = LocalDensity.current
        var fieldWidth by remember { mutableStateOf(0) }
        var attachMenu by remember { mutableStateOf(false) }

        LaunchedEffect(note?.text) {
            val text = note?.text ?: return@LaunchedEffect
            if (text != saved && value.text == saved) {
                value = TextFieldValue(text, TextRange(value.selection.start.coerceAtMost(text.length)))
                saved = text
            }
        }
        LaunchedEffect(Unit) {
            snapshotFlow { value.text }.collectLatest { text ->
                if (text == saved) return@collectLatest
                delay(400)
                saved = text
                NotesStore.setText(noteId, text)
            }
        }
        LaunchedEffect(Unit) { if (isNew) focus.requestFocus() }

        fun save() {
            if (value.text != saved) { saved = value.text; NotesStore.setText(noteId, value.text) }
        }
        fun applyEdit(edit: NoteEditing.Edit) {
            value = TextFieldValue(edit.text, TextRange(edit.cursor, edit.selectionEnd))
        }
        /** Copies the picked files into the store and puts a line for each at the cursor. */
        fun attach(uris: List<Uri>) {
            if (uris.isEmpty()) return
            scope.launch {
                val lines = withContext(Dispatchers.IO) { uris.mapNotNull { NoteAttachments.add(applicationContext, it) } }
                if (lines.size < uris.size) android.widget.Toast.makeText(applicationContext, R.string.notes_attach_failed, android.widget.Toast.LENGTH_LONG).show()
                if (lines.isEmpty()) return@launch
                val text = value.text
                val at = value.selection.end.coerceIn(0, text.length)
                val before = if (at > 0 && text[at - 1] != '\n') "\n" else ""
                val after = if (at < text.length && text[at] != '\n') "\n" else if (at == text.length) "\n" else ""
                val inserted = before + lines.joinToString("\n") + after
                value = TextFieldValue(text.substring(0, at) + inserted + text.substring(at), TextRange(at + inserted.length))
            }
        }
        val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { attach(it) }
        val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { attach(it) }
        var cameraUri by remember { mutableStateOf<Uri?>(null) }
        val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> cameraUri?.takeIf { ok }?.let { attach(listOf(it)) } }

        // Load previews for the attachments in this note.
        LaunchedEffect(value.text, filesChanged, fieldWidth) {
            val width = fieldWidth.takeIf { it > 0 } ?: return@LaunchedEffect
            val names = value.text.lineSequence().mapNotNull { NoteAttachments.parse(it)?.second }
                .filter { (NoteAttachments.isImage(it) || NoteAttachments.isPdf(it)) && it !in images && NoteAttachments.has(it) }.toList()
            for (name in names) withContext(Dispatchers.IO) { loadPreview(name, width) }?.let { images[name] = it }
        }

        val colors = MaterialTheme.colorScheme
        val typography = MaterialTheme.typography
        val caretLine = remember(value.text, value.selection) {
            NoteEditing.lineStarts(value.text).count { it <= value.selection.start } - 1
        }
        val theme = remember(colors, typography) {
            NoteTheme(
                title = typography.headlineSmall.toSpanStyle().copy(fontWeight = FontWeight.SemiBold),
                heading = typography.titleLarge.toSpanStyle().copy(fontWeight = FontWeight.SemiBold),
                subheading = typography.titleMedium.toSpanStyle().copy(fontWeight = FontWeight.SemiBold),
                muted = colors.onSurfaceVariant,
                syntax = colors.outline,
                link = colors.primary,
                codeBackground = colors.surfaceContainerHighest,
                text = colors.onSurface,
                indent = 22.sp,
            )
        }
        // How tall each attachment line is drawn (the hidden text gets a font size that makes its line that tall).
        val attachmentSizes = remember(images.toMap(), fieldWidth, density) {
            val width = fieldWidth.takeIf { it > 0 }?.toFloat() ?: return@remember emptyMap<String, Float>()
            fun sp(px: Float) = px / LINE_HEIGHT / density.density / density.fontScale
            value.text.lineSequence().mapNotNull { NoteAttachments.parse(it)?.second }.distinct().associateWith { name ->
                val image = images[name]
                val px = if (image != null) minOf(width * image.height / image.width, width * 1.25f) else if (NoteAttachments.isImage(name) || NoteAttachments.isPdf(name)) width * 0.56f else with(density) { 64.dp.toPx() }
                sp(px)
            }
        }
        val tableRowSp = with(density) { (TABLE_ROW_HEIGHT.toPx() / LINE_HEIGHT / density.density / density.fontScale).sp }
        val transformation = remember(theme, if (focused) caretLine else -1, attachmentSizes, tableRowSp) {
            NoteTransformation(theme, if (focused) caretLine else -1, attachmentSizes, tableRowSp)
        }
        val background = LocalAppSurfaces.current.background
        val context = this

        Scaffold(
            containerColor = background,
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = { save(); finish() }) { Icon(painterResource(R.drawable.ms_arrow_back), stringResource(R.string.navigate_back)) }
                    },
                    actions = {
                        if (!readOnly) {
                            IconButton(onClick = { save(); NotesStore.setPinned(noteId, note?.pinned != true) }) {
                                Icon(
                                    painterResource(if (note?.pinned == true) R.drawable.ms_push_pin_filled else R.drawable.ms_push_pin),
                                    stringResource(if (note?.pinned == true) R.string.notes_unpin else R.string.notes_pin),
                                    tint = if (note?.pinned == true) colors.primary else colors.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                save()
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value.text), null))
                            }) { Icon(painterResource(R.drawable.ms_share), stringResource(R.string.notes_share)) }
                            IconButton(onClick = { save(); NotesStore.delete(noteId); finish() }) {
                                Icon(painterResource(R.drawable.ms_delete), stringResource(R.string.notes_delete))
                            }
                        } else {
                            IconButton(onClick = { NotesStore.restore(noteId) }) {
                                Icon(painterResource(R.drawable.ms_restore_from_trash), stringResource(R.string.notes_restore))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).imePadding()) {
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val minHeight = maxHeight
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    BasicTextField(
                        value = value,
                        onValueChange = { new ->
                            val old = value
                            value = if (new.text == old.text) {
                                new.copy(selection = if (new.selection.collapsed) TextRange(NoteEditing.snap(new.text, new.selection.start)) else new.selection)
                            } else {
                                val edit = NoteEditing.apply(old.text, old.selection.start, new.text, new.selection.start)
                                TextFieldValue(edit.text, TextRange(edit.cursor), new.composition.takeIf { edit.text == new.text })
                            }
                        },
                        readOnly = readOnly,
                        // No fixed line height: attachment lines are made tall by their (hidden) text's size.
                        textStyle = typography.bodyLarge.copy(color = colors.onSurface, lineHeight = TextUnit.Unspecified),
                        cursorBrush = SolidColor(colors.primary),
                        visualTransformation = transformation,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        onTextLayout = { layout = it; fieldWidth = it.size.width },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = minHeight)
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                            .focusRequester(focus)
                            .onFocusChanged { focused = it.isFocused }
                            // Taps on a box tick it; taps on a link (not on the line being edited) open it.
                            .pointerInput(readOnly) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    val result = layout ?: return@awaitEachGesture
                                    val text = value.text
                                    val box = if (readOnly) null else boxAt(text, result, down.position, 12.dp.toPx())
                                    val url = if (box == null) linkAt(text, result, down.position, if (focused) caretLine else -1) else null
                                    val attachment = if (box == null && url == null) attachmentAt(text, result, down.position, if (focused) caretLine else -1) else null
                                    val cell = if (box == null && url == null && attachment == null && !readOnly) cellAt(text, result, down.position, if (focused) caretLine else -1, size.width.toFloat()) else null
                                    if (box == null && url == null && attachment == null && cell == null) return@awaitEachGesture
                                    down.consume()
                                    val up = waitForUpOrCancellation(PointerEventPass.Initial) ?: return@awaitEachGesture
                                    up.consume()
                                    if (box != null) value = value.copy(text = Checklist.toggle(value.text, box))
                                    else if (attachment != null) openAttachment(attachment)
                                    else if (cell != null) {
                                        value = value.copy(selection = TextRange(cell))
                                        focus.requestFocus()
                                        keyboard?.show()
                                    }
                                    else runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                }
                            }
                            .drawWithContent {
                                val result = layout
                                if (result != null) drawBlocks(value.text, result, colors.primary, colors.onPrimary, colors.onSurfaceVariant, theme.codeBackground)
                                drawContent()
                                if (result != null) drawTables(value.text, result, if (focused) caretLine else -1, textMeasurer, colors.outlineVariant, colors.onSurface)
                                if (result != null) drawAttachments(value.text, result, if (focused) caretLine else -1, images, textMeasurer, colors.surfaceContainerHigh, colors.onSurface, colors.onSurfaceVariant)
                            },
                        decorationBox = { inner ->
                            Box {
                                if (value.text.isEmpty()) Text(
                                    stringResource(R.string.notes_placeholder),
                                    style = typography.headlineSmall, color = colors.onSurfaceVariant,
                                )
                                inner()
                            }
                        },
                    )
                }
                }
                if (focused && !readOnly) {
                    FormatBar(
                        onBlock = { kind -> applyEdit(NoteEditing.setBlock(value.text, value.selection.start, value.selection.end, kind)) },
                        onCode = { applyEdit(NoteEditing.toggleCode(value.text, value.selection.start, value.selection.end)) },
                        onIndent = { out -> applyEdit(NoteEditing.indent(value.text, value.selection.start, value.selection.end, out)) },
                        onInline = { marker -> applyEdit(NoteEditing.toggleInline(value.text, value.selection.start, value.selection.end, marker)) },
                        onLink = { linkDialog = true },
                        attachMenu = attachMenu,
                        onAttachMenu = { attachMenu = it },
                        onAttachPhotos = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                        onAttachCamera = {
                            val file = java.io.File(cacheDir, "note_camera").apply { mkdirs() }.let { java.io.File(it, "photo-${System.currentTimeMillis()}.jpg") }
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, packageName + ".fileprovider", file)
                            cameraUri = uri
                            takePicture.launch(uri)
                        },
                        onAttachFile = { pickFiles.launch(arrayOf("*/*")) },
                        inTable = NoteEditing.inTable(value.text, value.selection.start),
                        onTable = { action ->
                            val text = value.text
                            val at = value.selection.start
                            applyEdit(when (action) {
                                TableAction.INSERT -> NoteEditing.insertTable(text, at)
                                TableAction.ADD_ROW -> NoteEditing.addRow(text, at)
                                TableAction.ADD_COLUMN -> NoteEditing.addColumn(text, at)
                                TableAction.DELETE_ROW -> NoteEditing.deleteRow(text, at)
                                TableAction.DELETE_COLUMN -> NoteEditing.deleteColumn(text, at)
                            })
                        },
                        onHideKeyboard = { keyboard?.hide() },
                    )
                } else {
                    Box(Modifier.navigationBarsPadding())
                }
            }
        }

        if (linkDialog) {
            var url by remember { mutableStateOf("https://") }
            AlertDialog(
                onDismissRequest = { linkDialog = false },
                title = { Text(stringResource(R.string.notes_link)) },
                text = { OutlinedTextField(url, { url = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)) },
                confirmButton = {
                    TextButton(onClick = {
                        linkDialog = false
                        applyEdit(NoteEditing.link(value.text, value.selection.start, value.selection.end, url.trim()))
                    }, enabled = url.trim().length > 8) { Text(stringResource(R.string.notes_link_add)) }
                },
                dismissButton = { TextButton(onClick = { linkDialog = false }) { Text(stringResource(android.R.string.cancel)) } },
            )
        }
    }

    @Composable
    private fun FormatBar(
        onBlock: (Kind) -> Unit, onCode: () -> Unit, onIndent: (Boolean) -> Unit, onInline: (String) -> Unit,
        onLink: () -> Unit, attachMenu: Boolean, onAttachMenu: (Boolean) -> Unit, onAttachPhotos: () -> Unit,
        onAttachCamera: () -> Unit, onAttachFile: () -> Unit, inTable: Boolean, onTable: (TableAction) -> Unit,
        onHideKeyboard: () -> Unit,
    ) {
        var styles by remember { mutableStateOf(false) }
        var tableMenu by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    BarButton(R.drawable.ms_format_size, R.string.notes_format_styles) { styles = true }
                    DropdownMenu(styles, onDismissRequest = { styles = false }) {
                        listOf(
                            Triple(R.string.notes_style_title, Kind.TITLE, MaterialTheme.typography.titleLarge),
                            Triple(R.string.notes_style_heading, Kind.HEADING, MaterialTheme.typography.titleMedium),
                            Triple(R.string.notes_style_subheading, Kind.SUBHEADING, MaterialTheme.typography.titleSmall),
                            Triple(R.string.notes_style_body, Kind.BODY, MaterialTheme.typography.bodyLarge),
                        ).forEach { (label, kind, style) ->
                            DropdownMenuItem(text = { Text(stringResource(label), style = style) }, onClick = { styles = false; onBlock(kind) })
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.notes_style_monospaced), fontFamily = FontFamily.Monospace) },
                            onClick = { styles = false; onCode() },
                        )
                    }
                }
                Box {
                    BarButton(R.drawable.ms_attach_file, R.string.notes_attach) { onAttachMenu(true) }
                    DropdownMenu(attachMenu, onDismissRequest = { onAttachMenu(false) }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.notes_attach_photos)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ms_photo_library), null) },
                            onClick = { onAttachMenu(false); onAttachPhotos() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.notes_attach_camera)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ms_photo_camera), null) },
                            onClick = { onAttachMenu(false); onAttachCamera() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.notes_attach_file)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ms_description), null) },
                            onClick = { onAttachMenu(false); onAttachFile() },
                        )
                    }
                }
                Box {
                    BarButton(R.drawable.ms_table, R.string.notes_table) { if (inTable) tableMenu = true else onTable(TableAction.INSERT) }
                    DropdownMenu(tableMenu, onDismissRequest = { tableMenu = false }) {
                        listOf(
                            R.string.notes_table_add_row to TableAction.ADD_ROW, R.string.notes_table_add_column to TableAction.ADD_COLUMN,
                            R.string.notes_table_delete_row to TableAction.DELETE_ROW, R.string.notes_table_delete_column to TableAction.DELETE_COLUMN,
                        ).forEach { (label, action) ->
                            DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { tableMenu = false; onTable(action) })
                        }
                    }
                }
                BarButton(R.drawable.ms_checklist, R.string.notes_checklist) { onBlock(Kind.CHECK) }
                BarButton(R.drawable.ms_format_list_bulleted, R.string.notes_format_bullets) { onBlock(Kind.BULLET) }
                BarButton(R.drawable.ms_format_list_numbered, R.string.notes_format_numbers) { onBlock(Kind.NUMBER) }
                BarButton(R.drawable.ms_format_quote, R.string.notes_format_quote) { onBlock(Kind.QUOTE) }
                BarButton(R.drawable.ms_format_indent_decrease, R.string.notes_format_outdent) { onIndent(true) }
                BarButton(R.drawable.ms_format_indent_increase, R.string.notes_format_indent) { onIndent(false) }
                VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
                BarButton(R.drawable.ms_format_bold, R.string.notes_format_bold) { onInline("**") }
                BarButton(R.drawable.ms_format_italic, R.string.notes_format_italic) { onInline("*") }
                BarButton(R.drawable.ms_format_strikethrough, R.string.notes_format_strike) { onInline("~~") }
                BarButton(R.drawable.ms_code, R.string.notes_format_code) { onInline("`") }
                BarButton(R.drawable.ms_link, R.string.notes_link) { onLink() }
            }
            BarButton(R.drawable.ms_keyboard_hide, R.string.notes_hide_keyboard, onHideKeyboard)
            Box(Modifier.width(4.dp))
        }
    }

    private fun openAttachment(name: String) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(NoteAttachments.contentUri(this, name), NoteAttachments.mimeType(name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { android.widget.Toast.makeText(this, R.string.transfers_cant_open, android.widget.Toast.LENGTH_SHORT).show() }
    }

    @Composable
    private fun BarButton(icon: Int, label: Int, onClick: () -> Unit) {
        IconButton(onClick = onClick) { Icon(painterResource(icon), stringResource(label), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    companion object {
        private const val EXTRA_ID = "note"
        /** Open ready to type: the cursor at the end and the keyboard up (the widget's + button). */
        private const val EXTRA_EDIT = "edit"

        /** From inside the app: the editor goes on top of the current screen, Back returns to it. */
        fun intent(context: Context, id: String) = Intent(context, NoteEditorActivity::class.java).putExtra(EXTRA_ID, id)

        fun newNoteIntent(context: Context) = Intent(context, NoteEditorActivity::class.java)

        /**
         * From a widget or tile: the editor opens in its own task (its own taskAffinity, see the manifest), replacing a note
         * opened that way before, so it never piles up on top of the app's screens (opening Janus from the launcher shows
         * Home, not a leftover note).
         */
        fun externalIntent(context: Context, id: String, edit: Boolean = false) =
            intent(context, id).addFlags(EXTERNAL_FLAGS).putExtra(EXTRA_EDIT, edit)

        fun newNoteExternalIntent(context: Context) = newNoteIntent(context).addFlags(EXTERNAL_FLAGS)

        private const val EXTERNAL_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
}

private class NoteTheme(
    val title: SpanStyle, val heading: SpanStyle, val subheading: SpanStyle,
    val muted: Color, val syntax: Color, val link: Color, val codeBackground: Color, val indent: TextUnit,
    val text: Color = Color.Unspecified,
)

/**
 * A line's height relative to its font size with the font's own spacing. No fixed line height is set, since a fixed or
 * em-based one is the same for every line and would flatten attachment lines (sized through their hidden text).
 */
private const val LINE_HEIGHT = 1.17f

private val TABLE_ROW_HEIGHT = 44.dp

private const val HIDE = '​' // zero-width space: hidden syntax keeps its length, so offsets map one to one

/** Line index of the checklist box under [position] (with [slop] px extra around it), or null. */
private fun boxAt(text: String, layout: TextLayoutResult, position: Offset, slop: Float): Int? {
    for (line in NoteMarkdown.lines(text)) {
        if (line.kind != Kind.CHECK) continue
        val visual = layout.getLineForOffset(line.markerStart)
        val box = layout.getBoundingBox(line.markerStart)
        val boxRight = layout.getBoundingBox(line.markerStart + 1).right
        if (position.y in layout.getLineTop(visual)..layout.getLineBottom(visual) && position.x in (box.left - slop)..(boxRight + slop)) return line.index
    }
    return null
}

/** The URL of the link under [position], unless it's on [caretLine] (being edited). */
private fun linkAt(text: String, layout: TextLayoutResult, position: Offset, caretLine: Int): String? {
    val offset = layout.getOffsetForPosition(position)
    val line = NoteMarkdown.lines(text).lastOrNull { it.start <= offset } ?: return null
    if (line.index == caretLine || line.kind == Kind.CODE || line.kind == Kind.FENCE) return null
    // Only when the tap is really on the text (not in the empty space after a line).
    val visual = layout.getLineForOffset(offset)
    if (position.x > layout.getLineRight(visual) || position.y > layout.getLineBottom(visual)) return null
    return NoteMarkdown.spans(text, line.contentStart, line.end)
        .firstOrNull { it.style == NoteMarkdown.Style.LINK && offset in it.contentStart..<it.contentEnd }?.url
}

/** Code block backgrounds, quote bars and checklist boxes, drawn where the hidden syntax is. */
private fun DrawScope.drawBlocks(text: String, layout: TextLayoutResult, primary: Color, onPrimary: Color, outline: Color, codeBackground: Color) {
    val lines = NoteMarkdown.lines(text)
    // Code blocks: one rounded background from the opening to the closing fence.
    var blockStart: NoteMarkdown.Line? = null
    for (line in lines) {
        if (line.kind == Kind.FENCE || line.kind == Kind.CODE) {
            if (blockStart == null) blockStart = line
            val next = lines.getOrNull(line.index + 1)
            val closes = line.kind == Kind.FENCE && line !== blockStart
            if (closes || next == null || (next.kind != Kind.CODE && next.kind != Kind.FENCE)) {
                val top = layout.getLineTop(layout.getLineForOffset(blockStart.start))
                val bottom = layout.getLineBottom(layout.getLineForOffset(maxOf(line.start, line.end - 1).coerceAtLeast(line.start)))
                drawRoundRect(codeBackground, Offset(-8.dp.toPx(), top), Size(size.width + 16.dp.toPx(), bottom - top), CornerRadius(8.dp.toPx()))
                blockStart = null
            }
        }
    }
    for (line in lines) {
        when (line.kind) {
            Kind.QUOTE -> {
                val x = layout.getBoundingBox(line.markerStart).left
                val top = layout.getLineTop(layout.getLineForOffset(line.start))
                val bottom = layout.getLineBottom(layout.getLineForOffset(maxOf(line.start, line.end - 1)))
                drawRoundRect(primary, Offset(x, top + 2.dp.toPx()), Size(3.dp.toPx(), bottom - top - 4.dp.toPx()), CornerRadius(2.dp.toPx()))
            }
            Kind.CHECK -> {
                val visual = layout.getLineForOffset(line.markerStart)
                val top = layout.getLineTop(visual)
                val bottom = layout.getLineBottom(visual)
                val left = layout.getBoundingBox(line.markerStart).left
                val side = ((bottom - top) * 0.62f).coerceAtMost(20.dp.toPx())
                val y = top + (bottom - top - side) / 2
                val corner = CornerRadius(side * 0.22f)
                if (line.checked) {
                    drawRoundRect(primary, Offset(left, y), Size(side, side), corner)
                    val tick = Path().apply {
                        moveTo(left + side * 0.24f, y + side * 0.52f)
                        lineTo(left + side * 0.42f, y + side * 0.70f)
                        lineTo(left + side * 0.76f, y + side * 0.32f)
                    }
                    drawPath(tick, onPrimary, style = Stroke(width = side * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                } else {
                    val stroke = side * 0.1f
                    drawRoundRect(outline, Offset(left + stroke / 2, y + stroke / 2), Size(side - stroke, side - stroke), corner, style = Stroke(stroke))
                }
            }
            else -> {}
        }
    }
}

/**
 * Shows the Markdown formatted. Hidden syntax becomes zero-width spaces (same length, so offsets map one to one):
 * nesting tabs, quote markers, fences and inline markers (inline ones and `#`s show, dimmed, on [caretLine]). Bullets
 * become "•", checklist markers blank space where the box is drawn; lists are indented with paragraph styles.
 */
private class NoteTransformation(
    private val theme: NoteTheme, private val caretLine: Int,
    /** Attachment name → font size (sp) that makes its line as tall as its preview. */
    private val attachmentSizes: Map<String, Float>,
    /** Font size that makes a table row line as tall as a drawn row. */
    private val tableRowSp: androidx.compose.ui.unit.TextUnit,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val shown = StringBuilder(raw)
        val styles = ArrayList<Triple<SpanStyle, Int, Int>>()
        val paragraphs = ArrayList<Triple<ParagraphStyle, Int, Int>>()
        val lines = NoteMarkdown.lines(raw)
        val title = NoteMarkdown.titleLine(lines)
        fun hide(from: Int, to: Int) { for (i in from until to) shown.setCharAt(i, HIDE) }
        fun syntax(from: Int, to: Int, onCaret: Boolean) = if (onCaret) styles += Triple(SpanStyle(color = theme.syntax), from, to) else hide(from, to)

        for (line in lines) {
            val onCaret = line.index == caretLine
            // An attachment line: its text hidden and sized so the line is as tall as the drawn preview.
            val attachment = if (line.kind == Kind.ATTACHMENT) NoteAttachments.parse(raw.substring(line.start, line.end)) else null
            // Table rows: drawn as a grid (except the row being edited, shown as text); the delimiter row folds away.
            if (line.kind == Kind.TABLE_ROW || line.kind == Kind.TABLE_RULE) {
                if (onCaret) styles += Triple(SpanStyle(color = theme.syntax), line.start, line.end) // pipes dimmed, cells below
                else {
                    hide(line.start, line.end)
                    // The size covers the line break too, or it would keep a normal line's height.
                    val end = if (line.end < raw.length) line.end + 1 else line.end
                    styles += Triple(SpanStyle(fontSize = if (line.kind == Kind.TABLE_ROW) tableRowSp else 1f.sp), line.start, end)
                }
                if (onCaret && line.kind == Kind.TABLE_ROW) {
                    NoteMarkdown.cells(raw, line).forEach { cell -> if (!cell.isEmpty()) styles += Triple(SpanStyle(color = theme.text), cell.first, cell.last + 1) }
                }
                continue
            }
            if (attachment != null) {
                if (onCaret) styles += Triple(SpanStyle(color = theme.syntax), line.start, line.end)
                else {
                    hide(line.start, line.end)
                    attachmentSizes[attachment.second]?.let { styles += Triple(SpanStyle(fontSize = it.sp), line.start, line.end) }
                }
                continue
            }
            when (line.kind) {
                Kind.FENCE -> if (onCaret) styles += Triple(SpanStyle(color = theme.syntax, fontFamily = FontFamily.Monospace), line.start, line.end) else hide(line.start, line.end)
                Kind.CODE -> styles += Triple(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp), line.start, line.end)
                Kind.TITLE, Kind.HEADING, Kind.SUBHEADING -> {
                    syntax(line.start, line.contentStart, onCaret)
                    val style = when (line.kind) { Kind.TITLE -> theme.title; Kind.HEADING -> theme.heading; else -> theme.subheading }
                    styles += Triple(style, line.start, line.end)
                }
                Kind.BULLET, Kind.NUMBER, Kind.CHECK, Kind.QUOTE -> {
                    for (i in line.start until line.markerStart) shown.setCharAt(i, '\u2003') // nesting tabs: an em space per level
                    when (line.kind) {
                        Kind.BULLET -> { shown.setCharAt(line.markerStart, '•'); styles += Triple(SpanStyle(color = theme.muted), line.markerStart, line.markerStart + 1) }
                        Kind.CHECK -> {
                            shown.setCharAt(line.markerStart, ' ') // em space: where the box is drawn
                            shown.setCharAt(line.markerStart + 1, ' ')
                            hide(line.markerStart + 2, line.contentStart)
                            if (line.checked) styles += Triple(SpanStyle(color = theme.muted, textDecoration = TextDecoration.LineThrough), line.contentStart, line.end)
                        }
                        Kind.QUOTE -> {
                            shown.setCharAt(line.markerStart, ' ') // room for the bar
                            hide(line.markerStart + 1, line.contentStart)
                            styles += Triple(SpanStyle(color = theme.muted), line.contentStart, line.end)
                        }
                        else -> styles += Triple(SpanStyle(color = theme.muted), line.markerStart, line.contentStart)
                    }
                }
                else -> if (line === title) styles += Triple(theme.title, line.start, line.end)
            }
            if (line.kind != Kind.CODE && line.kind != Kind.FENCE && line.kind != Kind.BLANK) {
                for (span in NoteMarkdown.spans(raw, line.contentStart, line.end)) {
                    span.markers.forEach { r -> syntax(r.first, r.last + 1, onCaret) }
                    val style = when (span.style) {
                        NoteMarkdown.Style.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                        NoteMarkdown.Style.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                        NoteMarkdown.Style.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                        NoteMarkdown.Style.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = theme.codeBackground)
                        NoteMarkdown.Style.LINK -> SpanStyle(color = theme.link, textDecoration = TextDecoration.Underline)
                        NoteMarkdown.Style.ESCAPE -> null
                    }
                    if (style != null && span.contentEnd > span.contentStart) styles += Triple(style, span.contentStart, span.contentEnd)
                }
            }
        }
        val builder = AnnotatedString.Builder(shown.toString())
        styles.forEach { (style, from, to) -> if (to > from) builder.addStyle(style, from, to) }
        paragraphs.forEach { (style, from, to) -> if (to > from) builder.addStyle(style, from, to) }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

/** A preview of an attachment [width] px wide: the image (any format Android decodes, HEIC included) or a PDF's first page. */
private fun loadPreview(name: String, width: Int): ImageBitmap? = runCatching {
    val file = NoteAttachments.file(name)
    if (NoteAttachments.isPdf(name)) {
        android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            android.graphics.pdf.PdfRenderer(fd).use { renderer ->
                renderer.openPage(0).use { page ->
                    val bitmap = android.graphics.Bitmap.createBitmap(width, width * page.height / page.width, android.graphics.Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.asImageBitmap()
                }
            }
        }
    } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(file)) { decoder, info, _ ->
            val w = info.size.width
            if (w > width) decoder.setTargetSize(width, info.size.height * width / w)
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }.asImageBitmap()
    } else {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }
}.getOrNull()

/** The stored name of the attachment under [position], unless its line is being edited. */
private fun attachmentAt(text: String, layout: TextLayoutResult, position: Offset, caretLine: Int): String? {
    for (line in NoteMarkdown.lines(text)) {
        if (line.kind != Kind.ATTACHMENT || line.index == caretLine) continue
        val visual = layout.getLineForOffset(line.start)
        if (position.y in layout.getLineTop(visual)..layout.getLineBottom(visual)) return NoteAttachments.parse(text.substring(line.start, line.end))?.second
    }
    return null
}

/** Attachment previews on their (tall, hidden) lines: images fit to the line, other files as a tile with name and size. */
private fun DrawScope.drawAttachments(
    text: String, layout: TextLayoutResult, caretLine: Int, images: Map<String, ImageBitmap>, measurer: TextMeasurer,
    tile: Color, onTile: Color, muted: Color,
) {
    for (line in NoteMarkdown.lines(text)) {
        if (line.kind != Kind.ATTACHMENT || line.index == caretLine) continue
        val (displayName, name) = NoteAttachments.parse(text.substring(line.start, line.end)) ?: continue
        val visual = layout.getLineForOffset(line.start)
        val top = layout.getLineTop(visual) + 4.dp.toPx()
        val bottom = layout.getLineBottom(visual) - 4.dp.toPx()
        val height = bottom - top
        if (height <= 0f) continue
        val corner = CornerRadius(12.dp.toPx())
        val image = images[name]
        if (image != null) {
            val scale = minOf(size.width / image.width, height / image.height)
            val w = image.width * scale
            val h = image.height * scale
            val rect = RoundRect(0f, top, w, top + h, corner)
            clipPath(Path().apply { addRoundRect(rect) }) {
                drawImage(image, dstOffset = IntOffset(0, top.toInt()), dstSize = IntSize(w.toInt(), h.toInt()))
            }
        } else {
            drawRoundRect(tile, Offset(0f, top), Size(size.width, height), corner)
            val have = NoteAttachments.has(name)
            val sizeText = if (have) android.text.format.Formatter.formatShortFileSize(null, NoteAttachments.file(name).length()) else "…"
            val title = measurer.measure(displayName, TextStyle(color = onTile, fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, constraints = androidx.compose.ui.unit.Constraints(maxWidth = (size.width - 32.dp.toPx()).toInt().coerceAtLeast(1)))
            val subtitle = measurer.measure("${name.substringAfterLast('.').uppercase()} · $sizeText", TextStyle(color = muted, fontSize = 13.sp))
            val textTop = top + (height - title.size.height - subtitle.size.height) / 2
            drawText(title, topLeft = Offset(16.dp.toPx(), textTop))
            drawText(subtitle, topLeft = Offset(16.dp.toPx(), textTop + title.size.height))
        }
    }
}

private enum class TableAction { INSERT, ADD_ROW, ADD_COLUMN, DELETE_ROW, DELETE_COLUMN }

/** The cursor position for the table cell under [position] (a drawn row, not the one being edited), or null. */
private fun cellAt(text: String, layout: TextLayoutResult, position: Offset, caretLine: Int, width: Float): Int? {
    val lines = NoteMarkdown.lines(text)
    for (line in lines) {
        if (line.kind != Kind.TABLE_ROW || line.index == caretLine) continue
        val visual = layout.getLineForOffset(line.start)
        if (position.y !in layout.getLineTop(visual)..layout.getLineBottom(visual)) continue
        val columns = tableColumns(text, lines, line)
        val col = (position.x / (width / columns)).toInt().coerceIn(0, columns - 1)
        return NoteEditing.cellCursorAt(text, line.index, col)
    }
    return null
}

/** The widest row's cell count in the table containing [line]. */
private fun tableColumns(text: String, lines: List<NoteMarkdown.Line>, line: NoteMarkdown.Line): Int {
    var a = line.index
    var b = line.index
    fun inTable(l: NoteMarkdown.Line?) = l != null && (l.kind == Kind.TABLE_ROW || l.kind == Kind.TABLE_RULE)
    while (inTable(lines.getOrNull(a - 1))) a--
    while (inTable(lines.getOrNull(b + 1))) b++
    return (a..b).map { lines[it] }.filter { it.kind == Kind.TABLE_ROW }.maxOf { NoteMarkdown.cells(text, it).size }.coerceAtLeast(1)
}

/** Table rows as a grid of equal columns (all rows look the same); the row being edited is left as text. */
private fun DrawScope.drawTables(text: String, layout: TextLayoutResult, caretLine: Int, measurer: TextMeasurer, grid: Color, onSurface: Color) {
    val lines = NoteMarkdown.lines(text)
    val stroke = 1.dp.toPx()
    for (line in lines) {
        if (line.kind != Kind.TABLE_ROW || line.index == caretLine) continue
        val visual = layout.getLineForOffset(line.start)
        val top = layout.getLineTop(visual)
        val bottom = layout.getLineBottom(visual)
        val columns = tableColumns(text, lines, line)
        val cellWidth = size.width / columns
        val cells = NoteMarkdown.cells(text, line)
        drawRect(grid, Offset(0f, top), Size(size.width, bottom - top), style = Stroke(stroke))
        for (c in 0 until columns) {
            if (c > 0) drawLine(grid, Offset(c * cellWidth, top), Offset(c * cellWidth, bottom), stroke)
            val content = cells.getOrNull(c)?.let { NoteMarkdown.cellText(text, it) }.orEmpty()
            if (content.isEmpty()) continue
            val measured = measurer.measure(
                content, TextStyle(color = onSurface, fontSize = 15.sp), maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                constraints = androidx.compose.ui.unit.Constraints(maxWidth = (cellWidth - 16.dp.toPx()).toInt().coerceAtLeast(1)),
            )
            drawText(measured, topLeft = Offset(c * cellWidth + 8.dp.toPx(), top + (bottom - top - measured.size.height) / 2))
        }
    }
}

