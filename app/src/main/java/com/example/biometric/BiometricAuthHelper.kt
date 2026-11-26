package com.example.biometric

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

enum class BiometricAvailability {
    AVAILABLE,
    NOT_ENROLLED,
    UNAVAILABLE
}

tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

object BiometricAuthHelper {

    private const val ALLOWED_AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK

    fun checkAvailability(context: Context): BiometricAvailability {
        val manager = BiometricManager.from(context)
        return when (manager.canAuthenticate(ALLOWED_AUTHENTICATORS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NOT_ENROLLED
            else -> BiometricAvailability.UNAVAILABLE
        }
    }

    fun showBiometricPrompt(
        activity: FragmentActivity,
        title: String = "Buka Locker",
        subtitle: String = "Gunakan sidik jari untuk membuka akses file terenkripsi",
        negativeButtonText: String = "Gunakan PIN",
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {},
        onAuthenticationFailedAttempt: () -> Unit = {},
        onCancelOrUsePin: () -> Unit = {}
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                onAuthenticationFailedAttempt()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                when (errorCode) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_CANCELED -> onCancelOrUsePin()
                    BiometricPrompt.ERROR_LOCKOUT -> onError(
                        "Sensor sidik jari terkunci sementara. Silakan buka dengan PIN 6 digit."
                    )
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> onError(
                        "Sensor sidik jari terkunci oleh sistem. Gunakan PIN 6 digit Anda."
                    )
                    BiometricPrompt.ERROR_NO_BIOMETRICS -> onError(
                        "Belum ada sidik jari terdaftar di Pengaturan perangkat."
                    )
                    else -> onError(
                        errString.toString().ifBlank { "Autentikasi sidik jari gagal." }
                    )
                }
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
            .setConfirmationRequired(false)
            .build()

        runCatching {
            prompt.authenticate(promptInfo)
        }.onFailure { err ->
            onError(err.localizedMessage ?: "Gagal menampilkan dialog sidik jari.")
        }
    }
}
