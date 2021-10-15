package app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.extensions.simpleName
import app.extensions.toSmartSystemString
import app.git.GitManager
import app.git.LogStatus
import app.git.graph.GraphNode
import app.theme.headerBackground
import app.theme.headerText
import app.theme.primaryTextColor
import app.theme.secondaryTextColor
import app.ui.components.ScrollableLazyColumn
import org.eclipse.jgit.lib.ObjectIdRef
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.revwalk.RevCommit

private val colors = listOf(
    Color(0xFF42a5f5),
    Color(0xFFef5350),
    Color(0xFFe78909c),
    Color(0xFFff7043),
    Color(0xFF66bb6a),
    Color(0xFFec407a),
)

private const val CANVAS_MIN_WIDTH = 100

// TODO Min size for message column
// TODO Horizontal scroll for the graph
@OptIn(
    ExperimentalDesktopApi::class, ExperimentalFoundationApi::class,
    ExperimentalComposeUiApi::class
)
@Composable
fun Log(
    gitManager: GitManager,
    onRevCommitSelected: (RevCommit) -> Unit,
    onUncommitedChangesSelected: () -> Unit,
    selectedIndex: MutableState<Int> = remember { mutableStateOf(-1) }
) {
    val logStatusState = gitManager.logStatus.collectAsState()
    val logStatus = logStatusState.value

    val selectedUncommited = remember { mutableStateOf(false) }

    if (logStatus is LogStatus.Loaded) {
        val commitList = logStatus.plotCommitList

        Card(
            modifier = Modifier
                .padding(8.dp)
                .background(MaterialTheme.colors.surface)
                .fillMaxSize()
        ) {
            val hasUncommitedChanges by gitManager.hasUncommitedChanges.collectAsState()
            var weightMod by remember { mutableStateOf(0f) }
            var graphWidth = (CANVAS_MIN_WIDTH + weightMod).dp//(weightMod / 500)

            if (graphWidth.value < CANVAS_MIN_WIDTH)
                graphWidth = CANVAS_MIN_WIDTH.dp

            ScrollableLazyColumn(
                modifier = Modifier
                    .background(MaterialTheme.colors.surface)
                    .fillMaxSize(),
            ) {

                stickyHeader {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp)
                            .background(MaterialTheme.colors.headerBackground),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            modifier = Modifier
                                .width(graphWidth)
                                .padding(start = 8.dp),
                            text = "Graph",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colors.headerText,
                            fontSize = 14.sp,
                            maxLines = 1,
                        )

                        DividerLog(
                            modifier = Modifier.draggable(rememberDraggableState {
                                weightMod += it
                            }, Orientation.Horizontal)
                        )

                        Text(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .width(graphWidth),
                            text = "Message",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colors.headerText,
                            fontSize = 14.sp,
                            maxLines = 1,
                        )
                    }
                }

                if (hasUncommitedChanges)
                    item {
                        val textColor = if (selectedUncommited.value) {
                            MaterialTheme.colors.primary
                        } else
                            MaterialTheme.colors.primaryTextColor

                        Row(
                            modifier = Modifier
                                .height(40.dp)
                                .fillMaxWidth()
                                .clickable {
                                    selectedIndex.value = -1
                                    selectedUncommited.value = true
                                    onUncommitedChangesSelected()
                                },
                        ) {
                            val hasPreviousCommits = remember(commitList) { commitList.count() > 0 }

                            UncommitedChangesGraphLine(
                                modifier = Modifier
                                    .width(graphWidth),
                                hasPreviousCommits = hasPreviousCommits,
                            )

                            DividerLog(
                                modifier = Modifier
                                    .draggable(
                                        rememberDraggableState {
                                            weightMod += it
                                        },
                                        Orientation.Horizontal
                                    )
                            )

                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Spacer(modifier = Modifier.weight(2f))

                                Text(
                                    text = "Uncommited changes",
                                    fontStyle = FontStyle.Italic,
                                    modifier = Modifier.padding(start = 16.dp),
                                    fontSize = 14.sp,
                                    color = textColor,
                                )

                                Spacer(modifier = Modifier.weight(2f))
                            }
                        }
                    }

                itemsIndexed(items = commitList) { index, item ->
                    val commitRefs = remember(commitList, item) {
                        item.refs
                    }
                    Row(
                        modifier = Modifier
                            .height(40.dp)
                            .fillMaxWidth()
                            .clickable {
                                selectedIndex.value = index
                                selectedUncommited.value = false
                                onRevCommitSelected(item)
                            },
                    ) {
                        CommitsGraphLine(
                            modifier = Modifier
                                .width(graphWidth),
                            plotCommit = item
                        )

                        DividerLog(
                            modifier = Modifier
                                .draggable(
                                    rememberDraggableState {
                                        weightMod += it
                                    },
                                    Orientation.Horizontal
                                )
                        )

                        CommitMessage(
                            modifier = Modifier.weight(1f),
                            commit = item,
                            selected = selectedIndex.value == index,
                            refs = commitRefs,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CommitMessage(
    modifier: Modifier = Modifier,
    commit: RevCommit,
    selected: Boolean,
    refs: List<Ref>
) {
    val textColor = if (selected) {
        MaterialTheme.colors.primary
    } else
        MaterialTheme.colors.primaryTextColor

    val secondaryTextColor = if (selected) {
        MaterialTheme.colors.primary
    } else
        MaterialTheme.colors.secondaryTextColor


    Column(
        modifier = modifier
    ) {
        Spacer(modifier = Modifier.weight(2f))
        Row(
            modifier = Modifier
                .fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            refs.forEach {
                RefChip(
                    modifier = Modifier
                        .padding(horizontal = 4.dp),
                    ref = it,
                )
            }

            Text(
                text = commit.shortMessage,
                modifier = Modifier.padding(start = 16.dp),
                fontSize = 14.sp,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.weight(2f))

            Text(
                text = commit.committerIdent.`when`.toSmartSystemString(),
                modifier = Modifier.padding(horizontal = 16.dp),
                fontSize = 12.sp,
                color = secondaryTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

        }
        Spacer(modifier = Modifier.weight(2f))
    }

}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DividerLog(modifier: Modifier) {
    Box(
        modifier = Modifier
            .width(8.dp)
            .then(modifier)
            .pointerIcon(PointerIcon.Hand)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(color = MaterialTheme.colors.primary)
                .align(Alignment.Center)
        )
    }
}


@Composable
fun CommitsGraphLine(
    modifier: Modifier = Modifier,
    plotCommit: GraphNode,
) {
    val passingLanes = remember(plotCommit) {
        plotCommit.passingLanes
    }

    val forkingOffLanes = remember(plotCommit) { plotCommit.forkingOffLanes }
    val mergingLanes = remember(plotCommit) { plotCommit.mergingLanes }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
        ) {
            val itemPosition = plotCommit.lane.position
            clipRect {
                if (plotCommit.childCount > 0) {
                    drawLine(
                        color = colors[itemPosition % colors.size],
                        start = Offset(20f * (itemPosition + 1), this.center.y),
                        end = Offset(20f * (itemPosition + 1), 0f),
                    )
                }

                forkingOffLanes.forEach { plotLane ->
                    drawLine(
                        color = colors[plotLane.position % colors.size],
                        start = Offset(20f * (itemPosition + 1), this.center.y),
                        end = Offset(20f * (plotLane.position + 1), 0f),
                    )
                }

                mergingLanes.forEach { plotLane ->
                    drawLine(
                        color = colors[plotLane.position % colors.size],
                        start = Offset(20f * (plotLane.position + 1), this.size.height),
                        end = Offset(20f * (itemPosition + 1), this.center.y),
                    )
                }

                if (plotCommit.parentCount > 0) {
                    drawLine(
                        color = colors[itemPosition % colors.size],
                        start = Offset(20f * (itemPosition + 1), this.center.y),
                        end = Offset(20f * (itemPosition + 1), this.size.height),
                    )
                }

                passingLanes.forEach { plotLane ->
                    drawLine(
                        color = colors[plotLane.position % colors.size],
                        start = Offset(20f * (plotLane.position + 1), 0f),
                        end = Offset(20f * (plotLane.position + 1), this.size.height),
                    )
                }

                drawCircle(
                    color = colors[itemPosition % colors.size],
                    radius = 10f,
                    center = Offset(20f * (itemPosition + 1), this.center.y),
                )
            }
        }
    }
}

@Composable
fun UncommitedChangesGraphLine(
    modifier: Modifier = Modifier,
    hasPreviousCommits: Boolean,
) {
    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
        ) {
            clipRect {

                if (hasPreviousCommits)
                    drawLine(
                        color = colors[0],
                        start = Offset(20f, this.center.y),
                        end = Offset(20f, this.size.height),
                    )

                drawCircle(
                    color = colors[0],
                    radius = 10f,
                    center = Offset(20f, this.center.y),
                )
            }
        }
    }
}

@Composable
fun RefChip(modifier: Modifier = Modifier, ref: Ref) {
    val icon = remember(ref) {
        if(ref is ObjectIdRef.PeeledTag) {
            "tag.svg"
        } else
            "branch.svg"
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colors.primary),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            modifier = Modifier
                .padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
                .size(14.dp),
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colors.onPrimary,
        )
        Text(
            text = ref.simpleName,
            color = MaterialTheme.colors.onPrimary,
            fontSize = 12.sp,
            modifier = Modifier
                .padding(end = 6.dp)
        )
    }
}