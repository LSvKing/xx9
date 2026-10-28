package com.xx9.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xx9.app.Prefs
import com.xx9.app.ui.theme.Acc
import com.xx9.app.ui.theme.AccText
import com.xx9.app.ui.theme.Danger
import com.xx9.app.ui.theme.Fg
import com.xx9.app.ui.theme.Line
import com.xx9.app.ui.theme.Mut

@Composable
fun SettingsScreen(
    currentUrl: String,
    onSave: (String) -> Unit,
) {
    var text by remember(currentUrl) { mutableStateOf(currentUrl) }
    var msg by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("服务器", color = Acc)
        Spacer(Modifier.height(8.dp))
        Text("原生 App 直连 FastAPI；免密。改地址后重新加载内容。", color = Mut)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; err = null; msg = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Base URL") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Acc,
                unfocusedBorderColor = Line,
                focusedTextColor = Fg,
                unfocusedTextColor = Fg,
                cursorColor = Acc,
            ),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val n = Prefs.normalizeOrNull(text)
                if (n == null) err = "地址无效"
                else {
                    onSave(n)
                    msg = "已保存"
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Acc, contentColor = AccText),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存") }
        TextButton(onClick = { text = Prefs.DEFAULT_BASE_URL }) {
            Text("恢复默认 ${Prefs.DEFAULT_BASE_URL}", color = Acc)
        }
        if (err != null) Text(err!!, color = Danger)
        if (msg != null) Text(msg!!, color = Acc)
        Spacer(Modifier.height(24.dp))
        Text("版本 2.0.0-native · 主界面非 WebView（AC-7）", color = Mut)
    }
}
