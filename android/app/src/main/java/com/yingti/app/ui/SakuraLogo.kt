package com.yingti.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.PathParser

/**
 * 樱花 logo：取自 sakura-signal 素材的主樱花（五瓣 + 花心）。
 * 五瓣各自取主题派生的一档颜色，还原素材"每片花瓣颜色不同"的层次；花心透出浅色。
 * 图形放平无底，配色随版式与亮暗切换整体位移。
 */

// 素材原始坐标范围，绘制时按此视口等比缩放。
private const val VIEWPORT_LEFT = 120f
private const val VIEWPORT_TOP = 285f
private const val VIEWPORT_SIZE = 845f

private val SAKURA_PETAL_PATHS = listOf(
    "M545 697 C501 629 450 544 424 475 C423 450 440 405 457 386 L565 294 L601 352 L650 304 L718 428 C733 448 735 488 723 521 C674 580 606 643 545 697Z",
    "M545 697 C499 627 451 542 425 475 C390 460 349 440 330 440 L180 458 L209 521 L129 520 C147 577 164 632 182 679 C191 704 226 727 260 743 C350 734 452 712 545 697Z",
    "M545 697 C450 711 346 728 260 742 C237 756 212 782 195 805 C180 830 179 868 172 909 L160 975 L258 958 L236 1049 L421 1029 C446 1028 478 996 508 972 C521 879 537 775 545 697Z",
    "M545 697 C610 735 697 790 759 824 C777 848 790 891 802 925 C811 951 802 979 799 1006 L789 1088 L723 1051 L711 1122 C669 1101 618 1081 578 1057 C544 1039 521 1001 507 973 C504 906 530 773 545 697Z",
    "M545 697 C606 639 678 572 725 520 C744 516 777 515 797 520 C834 534 898 568 939 592 L890 650 L954 684 C923 720 888 757 855 791 C836 813 801 824 762 826 C695 787 609 735 545 697Z",
)

private val SAKURA_CORE_PATHS = listOf(
    "M537 677 L542 599 C543 590 535 582 539 574 C545 558 562 558 572 568 C580 576 574 585 572 593 L552 676Z",
    "M523 697 L450 666 C439 662 432 667 425 658 C416 645 428 630 439 629 C450 626 458 635 461 642 L530 683Z",
    "M569 688 L643 665 C654 662 652 652 663 653 C679 653 683 670 676 684 C671 696 662 694 653 692 L572 702Z",
    "M528 719 L483 776 C484 787 480 794 471 795 C456 796 441 783 445 772 C448 762 457 762 465 757 L514 709Z",
    "M558 720 L591 777 C598 785 611 790 613 799 C615 814 600 825 587 820 C578 817 577 806 574 797 L544 725Z",
    "M589 697 A44 44 0 1 1 501 697 A44 44 0 1 1 589 697Z",
)

@Composable
fun SakuraLogo(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val petals = yingtiSakuraPetals(scheme.primary)
    val core = yingtiSakuraCore(scheme.primary, scheme.surface, dark)
    val petalPaths = remember { SAKURA_PETAL_PATHS.map(::parsePath) }
    val corePaths = remember { SAKURA_CORE_PATHS.map(::parsePath) }
    Canvas(modifier) {
        val k = size.minDimension / VIEWPORT_SIZE
        scale(k, k, pivot = Offset.Zero) {
            translate(-VIEWPORT_LEFT, -VIEWPORT_TOP) {
                petalPaths.forEachIndexed { index, path ->
                    drawPath(path, petals[index % petals.size])
                }
                corePaths.forEach { path -> drawPath(path, core) }
            }
        }
    }
}

private fun parsePath(pathData: String): Path =
    PathParser().parsePathString(pathData).toPath()
