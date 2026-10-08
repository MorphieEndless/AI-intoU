package com.yingti.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 首次使用的三步：账号 → 玩具 → AI。 */
enum class OnboardingStep { ACCOUNT, TOY, AI, DONE }

object Onboarding {
    /** 当前停在哪一步：按顺序找第一个没完成的。纯函数，便于单测。 */
    fun current(configured: Boolean, toyConnected: Boolean, aiAccessDone: Boolean): OnboardingStep = when {
        !configured -> OnboardingStep.ACCOUNT
        !toyConnected && !aiAccessDone -> OnboardingStep.TOY
        !aiAccessDone -> OnboardingStep.AI
        else -> OnboardingStep.DONE
    }

    /** 引导卡显示条件：没收起、也没全部完成。 */
    fun visible(step: OnboardingStep, dismissed: Boolean): Boolean = !dismissed && step != OnboardingStep.DONE
}

private data class StepText(val step: OnboardingStep, val title: String, val detail: String)

private val STEPS = listOf(
    StepText(OnboardingStep.ACCOUNT, "连接服务器", "填写服务器地址，用邀请码注册新账号，或用已有账号登入。"),
    StepText(OnboardingStep.TOY, "连上玩具", "授予蓝牙权限，回到「玩具」页扫描并连接设备。"),
    StepText(OnboardingStep.AI, "接入 AI", "在「AI 接入」里生成 token，把配置粘贴进 RikkaHub 等客户端。"),
)

@Composable
fun OnboardingCard(
    step: OnboardingStep,
    toyConnected: Boolean,
    onOpenToy: () -> Unit,
    onOpenAiAccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    val currentIndex = STEPS.indexOfFirst { it.step == step }.let { if (it < 0) STEPS.size else it }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "三步开始使用",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("收起") }
            }
            STEPS.forEachIndexed { index, item ->
                val done = index < currentIndex || (item.step == OnboardingStep.TOY && toyConnected)
                val active = index == currentIndex
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(26.dp).background(
                            when {
                                done -> MaterialTheme.colorScheme.primary
                                active -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                            CircleShape,
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (done) "✓" else "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (done || active) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        if (active) {
                            Text(
                                item.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            when (item.step) {
                                OnboardingStep.TOY -> TextButton(onClick = onOpenToy, contentPadding = PaddingValues(0.dp)) { Text("去连接玩具 →") }
                                OnboardingStep.AI -> TextButton(onClick = onOpenAiAccess, contentPadding = PaddingValues(0.dp)) { Text("打开 AI 接入 →") }
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}
