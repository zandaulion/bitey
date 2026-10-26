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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
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
            if (status == Status.Idle) Home(today, onChoose)
            else StatusScreen(today, status, onUndo, onDone)
        }
    }
}

@Composable
private fun Home(today: Today?, onChoose: (Choice) -> Unit) {
    // Starts at the top rather than centring the first item: centred, the
    // ring is tall enough to run into the time at the top of the screen.
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = padding,
            autoCentering = null,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (today == null) {
                item {
                    Text(
                        stringResource(R.string.waiting),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                return@ScalingLazyColumn
            }
            item { Summary(today) }
            if (today.bites.isNotEmpty()) {
                item { ListHeader { Text(today.word("bites")) } }
                items(today.bites) { bite ->
                    Button(onClick = { onChoose(bite) }, modifier = Modifier.fillMaxWidth()) {
                        ChoiceLabel(bite, today)
                    }
                }
            }
            if (today.meals.isNotEmpty()) {
                item { ListHeader { Text(today.word("meals")) } }
                items(today.meals) { meal ->
                    FilledTonalButton(onClick = { onChoose(meal) }, modifier = Modifier.fillMaxWidth()) {
                        ChoiceLabel(meal, today)
                    }
                }
            }
        }
    }
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
    val fill = MaterialTheme.colorScheme.primary
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(112.dp)) {
            Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                val stroke = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                drawArc(track, startAngle = -90f, sweepAngle = 360f, useCenter = false, style = stroke)
                val progress = today.progress
                if (progress != null && progress > 0f) {
                    drawArc(fill, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false, style = stroke)
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
        Text(today.macros(short = true), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(16.dp),
    ) {
        when (status) {
            is Status.Working -> Text("${status.label}…", textAlign = TextAlign.Center, maxLines = 3)
            is Status.Logged -> {
                Text(words.logged(status.label), textAlign = TextAlign.Center, maxLines = 3)
                FilledTonalButton(onClick = { onUndo(status.id) }, modifier = Modifier.padding(top = 12.dp)) {
                    Text(words.word("undo"))
                }
            }
            Status.Undone -> Text(words.word("undone"), textAlign = TextAlign.Center)
            is Status.Failed -> Text(stringResource(status.message), textAlign = TextAlign.Center)
            Status.Idle -> Unit
        }
    }
}
