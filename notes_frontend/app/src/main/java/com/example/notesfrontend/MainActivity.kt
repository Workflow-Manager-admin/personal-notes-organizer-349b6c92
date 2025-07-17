package com.example.notesfrontend

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.material3.Card
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import java.util.*
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.content.res.Resources
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

// PUBLIC_INTERFACE
class MainActivity : ComponentActivity() {
    /**
     * Entry point for the Notes app.
     * Sets up Compose UI and hooks up the ViewModel.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NotesAppTheme {
                val viewModel: NotesViewModel = viewModel(factory = NotesViewModel.Factory(application))
                NotesMainScreen(viewModel)
            }
        }
    }
}

// --------- Data Class ---------
data class Note(
    val id: String, // use UUID string
    val title: String,
    val content: String,
    val updatedAt: String
)

// ----------- Supabase Service -----------

object SupabaseConfig {
    val SUPABASE_URL: String
        get() = System.getenv("SUPABASE_URL") ?: BuildConfig.SUPABASE_URL
    val SUPABASE_KEY: String
        get() = System.getenv("SUPABASE_KEY") ?: BuildConfig.SUPABASE_KEY
}

// PUBLIC_INTERFACE
class NotesRepository {
    // The Supabase Table name
    private val NOTES_TABLE = "notes"
    private val supabaseUrl = SupabaseConfig.SUPABASE_URL
    private val supabaseKey = SupabaseConfig.SUPABASE_KEY
    private val client by lazy { OkHttpClient() }
    private val JSON = "application/json; charset=utf-8".toMediaType()

    // Helper to produce headers for Supabase request
    private fun supabaseHeaders(): Headers {
        return Headers.Builder()
            .add("apikey", supabaseKey)
            .add("Authorization", "Bearer $supabaseKey")
            .add("Content-Type", "application/json")
            .build()
    }

    // PUBLIC_INTERFACE
    suspend fun fetchNotes(): List<Note> = withContext(Dispatchers.IO) {
        val url = "$supabaseUrl/rest/v1/$NOTES_TABLE?select=*"
        val request = Request.Builder()
            .url(url)
            .headers(supabaseHeaders())
            .build()
        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            val responseBody = response.body?.string() ?: return@withContext emptyList()
            val arr = JSONArray(responseBody)
            return@withContext (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Note(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    content = o.getString("content"),
                    updatedAt = o.optString("updated_at", "")
                )
            }
        }
        return@withContext emptyList()
    }

    // PUBLIC_INTERFACE
    suspend fun addNote(title: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$supabaseUrl/rest/v1/$NOTES_TABLE"
        val uuid = UUID.randomUUID().toString()
        val now = Date().toString()
        val json = JSONObject().apply {
            put("id", uuid)
            put("title", title)
            put("content", content)
            put("updated_at", now)
        }
        val request = Request.Builder()
            .url(url)
            .headers(supabaseHeaders())
            .post(json.toString().toRequestBody(JSON))
            .build()
        val response = client.newCall(request).execute()
        return@withContext (response.code in 200..299)
    }

    // PUBLIC_INTERFACE
    suspend fun updateNote(id: String, title: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$supabaseUrl/rest/v1/$NOTES_TABLE?id=eq.$id"
        val now = Date().toString()
        val json = JSONObject().apply {
            put("title", title)
            put("content", content)
            put("updated_at", now)
        }
        val request = Request.Builder()
            .url(url)
            .headers(supabaseHeaders())
            .patch(json.toString().toRequestBody(JSON))
            .build()
        val response = client.newCall(request).execute()
        return@withContext (response.code in 200..299)
    }

    // PUBLIC_INTERFACE
    suspend fun deleteNote(id: String): Boolean = withContext(Dispatchers.IO) {
        val url = "$supabaseUrl/rest/v1/$NOTES_TABLE?id=eq.$id"
        val request = Request.Builder()
            .url(url)
            .headers(supabaseHeaders())
            .delete()
            .build()
        val response = client.newCall(request).execute()
        return@withContext (response.code in 200..299)
    }
}

// ----------- ViewModel -----------

class NotesViewModel(app: Application): AndroidViewModel(app) {
    var notes by mutableStateOf<List<Note>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var currentNote: Note? by mutableStateOf(null)
        private set
    var isEditorOpen by mutableStateOf(false)
        private set
    var editorTitle by mutableStateOf(TextFieldValue(""))
    var editorContent by mutableStateOf(TextFieldValue(""))
    val repository = NotesRepository()

    // PUBLIC_INTERFACE
    fun loadNotes() {
        isLoading = true
        errorMessage = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                notes = repository.fetchNotes().sortedByDescending { it.updatedAt }
            } catch (e: Exception) {
                errorMessage = "Failed to load notes"
            } finally {
                isLoading = false
            }
        }
    }

    // PUBLIC_INTERFACE
    fun openEditor(note: Note? = null) {
        currentNote = note
        isEditorOpen = true
        editorTitle = TextFieldValue(note?.title ?: "")
        editorContent = TextFieldValue(note?.content ?: "")
    }

    // PUBLIC_INTERFACE
    fun closeEditor() {
        currentNote = null
        isEditorOpen = false
    }

    // PUBLIC_INTERFACE
    fun saveNote() {
        val title = editorTitle.text.trim()
        val content = editorContent.text.trim()
        if (title.isEmpty()) {
            errorMessage = "Title cannot be empty"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            errorMessage = null
            val ok = if (currentNote == null)
                repository.addNote(title, content)
            else
                repository.updateNote(currentNote!!.id, title, content)
            if (ok) {
                loadNotes()
                withContext(Dispatchers.Main) { closeEditor() }
            } else {
                errorMessage = "Failed to save note"
            }
            isLoading = false
        }
    }

    // PUBLIC_INTERFACE
    fun deleteCurrentNote() {
        val note = currentNote ?: return
        viewModelScope.launch(Dispatchers.IO) {
            isLoading = true
            errorMessage = null
            val ok = repository.deleteNote(note.id)
            if (ok) {
                loadNotes()
                withContext(Dispatchers.Main) { closeEditor() }
            } else {
                errorMessage = "Failed to delete note"
            }
            isLoading = false
        }
    }

    companion object {
        val Factory = viewModelFactory<NotesViewModel> { application ->
            NotesViewModel(application)
        }
    }
}

// ----------- UI -----------

@Composable
fun NotesMainScreen(viewModel: NotesViewModel) {
    val notes by remember { viewModel::notes }
    val isLoading by remember { viewModel::isLoading }
    val errorMessage by remember { viewModel::errorMessage }
    val isEditorOpen by remember { viewModel::isEditorOpen }

    LaunchedEffect(Unit) { viewModel.loadNotes() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.White
    ) {
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentColor)
            }
        }
        if (isEditorOpen) {
            NoteEditorScreen(
                title = viewModel.editorTitle,
                content = viewModel.editorContent,
                onTitleChanged = { viewModel.editorTitle = it },
                onContentChanged = { viewModel.editorContent = it },
                onSave = { viewModel.saveNote() },
                onCancel = { viewModel.closeEditor() },
                onDelete = if (viewModel.currentNote != null) ({ viewModel.deleteCurrentNote() }) else null,
                isSaving = isLoading
            )
        } else {
            NotesListScreen(
                notes = notes,
                onNoteClick = { viewModel.openEditor(it) },
                onAddNew = { viewModel.openEditor(null) }
            )
        }
        errorMessage?.let {
            Snackbar(
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.BottomCenter),
                action = {
                    Text(
                        text = "Close",
                        color = AccentColor,
                        modifier = Modifier.clickable { viewModel.errorMessage = null }
                    )
                }
            ) { Text(it) }
        }
    }
}

val PrimaryColor = Color(0xFF4A90E2)
val SecondaryColor = Color(0xFF50E3C2)
val AccentColor = Color(0xFFF5A623)

@Composable
fun NotesListScreen(notes: List<Note>, onNoteClick: (Note) -> Unit, onAddNew: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        TopAppBar(
            title = { Text("My Notes", color = PrimaryColor) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
        )
        Box(
            Modifier.weight(1f).fillMaxWidth()
        ) {
            if (notes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No notes found.", color = Color.Gray)
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(notes.size) { i ->
                        val note = notes[i]
                        NoteCard(note = note, onClick = { onNoteClick(note) })
                    }
                }
            }
        }
        Button(
            onClick = onAddNew,
            colors = ButtonDefaults.buttonColors(containerColor = AccentColor, contentColor = Color.White),
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Text("+ Add Note")
        }
    }
}

@Composable
fun NoteCard(note: Note, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(note.title, color = PrimaryColor, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                note.content.take(80),
                color = Color.DarkGray,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2
            )
            Spacer(Modifier.height(4.dp))
            Text(
                note.updatedAt,
                style = MaterialTheme.typography.labelSmall,
                color = SecondaryColor
            )
        }
    }
}

// Note Editor Composable
@Composable
fun NoteEditorScreen(
    title: TextFieldValue,
    content: TextFieldValue,
    onTitleChanged: (TextFieldValue) -> Unit,
    onContentChanged: (TextFieldValue) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
    isSaving: Boolean
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text("Edit Note", color = PrimaryColor) },
            navigationIcon = {
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = PrimaryColor
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = title,
            onValueChange = onTitleChanged,
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentColor, unfocusedBorderColor = SecondaryColor)
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = content,
            onValueChange = onContentChanged,
            label = { Text("Note") },
            modifier = Modifier
                .height(180.dp)
                .fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentColor, unfocusedBorderColor = SecondaryColor),
            maxLines = 10
        )
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (onDelete != null) {
                TextButton(
                    onClick = onDelete,
                    enabled = !isSaving,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) {
                    Text("Delete")
                }
            }
            Row {
                TextButton(
                    onClick = onCancel,
                    enabled = !isSaving,
                    colors = ButtonDefaults.textButtonColors(contentColor = SecondaryColor)
                ) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = onSave,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentColor, contentColor = Color.White),
                    enabled = !isSaving
                ) {
                    Text("Save")
                }
            }
        }
    }
}

// --------- THEME ---------
@Composable
fun NotesAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = PrimaryColor,
            secondary = SecondaryColor,
            tertiary = AccentColor,
            background = Color.White,
            surface = Color.White,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onTertiary = Color.Black,
            onBackground = Color.Black,
            onSurface = Color.Black
        ),
        typography = Typography(),
        content = content
    )
}

// --------- Compose ViewModel Factory Helper ---------
@Composable
inline fun <reified VM : AndroidViewModel> viewModelFactory(
    crossinline factory: (Application) -> VM
): androidx.lifecycle.ViewModelProvider.Factory =
    object : androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory() {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            val app = ApplicationHolder.application ?: throw IllegalStateException("Application is null")
            @Suppress("UNCHECKED_CAST")
            return factory(app) as T
        }
    }

object ApplicationHolder {
    var application: Application? = null
}
