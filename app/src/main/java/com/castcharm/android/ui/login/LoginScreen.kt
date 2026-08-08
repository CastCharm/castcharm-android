package com.castcharm.android.ui.login

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

// LoginScreen is shown when AppSessionManager reports NotLoggedIn. It collects
// three inputs (server URL, username, password) and delegates to LoginViewModel.login().
// Navigation away happens via onLoginSuccess() which is passed from CastCharmNavigation.
@Composable
fun LoginScreen(
    viewModel: LoginViewModel = viewModel(),
    onLoginSuccess: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    // showPassword is UI-only state — stored with remember so it survives
    // recomposition but is not persisted (intentional: always starts hidden).
    var showPassword by remember { mutableStateOf(false) }

    // Navigate away as soon as LoginViewModel signals success. Using LaunchedEffect
    // rather than a direct call prevents side effects during composition.
    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn) {
            onLoginSuccess()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Logo
        Text(
            text = "CastCharm",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 32.sp
            ),
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Self-hosted podcast manager",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 48.dp)
        )

        // The credential fields only appear once we know they're needed —
        // stops users on a passwordless server from wondering why blank fields
        // are staring at them.
        val showCredentials = uiState.phase == LoginPhase.NEEDS_CREDS

        // Server URL. Enabled unless we're mid-request; editing it resets the
        // phase back to URL_ONLY so a change hides stale credential fields.
        OutlinedTextField(
            value = uiState.serverUrl,
            onValueChange = { viewModel.updateServerUrl(it) },
            label = { Text("Server URL") },
            placeholder = { Text("http://192.168.1.100:8000") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            singleLine = true,
            enabled = !uiState.isLoading,
            supportingText = {
                if (uiState.serverUrl.isNotBlank() && !isValidUrl(uiState.serverUrl)) {
                    Text("Must start with http:// or https://", color = MaterialTheme.colorScheme.error)
                }
            },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Uri
            )
        )

        if (showCredentials) {
            // Username
            OutlinedTextField(
                value = uiState.username,
                onValueChange = { viewModel.updateUsername(it) },
                label = { Text("Username") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                singleLine = true,
                enabled = !uiState.isLoading
            )

            // Password
            OutlinedTextField(
                value = uiState.password,
                onValueChange = { viewModel.updatePassword(it) },
                label = { Text("Password") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                singleLine = true,
                enabled = !uiState.isLoading,
                visualTransformation = if (showPassword) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrect = false,
                    imeAction = ImeAction.Done
                ),
                trailingIcon = {
                    IconButton(
                        onClick = { showPassword = !showPassword },
                        enabled = !uiState.isLoading
                    ) {
                        Icon(
                            imageVector = if (showPassword) Icons.Default.Visibility
                                          else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle password visibility"
                        )
                    }
                }
            )
        }

        // Error
        if (uiState.errorMessage != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    text = uiState.errorMessage!!,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Connect / Login button. The label and target action depend on which
        // phase we're in: URL_ONLY tests the server, NEEDS_CREDS submits the
        // password. NO_PASSWORD auto-progresses inside connect() so it never
        // becomes a visible phase here.
        Button(
            onClick = {
                if (showCredentials) viewModel.login() else viewModel.connect()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            enabled = !uiState.isLoading && isValidUrl(uiState.serverUrl)
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(22.dp)
                        .semantics { contentDescription = "Connecting" },
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
                Text(if (showCredentials) "Signing in..." else "Connecting...")
            } else {
                Text(if (showCredentials) "Login" else "Connect")
            }
        }
    }
}

// Returns true for blank input (don't flag an empty field as invalid — just
// disable the login button). Returns true only for http/https URLs so the
// error hint is shown for obviously wrong values like "192.168.1.100:8000".
private fun isValidUrl(url: String): Boolean {
    if (url.isBlank()) return true
    return url.startsWith("http://") || url.startsWith("https://")
}
