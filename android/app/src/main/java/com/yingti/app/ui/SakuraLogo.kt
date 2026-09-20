package com.yingti.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 樱花 logo：取自 sakura-signal 素材的主樱花（五瓣 + 花心）。
 * 五瓣各自取主题派生的一档颜色，还原素材"每片花瓣颜色不同"的层次；花心透出浅色。
 * 图形放平无底，配色随版式与亮暗切换整体位移。
 */

// 素材原始坐标范围，绘制时按此视口等比缩放。
private const val VIEWPORT_LEFT = 120f
private const val VIEWPORT_TOP = 285f
private const val VIEWPORT_SIZE = 845f

// 花心中心与半径
private const val CENTER_X = 545f
private const val CENTER_Y = 697f
private const val CORE_RADIUS = 44f

private val SAKURA_PETAL_PATHS = listOf(
    "M545 697 C501 629 450 544 424 475 C423 450 440 405 457 386 L565 294 L601 352 L650 304 L718 428 C733 448 735 488 723 521 C674 580 606 643 545 697Z",
    "M545 697 C499 627 451 542 425 475 C390 460 349 440 330 440 L180 458 L209 521 L129 520 C147 577 164 632 182 679 C191 704 226 727 260 743 C350 734 452 712 545 697Z",
    "M545 697 C450 711 346 728 260 742 C237 756 212 782 195 805 C180 830 179 868 172 909 L160 975 L258 958 L236 1049 L421 1029 C446 1028 478 996 508 972 C521 879 537 775 545 697Z",
    "M545 697 C610 735 697 790 759 824 C777 848 790 891 802 925 C811 951 802 979 799 1006 L789 1088 L723 1051 L711 1122 C669 1101 618 1081 578 1057 C544 1039 521 1001 507 973 C504 906 530 773 545 697Z",
    "M545 697 C606 639 678 572 725 520 C744 516 777 515 797 520 C834 534 898 568 939 592 L890 650 L954 684 C923 720 888 757 855 791 C836 813 801 824 762 826 C695 787 609 735 545 697Z",
)

// 5 根花蕊路径（按瓣索引对应：0->0, 1->1, 2->3, 3->4, 4->2）
private val SAKURA_STAMEN_PATHS_BY_PETAL = listOf(
    "M537 677 L542 599 C543 590 535 582 539 574 C545 558 562 558 572 568 C580 576 574 585 572 593 L552 676Z", // 瓣 0
    "M523 697 L450 666 C439 662 432 667 425 658 C416 645 428 630 439 629 C450 626 458 635 461 642 L530 683Z", // 瓣 1
    "M528 719 L483 776 C484 787 480 794 471 795 C456 796 441 783 445 772 C448 762 457 762 465 757 L514 709Z", // 瓣 2
    "M558 720 L591 777 C598 785 611 790 613 799 C615 814 600 825 587 820 C578 817 577 806 574 797 L544 725Z", // 瓣 3
    "M569 688 L643 665 C654 662 652 652 663 653 C679 653 683 670 676 684 C671 696 662 694 653 692 L572 702Z", // 瓣 4
)

// 5 块 1/5 扇形花心的精确闭合矢量路径（每块 72 度，中心严丝合缝拼成 R=44 的完整圆心）
private val SAKURA_SECTOR_PATHS_BY_PETAL = listOf(
    "M 545.0 697.0 L 525.71 657.45 A 44.0 44.0 0 0 1 576.65 666.44 Z", // 瓣 0: [-116°, -44°]
    "M 545.0 697.0 L 501.43 703.12 A 44.0 44.0 0 0 1 525.71 657.45 Z", // 瓣 1: [172°, 244°]
    "M 545.0 697.0 L 537.36 740.33 A 44.0 44.0 0 0 1 501.43 703.12 Z", // 瓣 2: [100°, 172°]
    "M 545.0 697.0 L 583.85 717.66 A 44.0 44.0 0 0 1 537.36 740.33 Z", // 瓣 3: [28°, 100°]
    "M 545.0 697.0 L 576.65 666.44 A 44.0 44.0 0 0 1 583.85 717.66 Z", // 瓣 4: [-44°, 28°]
)

