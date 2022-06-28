package org.fdroid.fdroid.privileged

import android.app.PendingIntent
import android.app.Service
import android.content.*
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.SessionInfo
import android.content.pm.PackageInstaller.SessionParams
import android.net.Uri
import android.util.Log

class ExtensionService : Service() {
    companion object {
        const val TAG = "ExtensionService"

        /** Intent actions used for broadcasts from PackageInstaller back to the local receiver  */
        private const val ACTION_INSTALL_COMMIT =
            "com.android.vending.INTENT_PACKAGE_INSTALL_COMMIT"
        private const val ACTION_UNINSTALL_COMMIT =
            "com.android.vending.INTENT_PACKAGE_UNINSTALL_COMMIT"

        private const val BROADCAST_SENDER_PERMISSION =
            "org.fdroid.fdroid.privileged.BROADCAST_SENDER_PERMISSION"

        private const val INSTALL_SUCCEEDED = 1
        private const val INSTALL_FAILED_INTERNAL_ERROR = -110
        private const val DELETE_SUCCEEDED = 1
        private const val DELETE_FAILED_INTERNAL_ERROR = -1

        private val INSTALL_RETURN_CODES = mapOf(
            PackageInstaller.STATUS_SUCCESS to INSTALL_SUCCEEDED,
            PackageInstaller.STATUS_FAILURE to INSTALL_FAILED_INTERNAL_ERROR,
        ).withDefault { 0 }

        private val DELETE_RETURN_CODES = mapOf(
            PackageInstaller.STATUS_SUCCESS to DELETE_SUCCEEDED,
            PackageInstaller.STATUS_FAILURE to DELETE_FAILED_INTERNAL_ERROR,
        ).withDefault { 0 }
    }

    private val mAccessProtectionHelper by lazy {
        AccessProtectionHelper(this)
    }
    private val mSessionInfoMap: MutableMap<String?, SessionInfo?> = mutableMapOf()
    private val mOpenSessionMap: MutableMap<String?, PackageInstaller.Session?> = mutableMapOf()

