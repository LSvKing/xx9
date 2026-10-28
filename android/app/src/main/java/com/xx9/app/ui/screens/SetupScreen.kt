package com.xx9.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xx9.app.Prefs
import com.xx9.app.ui.BrandMark
import com.xx9.app.ui.theme.Acc
import com.xx9.app.ui.theme.AccOn
import com.xx9.app.ui.theme.Bg
import com.xx9.app.ui.theme.CastBlock
import com.xx9.app.ui.theme.Danger
import com.xx9.app.ui.theme.Fg
import com.xx9.app.ui.theme.Line
import com.xx9.app.ui.theme.Mut
import com.xx9.app.ui.theme.Surface

@Composable
fun SetupScreen(
    initialUrl: String = Prefs.DEFAULT_BASE_URL,
    onContinue: (String) -> Unit,
) {
    var text by remember { mutableStateOf(if (initialUrl.endsWith("/")) initialUrl else "$initialUrl/") }
    var err by remember { mutableStateOf<String?>(null) }
    Box(
        Modifier.fillMaxSize().background(Bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(20.dp)
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .clipCard()
                .background(Surface, RoundedCornerShape(12.dp))
                .border(1.dp, Line, RoundedCornerShape(12.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(CastBlock, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
            )
            Column(Modifier.padding(20.dp)) {
                BrandMark()
                Spacer(Modifier.height(8.dp))
                Text("填写自建服务地址。App 无需输入密码。", color = Mut, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                Text("Base URL", color = Mut, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; err = null },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Acc,
                        unfocusedBorderColor = Line,
                        focusedTextColor = Fg,
                        unfocusedTextColor = Fg,
                        cursorColor = Acc,
                    ),
                    shape = RoundedCornerShape(10.dp),
                )
                if (err != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(err!!, color = Danger, fontSize = 12.sp)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val n = Prefs.normalizeOrNull(text)
                        if (n == null) err = "地址无效，请带上 http:// 或 https://"
                        else onContinue(n)
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Acc, contentColor = AccOn),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("保存并继续", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "可稍后在设置中修改",
                    color = Mut,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

private fun Modifier.clipCard(): Modifier = this