// 折叠回瓣 0 位置的角度偏移量
private val PETAL_OFFSETS = listOf(
    0.0f,    // 瓣 0
    -278.0f, // 瓣 1
    -219.0f, // 瓣 2
    -133.0f, // 瓣 3
    -73.0f,  // 瓣 4
)

// 叠放绘制顺序：从底到顶，确保合拢时瓣 0 在最顶层
private val UNIT_DRAW_ORDER = listOf(1, 2, 3, 4, 0)

@Composable
fun SakuraLogo(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val petals = yingtiSakuraPetals(scheme.primary)
    val core = yingtiSakuraCore(scheme.primary, dark)
    val petalPaths = remember { SAKURA_PETAL_PATHS.map(::parsePath) }
    val stamenPaths = remember { SAKURA_STAMEN_PATHS_BY_PETAL.map(::parsePath) }
    Canvas(modifier) {
        val k = size.minDimension / VIEWPORT_SIZE
        scale(k, k, pivot = Offset.Zero) {
            translate(-VIEWPORT_LEFT, -VIEWPORT_TOP) {
                petalPaths.forEachIndexed { index, path ->
                    drawPath(path, petals[index % petals.size])
                }
                stamenPaths.forEach { path -> drawPath(path, core) }
                // 静态中心圆
                drawCircle(core, radius = CORE_RADIUS, center = Offset(CENTER_X, CENTER_Y))
            }
        }
    }
}

/**
 * 顺时针折扇舒展绽放动效：
 * 花心切为五片扇区，每片绑定对应花瓣与花蕊；
 * 初始时五瓣叠合，随后顺时针旋转展开，花心扇片同步在中心拼合为完整圆形。
 */
@Composable
fun SakuraBloomingLogo(
    progress: Float, // 0f(完全叠合) .. 1f(盛开)
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val petals = yingtiSakuraPetals(scheme.primary)
    val core = yingtiSakuraCore(scheme.primary, dark)
    val petalPaths = remember { SAKURA_PETAL_PATHS.map(::parsePath) }
    val stamenPaths = remember { SAKURA_STAMEN_PATHS_BY_PETAL.map(::parsePath) }
    val sectorPaths = remember { SAKURA_SECTOR_PATHS_BY_PETAL.map(::parsePath) }

    Canvas(modifier) {
        val k = size.minDimension / VIEWPORT_SIZE
        val scaleFactor = 0.90f + 0.10f * progress
        val alphaFactor = 0.72f + 0.28f * progress

        scale(k, k, pivot = Offset.Zero) {
            translate(-VIEWPORT_LEFT, -VIEWPORT_TOP) {
                UNIT_DRAW_ORDER.forEach { idx ->
                    val rot = PETAL_OFFSETS[idx] * (1.0f - progress)
                    rotate(rot, pivot = Offset(CENTER_X, CENTER_Y)) {
                        scale(scaleFactor, scaleFactor, pivot = Offset(CENTER_X, CENTER_Y)) {
                            // 1. 对应花瓣
                            drawPath(
                                petalPaths[idx],
                                color = petals[idx % petals.size],
                                alpha = alphaFactor
                            )
                            // 2. 对应花蕊
                            drawPath(
                                stamenPaths[idx],
                                color = core,
                                alpha = alphaFactor
                            )
                            // 3. 对应 1/5 花心扇区（矢量闭合路径拼接中心圆）
                            drawPath(
                                sectorPaths[idx],
                                color = core,
                                alpha = alphaFactor
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 启动过度开屏：
 * 居中播放轻盈的五瓣顺时针舒展展开动效，
 * 动画时长严格自适应（约 650ms 自然展开，绝不强行拖延），就绪后平滑淡出。
 */
@Composable
fun SakuraSplashScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progressAnim = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progressAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing)
        )
        // 展开完成后即刻交棒，绝不多等
        onFinished()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF141113)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            SakuraBloomingLogo(
                progress = progressAnim.value,
                modifier = Modifier.size(110.dp)
            )
            Text(
                "樱趣",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFF7EFF2).copy(alpha = progressAnim.value)
            )
        }
    }
}

private fun parsePath(pathData: String): Path =
    PathParser().parsePathString(pathData).toPath()
