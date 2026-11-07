package com.zandaulion.bitey.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Where a tap on the watch has got to. */
sealed interface Status {
    data object Idle : Status
    data class Working(val label: String) : Status
    data class Logged(val id: String, val label: String) : Status
    data object Undone : Status
    data class Failed(val message: Int) : Status
}

/**
 * Today, the quick bites and the meals to repeat. A tap logs at once, through
 * the phone, and offers Undo for a few seconds: on a wrist a second "are you
 * sure" costs more than putting one mistake right.
 *
 * Opened from a quick bite on the tile, it logs that and closes again once
 * the Undo has had its time, back to wherever the person was.
 */
class MainActivity : ComponentActivity() {
    private val status = MutableStateFlow<Status>(Status.Idle)
    private var fromTile = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            val today by TodayStore.flow(applicationContext).collectAsState()
            val now by status.collectAsState()
            BiteyApp(
                today = today?.asOf(LocalDate.now()),
                status = now,
                onChoose = ::log,
                onUndo = ::undo,
                onDone = ::done,
            )
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        // First launch, or after the watch lost what it had: ask for today.
        if (TodayStore.current(applicationContext) == null) {
            lifecycleScope.launch { Phone.refresh(applicationContext) }
        }
    }

    private fun handle(intent: Intent?) {
        val key = intent?.getStringExtra(EXTRA_CHOICE) ?: return
        // Consumed once: recreating the activity must not log it again.
        intent.removeExtra(EXTRA_CHOICE)
        val today = TodayStore.current(applicationContext) ?: return
        val choice = (today.bites + today.meals).firstOrNull { it.key == key } ?: return
        fromTile = true
        log(choice)
    }

    private fun log(choice: Choice) {
        if (status.value is Status.Working) return
        status.value = Status.Working(choice.label)
        lifecycleScope.launch {
            status.value = when (val reply = Phone.log(applicationContext, choice.key)) {
                is PhoneReply.Logged -> Status.Logged(reply.id, reply.label.ifBlank { choice.label })
                PhoneReply.Unreachable -> Status.Failed(R.string.unreachable)
                PhoneReply.Unconfirmed -> Status.Failed(R.string.unconfirmed)
                else -> Status.Failed(R.string.failed)
            }
        }
    }

    private fun undo(id: String) {
        status.value = Status.Working("")
        lifecycleScope.launch {
            status.value = when (Phone.undo(applicationContext, id)) {
                PhoneReply.Done -> Status.Undone
                PhoneReply.Unreachable -> Status.Failed(R.string.unreachable)
                PhoneReply.Unconfirmed -> Status.Failed(R.string.unconfirmed)
                else -> Status.Failed(R.string.failed)
            }
        }
    }

    private fun done() {
        status.value = Status.Idle
        if (fromTile) finish()
    }

    companion object {
        const val EXTRA_CHOICE = "choice"
    }
}

@Composable
fun BiteyApp(
    today: Today?,
    status: Status,
    onChoose: (Choice) -> Unit,
    onUndo: (String) -> Unit,
    onDone: () -> Unit,
) {
    MaterialTheme {
        AppScaffold {
            val nav = rememberSwipeDismissableNavController()
            val latest by rememberUpdatedState(status)
            // The status decides the screen: a tap opens the result, and
            // ending it returns to the list.
            LaunchedEffect(status == Status.Idle) {
                val onResult = nav.currentDestination?.route == RESULT
                if (status != Status.Idle && !onResult) nav.navigate(RESULT) { launchSingleTop = true }
                if (status == Status.Idle && onResult) nav.popBackStack()
            }
            // Back from the result by swipe or the watch's back key: the host
            // pops it, and only once the swipe has finished is it over.
            LaunchedEffect(nav) {
                nav.currentBackStackEntryFlow.collect { entry ->
                    if (entry.destination.route == HOME && latest != Status.Idle) onDone()
                }
            }
            SwipeDismissableNavHost(navController = nav, startDestination = HOME) {
                composable(HOME) { Home(today, onChoose) }
                composable(RESULT) { StatusScreen(today, latest, onUndo, onDone) }
            }
        }
    }
}

private const val HOME = "home"
private const val RESULT = "result"

