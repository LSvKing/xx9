package com.xx9.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xx9.app.Prefs
import com.xx9.app.ui.theme.Acc
import com.xx9.app.ui.theme.AccOn
import com.xx9.app.ui.theme.Danger
import com.xx9.app.ui.theme.Fg
import com.xx9.app.ui.theme.Line
import com.xx9.app.ui.theme.Mut

@Composable
fun SettingsScreen(
    currentUrl: String,
    onSave: (String) -> Unit,
) {
    var text by remember(currentUrl) {
        mutableStateOf(if (currentUrl.endsWith("/")) currentUrl else "$currentUrl/")
    }
    var msg by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("服务器", color = Acc, fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        Text("原生 App 直连 FastAPI；免密。改地址后重新加载。", color = Mut, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; err = null; msg = null },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            singleLine = true,
            label = { Text("Base URL") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Acc,
                unfocusedBorderColor = Line,
                focusedTextColor = Fg,
                unfocusedTextColor = Fg,
                cursorColor = Acc,
            ),
            shape = RoundedCornerShape(10.dp),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val n = Prefs.normalizeOrNull(text)
                if (n == null) err = "地址无效，请带上 http:// 或 https://"
                else {
                    onSave(n)
                    msg = "已保存"
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Acc, contentColor = AccOn),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(10.dp),
        ) { Text("保存", fontWeight = FontWeight.Bold) }
        TextButton(onClick = { text = Prefs.DEFAULT_BASE_URL + "/" }) {
            Text("恢复默认", color = Acc)
        }
        if (err != null) Text(err!!, color = Danger, fontSize = 12.sp)
        if (msg != null) Text(msg!!, color = Acc, fontSize = 12.sp)
        Spacer(Modifier.height(24.dp))
        Text("版本 2.0.0-native · 主界面 Compose（非 WebView）· AMEND-3 长短比", color = Mut, fontSize = 12.sp)
    }
}
