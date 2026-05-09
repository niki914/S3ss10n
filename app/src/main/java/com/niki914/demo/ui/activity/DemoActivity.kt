package com.niki914.demo.ui.activity

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.niki914.demo.ChatViewModel
import com.niki914.demo.ui.compose.DemoChatScreen
import com.niki914.demo.ui.compose.theme.DemoTheme

class DemoActivity : AppCompatActivity() {

    private val vm by viewModels<ChatViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // phase4 手工 smoke 入口，非长期生产逻辑。
        com.niki914.s3ss10n.smoketest.main()

//        vm.sendIntent(ChatIntent.SetConfig {
//            endpoint = ""
//            apiKey = ""
//            model = "gemini-2.5-flash"
//            systemPrompt = "You're a helpful assistant."
//        })
//        vm.sendIntent(ChatIntent.Send("toast: hello world!"))

        setContent {
            DemoTheme { // 应用动态颜色主题
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DemoChatScreen(vm)
                }
            }
        }
    }
}
