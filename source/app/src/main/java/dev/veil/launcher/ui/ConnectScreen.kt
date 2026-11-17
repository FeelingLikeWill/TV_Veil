package dev.veil.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.veil.launcher.LauncherState

@Composable
fun ConnectScreen(state: LauncherState) {
    val dc = state.deviceCode
    Column(Modifier.fillMaxSize().background(Veil.Bg).padding(start = 48.dp, end = 48.dp, top = 26.dp)) {
        TopBar(state, Modifier.zIndex(1f))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("CONNECT TRAKT", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, color = Veil.Accent)
                Spacer(Modifier.height(8.dp))
                Text("Show what you're watching", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.9).sp)
                Spacer(Modifier.height(22.dp))
                if (dc != null) {
                    Text("On your phone or computer, go to", fontSize = 12.sp, color = Veil.Text.copy(alpha = 0.7f))
                    Spacer(Modifier.height(4.dp))
                    Text(dc.url.removePrefix("https://"), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(16.dp))
                    Text("and enter this code", fontSize = 12.sp, color = Veil.Text.copy(alpha = 0.7f))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        dc.userCode,
                        color = Veil.Ink, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 6.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Veil.Text)
                            .padding(horizontal = 30.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.height(20.dp))
                }
                Text(state.connectStatus, fontSize = 11.sp, color = Veil.Text.copy(alpha = 0.7f))
                Spacer(Modifier.height(6.dp))
                Text("Press Back to cancel", fontSize = 10.sp, color = Veil.Text.copy(alpha = 0.5f))
            }
        }
    }
}
