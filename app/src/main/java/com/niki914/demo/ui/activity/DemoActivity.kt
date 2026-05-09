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
import androidx.lifecycle.lifecycleScope
import com.niki914.demo.ChatViewModel
import com.niki914.demo.ui.compose.DemoChatScreen
import com.niki914.demo.ui.compose.theme.DemoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import com.niki914.s3ss10n.smoketest.main as runSmokeTests

class DemoActivity : AppCompatActivity() {

    private val vm by viewModels<ChatViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 运行烟测
        lifecycleScope.launch(Dispatchers.IO) {
            runSmokeTests()
        }

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