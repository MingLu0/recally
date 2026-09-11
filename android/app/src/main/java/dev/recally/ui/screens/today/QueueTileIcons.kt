package dev.recally.ui.screens.today

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The "Waiting for you" tiles' 26dp composite icons (issue #198,
 * "Evidence -- the design's own geometry"; artboard `RcWhite.dc.html`). Both
 * are multi-colour and drawn with [androidx.compose.foundation.Image], never
 * [androidx.compose.material3.Icon] -- a single tint would flatten the
 * card-stack's two fills and the clock's disc-plus-stroke into one colour.
 */
enum class QueueTileIcon { TO_APPROVE, NEEDS_HUMAN }

/**
 * Two offset rounded rects (the book-cover plum and forest, fixed hex --
 * [dev.recally.ui.theme.BookCoverColors] -- since the design specifies them
 * as literal fills, not theme roles) with a white check: a stack of cards
 * with a tick.
 */
@Composable
fun toApproveTileIcon(): ImageVector =
    remember {
        ImageVector
            .Builder(
                name = "queue_to_approve",
                defaultWidth = 26.dp,
                defaultHeight = 26.dp,
                viewportWidth = 26f,
                viewportHeight = 26f,
            ).addPath(
                pathData = addPathNodes("M7 3h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"),
                fill = SolidColor(Color(0xFF4A3D55)),
            ).addPath(
                pathData = addPathNodes("M3 6h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z"),
                fill = SolidColor(Color(0xFF24403A)),
            ).addPath(
                pathData = addPathNodes("M11 9l2 2 4-4"),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
                strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
            ).build()
    }

/**
 * A filled disc (`warn-wash` / `warn` stroke -- the design's own
 * `#F8E6D8`/`#B85C2E` are exactly those tokens, so dark theme follows without
 * new colour work) with clock hands.
 */
@Composable
fun needsHumanTileIcon(
    discFill: Color,
    strokeColor: Color,
): ImageVector =
    remember(discFill, strokeColor) {
        ImageVector
            .Builder(
                name = "queue_needs_human",
                defaultWidth = 26.dp,
                defaultHeight = 26.dp,
                viewportWidth = 26f,
                viewportHeight = 26f,
            ).addPath(
                pathData = addPathNodes("M13 22a9 9 0 1 0 0-18 9 9 0 0 0 0 18z"),
                fill = SolidColor(discFill),
                stroke = SolidColor(strokeColor),
                strokeLineWidth = 1.4f,
            ).addPath(
                pathData = addPathNodes("M13 8v5l3 2"),
                stroke = SolidColor(strokeColor),
                strokeLineWidth = 2f,
                strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
                strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
            ).build()
    }
