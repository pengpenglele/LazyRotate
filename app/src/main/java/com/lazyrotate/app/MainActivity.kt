package com.lazyrotate.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.lazyrotate.app.contract.RotationActions
import com.lazyrotate.app.contract.RotationTarget
import com.lazyrotate.app.ui.LazyRotateApp
import com.lazyrotate.app.ui.MainViewModel
import com.lazyrotate.app.ui.theme.LazyRotateTheme

/**
 * 唯一 Activity。清单里已经冻结了类名与主题：
 *   com.lazyrotate.app.MainActivity
 *   @style/Theme.LazyRotate
 *
 * 除了展示 Compose 界面，它还承担「外部指令入口」：
 *   adb shell am start -a com.lazyrotate.app.action.SET_ROTATION \
 *       -n com.lazyrotate.app/.MainActivity --es com.lazyrotate.app.extra.TARGET landscape
 * Tasker 等自动化工具也走这个入口。onCreate 与 onNewIntent 都要处理。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 冷启动时可能本身就是被外部指令拉起来的，先处理再画界面。
        handleCommandIntent(intent)

        setContent {
            LazyRotateTheme {
                LazyRotateApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 冻结契约要求：必须把新 Intent 设回去，否则 getIntent() 还是旧的。
        setIntent(intent)
        handleCommandIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从系统设置页授权回来，重新读权限与真实旋转状态。
        viewModel.refreshAll()
    }

    /**
     * 处理外部指令。EXTRA_TARGET 放的是 RotationTarget.code（如 "landscape"），
     * 未知 code 会被 RotationTarget.fromCode 兜底成 FOLLOW_SYSTEM。
     */
    private fun handleCommandIntent(intent: Intent?) {
        val command = intent ?: return
        when (command.action) {
            RotationActions.ACTION_SET_ROTATION -> {
                val code = command.getStringExtra(RotationActions.EXTRA_TARGET)
                if (code != null) {
                    viewModel.applyTarget(RotationTarget.fromCode(code))
                }
            }

            RotationActions.ACTION_FLIP -> viewModel.flip()
            RotationActions.ACTION_CYCLE -> viewModel.cycle()
            else -> Unit
        }
    }
}
