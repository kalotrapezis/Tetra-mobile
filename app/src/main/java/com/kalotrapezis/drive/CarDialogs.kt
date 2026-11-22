package com.kalotrapezis.drive

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/*
 * Tetra's dialogs: Material's, under names of their own. They were drawn as layers of Tetra Car's panel when Tetra Car was
 * projected to a car's screen (Tetra Auto, removed 22 Nov 2026); the call sites kept the names.
 */

@Composable
internal fun TetraAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) = androidx.compose.material3.AlertDialog(onDismissRequest, confirmButton, modifier, dismissButton, icon, title, text)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TetraSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.ModalBottomSheet(onDismissRequest, modifier, sheetState, containerColor = containerColor, contentColor = contentColor, content = content)

@Composable
internal fun TetraDialog(onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(), content: @Composable () -> Unit) =
    Dialog(onDismissRequest, properties, content)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TetraDatePickerDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.DatePickerDialog(onDismissRequest, confirmButton, modifier, dismissButton, content = content)
