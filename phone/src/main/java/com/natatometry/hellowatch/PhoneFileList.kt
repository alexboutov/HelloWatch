package com.natatometry.hellowatch

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The swim*.csv files of the phone's swim_logs folder as the screen lists them, and the selection. */
class PhoneFileListState {
    companion object {
        /** "<yyyy-MM-dd>_<HH-mm-ss>": the swim's start time in a file name; it sorts as text. */
        private val STAMP = Regex("""\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}""")

        /** Newest swim first; names without a start time come last, by name. */
        fun newestFirst(names: Collection<String>): List<String> =
            names.distinct().sortedWith(
                compareByDescending<String> { STAMP.find(it)?.value ?: "" }.thenBy { it }
            )
    }

    /** Names of the files in the folder, newest swim first. */
    var files by mutableStateOf(emptyList<String>())
        private set

    /** Names of the selected files; every one of them is in [files]. */
    var selected by mutableStateOf(emptySet<String>())
        private set

    /** Takes a fresh listing of the folder; a selected file that is gone leaves the selection. */
    fun onListed(names: Collection<String>) {
        files = newestFirst(names)
        selected = selected.intersect(files.toSet())
    }

    /** A file was just saved in the folder: it enters the list without the folder being read again. */
    fun onSaved(name: String) {
        files = newestFirst(files + name)
    }

    /** Selects the file, or unselects it if it is selected. */
    fun toggle(name: String) {
        selected = if (name in selected) selected - name else selected + name
    }
}

private val FILE_ROW_HEIGHT = 48.dp
private const val FILE_ROWS_SHOWN = 4
private const val NAMES_IN_QUESTION = 6

/** Text of the question before a deletion: the names, the first few of them if there are many. */
private fun questionText(names: List<String>): String {
    val shown = names.take(NAMES_IN_QUESTION).joinToString("\n")
    val more = if (names.size > NAMES_IN_QUESTION) "\n... and ${names.size - NAMES_IN_QUESTION} more" else ""
    return "$shown$more\n\nDeleted files cannot be brought back."
}

/** Status line after a deletion; [left] are the files that could not be deleted. */
private fun deletionText(deleted: Int, left: List<String>): String {
    val done = if (deleted == 1) "Deleted 1 file" else "Deleted $deleted files"
    return if (left.isEmpty()) done else "$done; could not delete ${left.joinToString(", ")}"
}

/**
 * The files of the phone's swim_logs folder, newest swim first, in a list four rows high that
 * scrolls for the rest. A tap on a row selects or unselects its file. "Delete selected" removes
 * the selected files from the phone after one question. The folder is read when the list first
 * appears and again whenever [refreshKey] changes; [onStatus] receives the result of a deletion.
 */
@Composable
fun PhoneFilesSection(
    fileList: PhoneFileListState,
    storage: SwimFileStorage,
    refreshKey: Int,
    onStatus: (String) -> Unit
) {
    val scope = rememberCoroutineScope()

    // The files named in the question now on the screen; null while no question is shown.
    var asked by remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(refreshKey) {
        fileList.onListed(withContext(Dispatchers.IO) { storage.listCsvNames() })
    }

    fun delete(names: List<String>) {
        scope.launch {
            val (left, nowInFolder) = withContext(Dispatchers.IO) {
                Pair(storage.deleteCsv(names), storage.listCsvNames())
            }
            fileList.onListed(nowInFolder)
            onStatus(deletionText(names.size - left.size, left))
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Files on phone (${fileList.files.size}):",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(4.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(FILE_ROW_HEIGHT * FILE_ROWS_SHOWN)
                .border(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            // No item keys: with keys the list would stay on the rows it shows, and a file saved
            // while the list is at its top would be put above them, out of sight.
            items(fileList.files) { name ->
                val isSelected = name in fileList.selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(FILE_ROW_HEIGHT)
                        .toggleable(
                            value = isSelected,
                            role = Role.Checkbox,
                            onValueChange = { fileList.toggle(name) }
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = null, // the whole row takes the tap
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { asked = fileList.files.filter { it in fileList.selected } },
            enabled = fileList.selected.isNotEmpty(),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            val count = fileList.selected.size
            Text(if (count == 0) "Delete selected" else "Delete selected ($count)")
        }
    }

    asked?.let { names ->
        AlertDialog(
            onDismissRequest = { asked = null },
            title = { Text(if (names.size == 1) "Delete 1 file?" else "Delete ${names.size} files?") },
            text = { Text(questionText(names)) },
            confirmButton = {
                TextButton(onClick = { asked = null; delete(names) }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { asked = null }) { Text("Cancel") }
            }
        )
    }
}
