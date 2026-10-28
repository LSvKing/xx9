package com.xx9.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import com.xx9.app.Prefs
import com.xx9.app.ui.theme.Acc
import com.xx9.app.ui.theme.AccText
import com.xx9.app.ui.theme.Bg
import com.xx9.app.ui.theme.Danger
import com.xx9.app.ui.theme.Fg
import com.xx9.app.ui.theme.Line
import com.xx9.app.ui.theme.Mut

@Composable
fun SetupScreen(
    initialUrl: String,
    onContinue: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initialUrl) }
    var err by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(24.dp),
    ) {
        Text("xx9", color = Acc)
        Spacer(Modifier.height(8.dp))
        Text("服务器地址", color = Fg)
        Spacer(Modifier.height(6.dp))
        Text("默认已填；可改。App 免密，Web 密码不变。", color = Mut)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; err = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Base URL") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Acc,
                unfocusedBorderColor = Line,
                focusedTextColor = Fg,
                unfocusedTextColor = Fg,
                cursorColor = Acc,
                focusedLabelColor = Acc,
                unfocusedLabelColor = Mut,
            ),
            shape = RoundedCornerShape(10.dp),
        )
        if (err != null) {
            Spacer(Modifier.height(8.dp))
            Text(err!!, color = Danger)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                val n = Prefs.normalizeOrNull(text)
                if (n == null) err = "地址无效，请带上 http:// 或 https://"
                else onContinue(n)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Acc, contentColor = AccText),
        ) { Text("进入") }
        TextButton(onClick = {
            text = Prefs.DEFAULT_BASE_URL
            err = null
        }) { Text("恢复默认", color = Acc) }
    }
}
