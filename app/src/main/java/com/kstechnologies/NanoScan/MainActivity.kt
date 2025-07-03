package com.kstechnologies.NanoScan

import android.app.ActionBar
import androidx.activity.ComponentActivity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.io.File
import java.lang.reflect.Field

class MainActivity : ComponentActivity() {

    private val csvFiles = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mContext = this

        window.requestFeature(Window.FEATURE_ACTION_BAR)
        val ab: ActionBar? = actionBar
        ab?.hide()

        setContent {
            ScanListScreen(
                csvFiles = csvFiles,
                onDelete = { name -> removeFile(name); csvFiles.remove(name) },
                onOpen = { name -> openGraph(name) }
            ) { actionBar?.show() }
        }
    }

    override fun onResume() {
        super.onResume()
        csvFiles.clear()
        populateList()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_scan_list, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_info -> {
                startActivity(Intent(this, InfoActivity::class.java))
                true
            }
            R.id.action_scan -> {
                val intent = Intent(this, NewScanActivity::class.java)
                intent.putExtra("file_name", getString(R.string.newScan))
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun populateList() {
        val files: Array<Field> = R.raw::class.java.fields
        for (file in files) {
            csvFiles.add(file.name)
        }
        val nanoExtPath = android.os.Environment.getExternalStorageDirectory().absolutePath
        val dir = File(nanoExtPath, "/")
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.contains(".csv")) {
                csvFiles.add(f.name)
            }
        }
    }

    private fun removeFile(name: String) {
        val nanoExtPath = android.os.Environment.getExternalStorageDirectory().absolutePath
        val dir = File(nanoExtPath, "/")
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name == name) {
                f.delete()
            }
        }
    }

    private fun openGraph(name: String) {
        val intent = Intent(mContext, GraphActivity::class.java)
        intent.putExtra("file_name", name)
        startActivity(intent)
    }

    companion object {
        private lateinit var mContext: Context
    }
}

@Composable
private fun ScanListScreen(
    csvFiles: List<String>,
    onDelete: (String) -> Unit,
    onOpen: (String) -> Unit,
    onSplashFinished: () -> Unit
) {
    var search by remember { mutableStateOf("") }
    var showSplash by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(1000)
        showSplash = false
        onSplashFinished()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = showSplash,
            exit = fadeOut(animationSpec = tween(durationMillis = 1000))
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.desc_splash),
                    modifier = Modifier.size(96.dp)
                )
            }
        }

        if (!showSplash) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                TextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )

                val filtered = csvFiles.filter { it.contains(search, ignoreCase = true) }
                LazyColumn {
                    items(filtered, key = { it }) { file ->
                        val dismissState = rememberDismissState(
                            confirmValueChange = { value ->
                                if (value == DismissValue.DismissedToStart) {
                                    onDelete(file)
                                }
                                true
                            }
                        )
                        SwipeToDismiss(
                            state = dismissState,
                            directions = setOf(DismissDirection.EndToStart),
                            background = {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colors.error)
                                        .padding(start = 20.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        tint = Color.White,
                                        contentDescription = stringResource(R.string.delete)
                                    )
                                }
                            },
                            dismissContent = {
                                ListItem(
                                    text = { Text(file) },
                                    modifier = Modifier.clickable { onOpen(file) }
                                )
                            }
                        )
                        Divider()
                    }
                }
            }
        }
    }
}