    private fun uninstall(
        packageName: String,
        callback: IPrivilegedCallback
    ) {
        // Create a single-use broadcast receiver
        val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                this@ExtensionService.unregisterReceiver(this)
                val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                when (val status =
                    intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
                    PackageInstaller.STATUS_SUCCESS -> {
                        callback.handleResult(
                            packageName,
                            DELETE_RETURN_CODES.getValue(status)
                        )
                    }
                    else -> {
                        Log.e(
                            TAG, "Error $status while installing $packageName: $statusMessage"
                        )
                        callback.handleResult(
                            packageName,
                            DELETE_RETURN_CODES.getValue(status)
                        )
                    }
                }
            }
        }

        // Create a matching intent-filter and register the receiver
        val action = "$ACTION_UNINSTALL_COMMIT.$packageName"
        val intentFilter = IntentFilter()
        intentFilter.addAction(action)
        registerReceiver(broadcastReceiver, intentFilter, BROADCAST_SENDER_PERMISSION, null)

        // Create a matching PendingIntent and use it to generate the IntentSender
        val broadcastIntent = Intent(action)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            packageName.hashCode(),
            broadcastIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT
                    or PendingIntent.FLAG_MUTABLE
        )

        packageManager.setInstallerPackageName(packageName, getPackageName())
        packageManager.packageInstaller.uninstall(
            packageName,
            pendingIntent.intentSender
        )
    }

    /**
     * This implementation bundles an entire "session" into a single call.
     * This must not be called on main thread.
     */
    private fun install(
        packageName: String,
        packageUri: Uri,
        callback: IPrivilegedCallback
    ) {
        packageManager.setInstallerPackageName(packageName, getPackageName())
        // 0. Generic try/catch block because I am not really sure what exceptions
        // might be thrown by PackageInstaller and I want to handle them
        // at least slightly gracefully.
        try {
            // 1. Create or recover a session, and open it
            // Try recovery first
            var session: PackageInstaller.Session? = null
            var sessionInfo = mSessionInfoMap[packageName]
            if (sessionInfo != null) {
                // See if it's openable, or already held open
                session = getSession(packageName)
            }
            // If open failed, or there was no session, create a new one and open it.
            // If we cannot create or open here, the failure is terminal.
            if (session == null) {
                try {
                    innerCreateSession(packageName)
                } catch (e: Exception) {
                    Log.e(TAG, "Can't create session for $packageName ${e.message}")
                    callback.handleResult(
                        packageName,
                        INSTALL_RETURN_CODES.getValue(PackageInstaller.STATUS_FAILURE)
                    )
                    mSessionInfoMap.remove(packageName)
                    return
                }
                sessionInfo = mSessionInfoMap[packageName]
                try {
                    session = packageManager.packageInstaller.openSession(sessionInfo!!.sessionId)
                    mOpenSessionMap[packageName] = session
                } catch (e: Exception) {
                    Log.e(TAG, "Can't open session for $packageName: ${e.message}")
                    callback.handleResult(
                        packageName,
                        INSTALL_RETURN_CODES.getValue(PackageInstaller.STATUS_FAILURE)
                    )
                    mSessionInfoMap.remove(packageName)
                    return
                }
            }

            // 2. Launch task to handle file operations.
            val task = InstallTask(
                contentResolver,
                packageName, packageUri,
                callback, session,
                getCommitCallback(packageName, sessionInfo!!.sessionId, callback)
            )
            task.execute()
            if (task.error) {
                cancelSession(sessionInfo.sessionId, packageName)
            }
        } catch (e: Exception) {
            Log.e(
                TAG, "Unexpected exception while installing: $packageName: ${e.message}"
            )
            callback.handleResult(
                packageName,
                INSTALL_RETURN_CODES.getValue(PackageInstaller.STATUS_FAILURE)
            )
        }
    }

    /**
     * Retrieve an existing session. Will open if needed, but does not attempt to create.
     */
    private fun getSession(packageName: String): PackageInstaller.Session? {
        // Check for already-open session
        var session = mOpenSessionMap[packageName]
        if (session != null) {
            try {
                // Probe the session to ensure that it's still open. This may or may not
                // throw (if non-open), but it may serve as a canary for stale sessions.
                session.names
                return session
            } catch (e: Exception) {
                Log.e(TAG, "Stale open session for $packageName: ${e.message}")
                mOpenSessionMap.remove(packageName)
            }
        }
        // Check to see if this is a known session
        val sessionInfo = mSessionInfoMap[packageName] ?: return null
        // Try to open it. If we fail here, assume that the SessionInfo was stale.
        session = try {
            packageManager.packageInstaller.openSession(sessionInfo.sessionId)
        } catch (e: Exception) {
            Log.w(TAG, "SessionInfo was stale for $packageName - deleting info")
            mSessionInfoMap.remove(packageName)
            return null
        }
        mOpenSessionMap[packageName] = session
        return session
    }

    /** This version throws an IOException when the session cannot be created  */
    @Throws(Exception::class)
    private fun innerCreateSession(packageName: String) {
        if (mSessionInfoMap.containsKey(packageName)) {
            Log.w(TAG, "Creating session for $packageName when one already exists")
            return
        }
        val params = SessionParams(
            SessionParams.MODE_FULL_INSTALL
        ).apply {
            setRequireUserAction(SessionParams.USER_ACTION_NOT_REQUIRED)
            setAppPackageName(packageName)
        }

        // An exception may be thrown at this point
        val sessionId = packageManager.packageInstaller.createSession(params)
        val sessionInfo = packageManager.packageInstaller.getSessionInfo(sessionId)
        mSessionInfoMap[packageName] = sessionInfo
    }

    /**
     * Cancel a session based on its sessionId. Package name is for logging only.
     */
    private fun cancelSession(sessionId: Int, packageName: String) {
        // Close if currently held open
        closeSession(packageName)
        // Remove local record
        mSessionInfoMap.remove(packageName)
        try {
            packageManager.packageInstaller.abandonSession(sessionId)
        } catch (se: SecurityException) {
            // The session no longer exists, so we can exit quietly.
            return
        }
    }

    /**
     * Close a session if it happens to be held open.
     */
    private fun closeSession(packageName: String) {
        val session = mOpenSessionMap.remove(packageName)
        if (session != null) {
            // Unfortunately close() is not idempotent. Try our best to make this safe.
            try {
                session.close()
            } catch (e: Exception) {
                Log.w(
                    TAG, "Unexpected error closing session for $packageName: ${e.message}"
                )
            }
        }
    }

    /**
     * Creates a commit callback for the package install that's underway. This will be called
     * some time after calling session.commit() (above).
     */
    private fun getCommitCallback(
        packageName: String, sessionId: Int,
        callback: IPrivilegedCallback
    ): IntentSender {
        // Create a single-use broadcast receiver
        val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                this@ExtensionService.unregisterReceiver(this)
                handleCommitCallback(intent, packageName, sessionId, callback)
            }
        }
        // Create a matching intent-filter and register the receiver
        val action = "$ACTION_INSTALL_COMMIT.$packageName"
        val intentFilter = IntentFilter()
        intentFilter.addAction(action)
        registerReceiver(broadcastReceiver, intentFilter, BROADCAST_SENDER_PERMISSION, null)

        // Create a matching PendingIntent and use it to generate the IntentSender
        val broadcastIntent = Intent(action)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            packageName.hashCode(),
            broadcastIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT
                    or PendingIntent.FLAG_MUTABLE
        )
        return pendingIntent.intentSender
    }

    /**
     * Examine the extras to determine information about the package update/install, decode
     * the result, and call the appropriate callback.
     *
     * @param intent The intent, which the PackageInstaller will have added Extras to
     * @param packageName The package name we created the receiver for
     * @param sessionId The session Id we created the receiver for
     * @param callback The callback to report success/failure to
     */
    private fun handleCommitCallback(
        intent: Intent,
        packageName: String,
        sessionId: Int,
        callback: IPrivilegedCallback
    ) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(
                TAG, "Installation of $packageName finished with extras ${intent.extras}"
            )
        }
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_SUCCESS -> {
                cancelSession(sessionId, packageName)
                callback.handleResult(
                    packageName,
                    INSTALL_RETURN_CODES.getValue(status)
                )
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val activityIntent: Intent = intent.getParcelableExtra(Intent.EXTRA_INTENT)!!
                activityIntent.flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(activityIntent)
            }
            else -> {
                cancelSession(sessionId, packageName)
                Log.e(
                    TAG, "Error $status while installing $packageName: $statusMessage"
                )
                callback.handleResult(
                    packageName,
                    INSTALL_RETURN_CODES.getValue(status)
                )
            }
        }
    }

    private val binder = object : IPrivilegedService.Stub() {
        override fun hasPrivilegedPermissions() = mAccessProtectionHelper.isCallerAllowed()

        override fun installPackage(
            packageURI: Uri,
            flags: Int,
            installerPackageName: String,
            callback: IPrivilegedCallback
        ) {
            if (!mAccessProtectionHelper.isCallerAllowed()) {
                return
            }

            install(installerPackageName, packageURI, callback)
        }

        override fun deletePackage(
            packageName: String,
            flags: Int,
            callback: IPrivilegedCallback
        ) {
            if (!mAccessProtectionHelper.isCallerAllowed()) {
                return
            }

            uninstall(packageName, callback)
        }

        override fun getInstalledPackages(flags: Int): MutableList<PackageInfo> {
            return packageManager.getInstalledPackages(
                flags or getMatchStaticSharedLibraries()
            )
        }
    }

    private fun getMatchStaticSharedLibraries(): Int {
        try {
            //val field =
            //    PackageManager::class.java.getDeclaredField("MATCH_STATIC_SHARED_LIBRARIES")
            //return field[null] as Int
            return 0x04000000
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch static shared libraries flag: ${e.message}")
        }
        return 0
    }

    override fun onBind(intent: Intent?) = binder

    override fun onCreate() {
        super.onCreate()
        packageManager.packageInstaller.mySessions.forEach {
            val packageName = it.appPackageName
            val oldInfo = mSessionInfoMap.put(packageName, it)

            // Checking for old info is strictly for logging purposes
            if (oldInfo != null) {
                Log.w(
                    TAG,
                    "Multiple sessions for $packageName found. " +
                            "Removing ${oldInfo.sessionId} & keeping ${it.sessionId}"
                )
            }
        }
    }
}