@Composable
private fun Home(today: Today?, onChoose: (Choice) -> Unit) {
    // A transforming list, not a scaling one: rows narrow and round off as
    // they reach the curved top and bottom, so no button runs past the edge
    // of a round screen mid-scroll (Play's watch shapes check found exactly
    // that). It starts at the top: centred, the ring runs into the time.
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = padding,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (today == null) {
                item {
                    Text(
                        stringResource(R.string.waiting),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                return@TransformingLazyColumn
            }
            // Not transformed: it is no surface to morph, and shrinking its
            // height alone lets the header below ride over the macros line.
            item { Summary(today) }
            if (today.bites.isNotEmpty()) {
                item { Header(today.word("bites"), SurfaceTransformation(spec), Modifier.transformedHeight(this, spec)) }
                items(today.bites) { bite ->
                    Button(
                        onClick = { onChoose(bite) },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                    ) {
                        ChoiceLabel(bite, today)
                    }
                }
            }
            if (today.meals.isNotEmpty()) {
                item { Header(today.word("meals"), SurfaceTransformation(spec), Modifier.transformedHeight(this, spec)) }
                items(today.meals) { meal ->
                    FilledTonalButton(
                        onClick = { onChoose(meal) },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                        transformation = SurfaceTransformation(spec),
                    ) {
                        ChoiceLabel(meal, today)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String, transformation: SurfaceTransformation, modifier: Modifier) {
    ListHeader(modifier = modifier.fillMaxWidth(), transformation = transformation) { Text(text) }
}

@Composable
private fun ChoiceLabel(choice: Choice, today: Today) {
    if (choice.icon.isNotBlank()) {
        Text(choice.icon, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 8.dp))
    }
    Column {
        Text(choice.label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        Text("+${choice.kcal} ${today.word("kcal")}", style = MaterialTheme.typography.bodySmall)
    }
}

/** The day's figure inside a ring that fills towards the burn estimate. */
@Composable
private fun Summary(today: Today) {
    val track = MaterialTheme.colorScheme.surfaceContainer
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(112.dp)) {
            Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                val width = 8.dp.toPx()
                drawArc(track, startAngle = -90f, sweepAngle = 360f, useCenter = false, style = Stroke(width))
                // Protein, carbs and fat one after another, each by energy.
                var start = -90f
                today.ring.zip(MacroColours.ALL).forEach { (share, colour) ->
                    val sweep = 360f * share
                    if (sweep > 0f) {
                        drawArc(Color(colour), start, sweep, useCenter = false, style = Stroke(width, cap = StrokeCap.Butt))
                    }
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(today.kcal.toString(), style = MaterialTheme.typography.displaySmall)
                Text(today.word("kcal"), style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(
            today.eaten,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        // The ring's legend: each macro in its arc's colour.
        Text(
            buildAnnotatedString {
                today.macroParts().zip(MacroColours.ALL).forEachIndexed { i, (part, colour) ->
                    if (i > 0) append(" · ")
                    withStyle(SpanStyle(color = Color(colour))) { append(part) }
                }
            },
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatusScreen(today: Today?, status: Status, onUndo: (String) -> Unit, onDone: () -> Unit) {
    LaunchedEffect(status) {
        when (status) {
            is Status.Logged -> { delay(6_000); onDone() }
            Status.Undone -> { delay(1_500); onDone() }
            is Status.Failed -> { delay(6_000); onDone() }
            else -> Unit
        }
    }
    val words = today ?: Today.parse("""{"day":""}""")!!
    // A list in the scaffold, not a fixed column: on a small round watch three
    // lines and two buttons do not fit inside the circle, and this keeps every
    // part inside the round screen's safe area and reachable by scrolling.
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            modifier = Modifier.fillMaxSize(),
        ) {
            when (status) {
                is Status.Working -> item { Message("${status.label}…") }
                is Status.Logged -> {
                    item { Message(words.logged(status.label)) }
                    item {
                        FilledTonalButton(
                            onClick = { onUndo(status.id) },
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                            transformation = SurfaceTransformation(spec),
                        ) {
                            Text(words.word("undo"), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    item { BackButton(words, onDone, Modifier.transformedHeight(this, spec), SurfaceTransformation(spec)) }
                }
                Status.Undone -> item { Message(words.word("undone")) }
                is Status.Failed -> {
                    item { Message(stringResource(status.message)) }
                    item { BackButton(words, onDone, Modifier.transformedHeight(this, spec), SurfaceTransformation(spec)) }
                }
                Status.Idle -> Unit
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun BackButton(words: Today, onDone: () -> Unit, modifier: Modifier, transformation: SurfaceTransformation) {
    Button(onClick = onDone, modifier = modifier.fillMaxWidth(), transformation = transformation) {
        Text(words.word("back"), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
