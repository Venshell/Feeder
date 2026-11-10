package com.nononsenseapps.feeder.ui.compose.feed

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.model.LoadOlderMessage
import com.nononsenseapps.feeder.model.LoadOlderUiState

@Composable
fun LoadOlderFooter(
    state: LoadOlderUiState,
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.visible) return
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            state.loading -> {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(id = R.string.loading_older_articles),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.message == LoadOlderMessage.Failed -> {
                Text(
                    text = stringResource(id = R.string.load_older_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onLoadOlder) {
                    Text(text = stringResource(id = R.string.load_older_retry))
                }
            }

            state.message == LoadOlderMessage.NoOlder -> {
                Text(
                    text = stringResource(id = R.string.no_older_articles),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.showAction -> {
                TextButton(onClick = onLoadOlder) {
                    Text(text = stringResource(id = R.string.load_older_articles))
                }
            }
        }
    }
}
