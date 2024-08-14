import android.hardware.fingerprint.FingerprintManager
import android.os.CancellationSignal
import android.content.Context

class FingerprintHelper(context: Context) {
    private var fingerprintManager: FingerprintManager? = null
    private var cancellationSignal: CancellationSignal? = null

    init {
        fingerprintManager = context.getSystemService(Context.FINGERPRINT_SERVICE) as FingerprintManager
    }

    fun enableFingerprint() {
        cancellationSignal = CancellationSignal()
        fingerprintManager?.authenticate(null, cancellationSignal, 0, object : FingerprintManager.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: FingerprintManager.AuthenticationResult?) {
                super.onAuthenticationSucceeded(result)
                // Handle success
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // Handle failure
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                super.onAuthenticationError(errorCode, errString)
                // Handle error
            }
        }, null)
    }

    fun disableFingerprint() {
        cancellationSignal?.cancel()
    }
}
