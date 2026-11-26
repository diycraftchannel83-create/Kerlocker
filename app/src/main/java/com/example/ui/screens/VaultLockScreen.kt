package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.biometric.BiometricAuthHelper
import com.example.biometric.BiometricAvailability
import com.example.biometric.findFragmentActivity
import com.example.ui.SetupStep
import com.example.ui.VaultUiState
import com.example.ui.components.AesBadgePill
import com.example.ui.components.PinDotsIndicator
import com.example.ui.components.TactilePinKeypad
import kotlinx.coroutines.delay

@Composable
fun VaultLockScreen(
    uiState: VaultUiState,
    onDigitEntered: (Char) -> Unit,
    onBackspace: () -> Unit,
    onBiometricSuccess: () -> Unit,
    onCompleteInitialSetup: (Boolean) -> Unit,
    onRestoreFromExternalFolder: (folderUri: String, pin: String, onError: (String) -> Unit) -> Unit,
    onBiometricError: (String) -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    var hasAutoPrompted by remember(uiState.isLocked, uiState.isSetupComplete) { mutableStateOf(false) }
    var biometricHint by remember(uiState.isLocked, uiState.isSetupComplete) { mutableStateOf<String?>(null) }
    var pendingRestoreFolderUri by remember { mutableStateOf<String?>(null) }

    val restoreFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            pendingRestoreFolderUri = treeUri.toString()
        }
    }

    val canUseBiometric = uiState.isSetupComplete &&
        uiState.securityPrefs.biometricEnabled &&
        uiState.biometricAvailability == BiometricAvailability.AVAILABLE &&
        uiState.lockoutSecondsRemaining == 0L

    val triggerBiometric = remember(activity, onBiometricSuccess, onBiometricError) {
        {
            if (activity != null) {
                biometricHint = null
                BiometricAuthHelper.showBiometricPrompt(
                    activity = activity,
                    onSuccess = {
                        biometricHint = null
                        onBiometricSuccess()
                    },
                    onAuthenticationFailedAttempt = {
                        biometricHint = "Sidik jari tidak cocok. Coba sentuh sensor lagi atau ketik PIN."
                    },
                    onError = { msg ->
                        biometricHint = msg
                        onBiometricError(msg)
                    },
                    onCancelOrUsePin = {
                        biometricHint = null
                    }
                )
            } else {
                val msg = "Host biometrik tidak tersedia. Gunakan PIN 6 digit."
                biometricHint = msg
                onBiometricError(msg)
            }
        }
    }

    LaunchedEffect(uiState.isPrefsLoaded, canUseBiometric, uiState.isLocked) {
        if (uiState.isPrefsLoaded && uiState.isLocked && canUseBiometric && !hasAutoPrompted) {
            delay(150L)
            hasAutoPrompted = true
            triggerBiometric()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (!uiState.isPrefsLoaded) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            return@Surface
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // Subtle atmospheric header artwork
            Image(
                painter = painterResource(id = R.drawable.img_vault_shield_hero_1795646836203),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .alpha(0.22f)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.widthIn(max = 420.dp)
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    AesBadgePill(
                        text = if (uiState.isHardwareBackedKeystore) {
                            "AES-256-GCM • HARDWARE KEYSTORE"
                        } else {
                            "AES-256-GCM • KEYSTORE"
                        }
                    )
                    Spacer(modifier = Modifier.height(20.dp))

                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
                            .border(
                                1.5.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (!uiState.isSetupComplete && uiState.setupStep == SetupStep.BIOMETRIC_OPT_IN) {
                                Icons.Rounded.Fingerprint
                            } else {
                                Icons.Rounded.Lock
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    if (!uiState.isSetupComplete && uiState.setupStep == SetupStep.BIOMETRIC_OPT_IN) {
                        BiometricOptInContent(
                            onEnableBiometric = {
                                if (activity != null) {
                                    BiometricAuthHelper.showBiometricPrompt(
                                        activity = activity,
                                        title = "Verifikasi Sidik Jari",
                                        subtitle = "Daftarkan autentikasi biometrik untuk membuka Locker dengan cepat",
                                        negativeButtonText = "Batal",
                                        onSuccess = { onCompleteInitialSetup(true) },
                                        onAuthenticationFailedAttempt = {
                                            onBiometricError("Sidik jari tidak dikenali, coba lagi.")
                                        },
                                        onError = { msg -> onBiometricError(msg) }
                                    )
                                } else {
                                    onBiometricError("Sensor biometrik tidak tersedia di layar ini.")
                                }
                            },
                            onSkip = { onCompleteInitialSetup(false) }
                        )
                    } else {
                        val title = when {
                            !uiState.isSetupComplete && uiState.setupStep == SetupStep.CREATE_PIN ->
                                "Buat PIN Brankas"
                            !uiState.isSetupComplete && uiState.setupStep == SetupStep.CONFIRM_PIN ->
                                "Konfirmasi PIN Anda"
                            else -> "Locker Terkunci"
                        }
                        val subtitle = when {
                            !uiState.isSetupComplete && uiState.setupStep == SetupStep.CREATE_PIN ->
                                "Masukkan 6 digit PIN utama untuk melindungi folder dan file terenkripsi Anda."
                            !uiState.isSetupComplete && uiState.setupStep == SetupStep.CONFIRM_PIN ->
                                "Masukkan kembali 6 digit PIN yang sama untuk verifikasi."
                            uiState.lockoutSecondsRemaining > 0L ->
                                "Terlalu banyak percobaan gagal. Coba lagi dalam ${uiState.lockoutSecondsRemaining} detik."
                            canUseBiometric ->
                                "Masukkan 6 digit PIN atau sentuh sensor sidik jari untuk membuka."
                            else ->
                                "Masukkan 6 digit PIN untuk mengakses brankas terenkripsi."
                        }

                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (uiState.lockoutSecondsRemaining > 0L) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(28.dp))
                        PinDotsIndicator(
                            pinLength = uiState.pinInput.length,
                            isError = uiState.pinError != null
                        )

                        val feedbackMessage = uiState.pinError ?: biometricHint
                        AnimatedVisibility(
                            visible = feedbackMessage != null,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Text(
                                text = feedbackMessage.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                    }
                }

                if (uiState.isSetupComplete || uiState.setupStep != SetupStep.BIOMETRIC_OPT_IN) {
                    Spacer(modifier = Modifier.height(24.dp))
                    TactilePinKeypad(
                        onDigitClick = onDigitEntered,
                        onBackspaceClick = onBackspace,
                        onBiometricClick = if (canUseBiometric) triggerBiometric else null,
                        enabled = uiState.lockoutSecondsRemaining == 0L
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    if (canUseBiometric) {
                        OutlinedButton(
                            onClick = triggerBiometric,
                            modifier = Modifier.testTag("unlock_fingerprint_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Fingerprint,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Buka dengan Sidik Jari")
                        }
                    } else if (!uiState.isSetupComplete && uiState.setupStep == SetupStep.CREATE_PIN) {
                        OutlinedButton(
                            onClick = { restoreFolderPicker.launch(null) },
                            modifier = Modifier.testTag("restore_existing_folder_setup_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Restore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Pulihkan dari Folder Locker")
                        }
                    } else {
                        Text(
                            text = "File tersembunyi di penyimpanan internal terisolasi",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    pendingRestoreFolderUri?.let { folderUri ->
        RestoreFolderPinDialog(
            isLoading = uiState.isRecoveringApp,
            onDismiss = { if (!uiState.isRecoveringApp) pendingRestoreFolderUri = null },
            onConfirm = { pin, onError ->
                onRestoreFromExternalFolder(folderUri, pin, onError)
            }
        )
    }
}

@Composable
private fun RestoreFolderPinDialog(
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (pin: String, onError: (String) -> Unit) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pulihkan Brankas Sebelumnya") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Masukkan 6 digit PIN yang sebelumnya digunakan untuk mengenkripsi folder Locker tersebut.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        if (it.length <= 6 && it.all(Char::isDigit)) {
                            pin = it
                            errorText = null
                        }
                    },
                    label = { Text("PIN Sebelumnya (6 digit)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
                errorText?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (isLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Mendekripsi & memulihkan file...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (pin.length != 6) {
                        errorText = "Masukkan tepat 6 digit PIN"
                    } else {
                        onConfirm(pin) { err -> errorText = err }
                    }
                },
                enabled = !isLoading && pin.length == 6
            ) {
                Text("Pulihkan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("Batal")
            }
        }
    )
}

@Composable
private fun BiometricOptInContent(
    onEnableBiometric: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "Aktifkan Sidik Jari?",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Buka Locker secara instan dan aman menggunakan sensor sidik jari perangkat Anda tanpa mengetik PIN setiap saat.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(28.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Keamanan Berlapis Aktif",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "• Enkripsi AES-256-GCM dengan kunci di Android Keystore\n• Folder tujuan terenkripsi .vault dapat dipulihkan lintas instalasi\n• Perlindungan PIN PBKDF2-HMAC-SHA256 + Sidik Jari",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
        Button(
            onClick = onEnableBiometric,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("enable_biometric_setup_button"),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Rounded.Fingerprint, contentDescription = null)
            Spacer(modifier = Modifier.size(8.dp))
            Text("Aktifkan Sidik Jari", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(modifier = Modifier.height(10.dp))
        TextButton(
            onClick = onSkip,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("skip_biometric_setup_button")
        ) {
            Text("Lewati, Gunakan PIN Saja")
        }
    }
}
