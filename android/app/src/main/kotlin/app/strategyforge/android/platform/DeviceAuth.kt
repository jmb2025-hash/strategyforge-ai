package app.strategyforge.android.platform

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat

/**
 * "Confirm it's you" on the phone (D-029): the device's own lock (fingerprint, face or screen-lock
 * PIN, pattern or password). A success confirms recent authentication for the engine; nothing is
 * stored by the app.
 */
object DeviceAuth {
    /** False when the phone has no screen lock; then there is nothing to confirm against. */
    fun available(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    fun prompt(
        context: Context,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onCancel: () -> Unit,
    ) {
        if (!available(context)) {
            onSuccess()
            return
        }
        val builder =
            BiometricPrompt
                .Builder(context)
                .setTitle(title)
                .setSubtitle(subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(
            CancellationSignal(),
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) = onCancel()
            },
        )
    }
}
