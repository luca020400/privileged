package org.fdroid.fdroid.privileged

import android.content.ContentResolver
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Looper
import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * Task that installs an APK. This must not be called on the main thread.
 * This code is based off the Finsky/Wearsky implementation
 */
class InstallTask(
    private val contentResolver: ContentResolver,
    private val mPackageName: String,
    private val mPackageURIs: List<Uri>,
    private val mCallback: IPrivilegedCallback,
    private val mSession: PackageInstaller.Session, private val mCommitCallback: IntentSender
) {
    private var mError = false

    val error
        get() = mError

    fun execute() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "This method cannot be called from the UI thread." }
        var sessionStream: OutputStream? = null
        var exception: Exception? = null
        try {
            sessionStream = mSession.openWrite(mPackageName, 0, -1)

            // 2b: Stream the asset to the installer. Note:
            writeToOutputStreamFromUri(sessionStream)
            mSession.fsync(sessionStream)
        } catch (e: Exception) {
            exception = e
            mError = true
        } finally {
            if (sessionStream != null) {
                // 2c: close output stream
                try {
                    sessionStream.close()
                } catch (e: Exception) {
                    // Ignore otherwise
                    if (exception == null) {
                        exception = e
                        mError = true
                    }
                }
            }
        }
        if (mError) {
            // An error occurred, we're done
            Log.e(
                TAG,
                "Exception while installing $mPackageName: $exception"
            )
            mSession.close()
            mCallback.handleResult(mPackageName, PackageInstaller.STATUS_FAILURE)
        } else {
            // 3. Commit the session (this actually installs it.)  Session map
            // will be cleaned up in the callback.
            mSession.commit(mCommitCallback)
            mSession.close()
        }
    }

    /**
     * `PackageInstaller` works with streams. Write the Uri contents into an
     * `OutputStream` that is passed in.
     * <br></br>
     * The `OutputStream` is not closed.
     */
    private fun writeToOutputStreamFromUri(outputStream: OutputStream?): Boolean {
        if (outputStream == null) {
            mError = true
            return false
        }
        var inputStream: InputStream? = null
        try {
            val inputBuf = ByteArray(DEFAULT_BUFFER_SIZE)
            var bytesRead: Int
            mPackageURIs.forEach { uri ->
                inputStream = contentResolver.openInputStream(uri)!!
                while (inputStream!!.read(inputBuf).also { bytesRead = it } > -1) {
                    if (bytesRead > 0) {
                        outputStream.write(inputBuf, 0, bytesRead)
                    }
                }
            }
            outputStream.flush()
        } catch (e: Exception) {
            mError = true
            return false
        } finally {
            safeClose(inputStream)
        }
        return true
    }

    companion object {
        private const val TAG = "InstallTask"
        private const val DEFAULT_BUFFER_SIZE = 1024 * 1024

        /**
         * Quietly close a closeable resource (e.g. a stream or file). The input may already
         * be closed and it may even be null.
         */
        fun safeClose(resource: Closeable?) {
            if (resource != null) {
                try {
                    resource.close()
                } catch (e: Exception) {
                    // Catch and discard the error
                }
            }
        }
    }

}