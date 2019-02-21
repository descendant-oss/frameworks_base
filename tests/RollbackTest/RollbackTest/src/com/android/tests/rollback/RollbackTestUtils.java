/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tests.rollback;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.support.test.InstrumentationRegistry;
import android.util.Log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Utilities to facilitate testing rollbacks.
 */
class RollbackTestUtils {

    private static final String TAG = "RollbackTest";

    static RollbackManager getRollbackManager() {
        Context context = InstrumentationRegistry.getContext();
        RollbackManager rm = (RollbackManager) context.getSystemService(Context.ROLLBACK_SERVICE);
        if (rm == null) {
            throw new AssertionError("Failed to get RollbackManager");
        }
        return rm;
    }

    /**
     * Returns the version of the given package installed on device.
     * Returns -1 if the package is not currently installed.
     */
    static long getInstalledVersion(String packageName) {
        Context context = InstrumentationRegistry.getContext();
        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo info = pm.getPackageInfo(packageName, PackageManager.MATCH_APEX);
            return info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private static void assertStatusSuccess(Intent result) {
        int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == -1) {
            throw new AssertionError("PENDING USER ACTION");
        } else if (status > 0) {
            String message = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            throw new AssertionError(message == null ? "UNKNOWN FAILURE" : message);
        }
    }

    /**
     * Uninstalls the given package.
     * Does nothing if the package is not installed.
     * @throws AssertionError if package can't be uninstalled.
     */
    static void uninstall(String packageName) throws InterruptedException, IOException {
        // No need to uninstall if the package isn't installed.
        if (getInstalledVersion(packageName) == -1) {
            return;
        }

        Context context = InstrumentationRegistry.getContext();
        PackageManager packageManager = context.getPackageManager();
        PackageInstaller packageInstaller = packageManager.getPackageInstaller();
        packageInstaller.uninstall(packageName, LocalIntentSender.getIntentSender());
        assertStatusSuccess(LocalIntentSender.getIntentSenderResult());
    }

    /**
     * Commit the given rollback.
     * @throws AssertionError if the rollback fails.
     */
    static void rollback(int rollbackId, VersionedPackage... causePackages)
            throws InterruptedException {
        RollbackManager rm = getRollbackManager();
        rm.commitRollback(rollbackId, Arrays.asList(causePackages),
                LocalIntentSender.getIntentSender());
        Intent result = LocalIntentSender.getIntentSenderResult();
        int status = result.getIntExtra(RollbackManager.EXTRA_STATUS,
                RollbackManager.STATUS_FAILURE);
        if (status != RollbackManager.STATUS_SUCCESS) {
            String message = result.getStringExtra(RollbackManager.EXTRA_STATUS_MESSAGE);
            throw new AssertionError(message);
        }
    }

    /**
     * Installs the apk with the given name.
     *
     * @param resourceName name of class loader resource for the apk to
     *        install.
     * @param enableRollback if rollback should be enabled.
     * @throws AssertionError if the installation fails.
     */
    static void install(String resourceName, boolean enableRollback)
            throws InterruptedException, IOException {
        Context context = InstrumentationRegistry.getContext();
        PackageInstaller.Session session = null;
        PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (enableRollback) {
            params.setEnableRollback();
        }
        int sessionId = packageInstaller.createSession(params);
        session = packageInstaller.openSession(sessionId);

        ClassLoader loader = RollbackTest.class.getClassLoader();
        try (OutputStream packageInSession = session.openWrite(resourceName, 0, -1);
             InputStream is = loader.getResourceAsStream(resourceName);) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = is.read(buffer)) >= 0) {
                packageInSession.write(buffer, 0, n);
            }
        }

        // Commit the session (this will start the installation workflow).
        session.commit(LocalIntentSender.getIntentSender());
        assertStatusSuccess(LocalIntentSender.getIntentSenderResult());
    }

    /** Launches {@code packageName} with {@link Intent#ACTION_MAIN}. */
    static void launchPackage(String packageName)
            throws InterruptedException, IOException {
        Context context = InstrumentationRegistry.getContext();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setPackage(packageName);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        context.startActivity(intent);
    }

    /**
     * Installs the APKs or APEXs with the given resource names as an atomic
     * set. A resource is assumed to be an APEX if it has the .apex extension.
     * <p>
     * In case of staged installs, this function will return succesfully after
     * the staged install has been committed and is ready for the device to
     * reboot.
     *
     * @param staged if the rollback should be staged.
     * @param enableRollback if rollback should be enabled.
     * @param resourceNames names of the class loader resource for the apks to
     *        install.
     * @throws AssertionError if the installation fails.
     */
    private static void install(boolean staged, boolean enableRollback,
            String... resourceNames) throws InterruptedException, IOException {
        Context context = InstrumentationRegistry.getContext();
        PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();

        PackageInstaller.SessionParams multiPackageParams = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        multiPackageParams.setMultiPackage();
        if (staged) {
            multiPackageParams.setStaged();
        }
        if (enableRollback) {
            // TODO: Do we set this on the parent params, the child params, or
            // both?
            multiPackageParams.setEnableRollback();
        }
        int multiPackageId = packageInstaller.createSession(multiPackageParams);
        PackageInstaller.Session multiPackage = packageInstaller.openSession(multiPackageId);

        for (String resourceName : resourceNames) {
            PackageInstaller.Session session = null;
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            if (staged) {
                params.setStaged();
            }
            if (resourceName.endsWith(".apex")) {
                params.setInstallAsApex();
            }
            if (enableRollback) {
                params.setEnableRollback();
            }
            int sessionId = packageInstaller.createSession(params);
            session = packageInstaller.openSession(sessionId);

            ClassLoader loader = RollbackTest.class.getClassLoader();
            try (OutputStream packageInSession = session.openWrite(resourceName, 0, -1);
                 InputStream is = loader.getResourceAsStream(resourceName);) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = is.read(buffer)) >= 0) {
                    packageInSession.write(buffer, 0, n);
                }
            }
            multiPackage.addChildSessionId(sessionId);
        }

        // Commit the session (this will start the installation workflow).
        multiPackage.commit(LocalIntentSender.getIntentSender());
        assertStatusSuccess(LocalIntentSender.getIntentSenderResult());

        if (staged) {
            waitForSessionReady(multiPackageId);
        }
    }

    /**
     * Installs the apks with the given resource names as an atomic set.
     *
     * @param enableRollback if rollback should be enabled.
     * @param resourceNames names of the class loader resource for the apks to
     *        install.
     * @throws AssertionError if the installation fails.
     */
    static void installMultiPackage(boolean enableRollback, String... resourceNames)
            throws InterruptedException, IOException {
        install(false, enableRollback, resourceNames);
    }

    /**
     * Installs the APKs or APEXs with the given resource names as a staged
     * atomic set. A resource is assumed to be an APEX if it has the .apex
     * extension.
     *
     * @param enableRollback if rollback should be enabled.
     * @param resourceNames names of the class loader resource for the apks to
     *        install.
     * @throws AssertionError if the installation fails.
     */
    static void installStaged(boolean enableRollback, String... resourceNames)
            throws InterruptedException, IOException {
        install(true, enableRollback, resourceNames);
    }

    static void adoptShellPermissionIdentity(String... permissions) {
        InstrumentationRegistry
            .getInstrumentation()
            .getUiAutomation()
            .adoptShellPermissionIdentity(permissions);
    }

    static void dropShellPermissionIdentity() {
        InstrumentationRegistry
            .getInstrumentation()
            .getUiAutomation()
            .dropShellPermissionIdentity();
    }

    /**
     * Returns the RollbackInfo with a given package in the list of rollbacks.
     * Throws an assertion failure if there is more than one such rollback
     * info. Returns null if there are no such rollback infos.
     */
    static RollbackInfo getUniqueRollbackInfoForPackage(List<RollbackInfo> rollbacks,
            String packageName) {
        RollbackInfo found = null;
        for (RollbackInfo rollback : rollbacks) {
            for (PackageRollbackInfo info : rollback.getPackages()) {
                if (packageName.equals(info.getPackageName())) {
                    assertNull(found);
                    found = rollback;
                    break;
                }
            }
        }
        return found;
    }

    /**
     * Asserts that the given PackageRollbackInfo has the expected package
     * name and versions.
     */
    static void assertPackageRollbackInfoEquals(String packageName,
            long versionRolledBackFrom, long versionRolledBackTo,
            PackageRollbackInfo info) {
        assertEquals(packageName, info.getPackageName());
        assertEquals(packageName, info.getVersionRolledBackFrom().getPackageName());
        assertEquals(versionRolledBackFrom, info.getVersionRolledBackFrom().getLongVersionCode());
        assertEquals(packageName, info.getVersionRolledBackTo().getPackageName());
        assertEquals(versionRolledBackTo, info.getVersionRolledBackTo().getLongVersionCode());
    }

    /**
     * Asserts that the given RollbackInfo has a single package with expected
     * package name and versions.
     */
    static void assertRollbackInfoEquals(String packageName,
            long versionRolledBackFrom, long versionRolledBackTo,
            RollbackInfo info, VersionedPackage... causePackages) {
        assertNotNull(info);
        assertEquals(1, info.getPackages().size());
        assertPackageRollbackInfoEquals(packageName, versionRolledBackFrom, versionRolledBackTo,
                info.getPackages().get(0));
        assertEquals(causePackages.length, info.getCausePackages().size());
        for (int i = 0; i < causePackages.length; ++i) {
            assertEquals(causePackages[i].getPackageName(),
                    info.getCausePackages().get(i).getPackageName());
            assertEquals(causePackages[i].getLongVersionCode(),
                    info.getCausePackages().get(i).getLongVersionCode());
        }
    }

    /**
     * Waits for the given session to be marked as ready.
     * Throws an assertion if the session fails.
     */
    static void waitForSessionReady(int sessionId) {
        BlockingQueue<PackageInstaller.SessionInfo> sessionStatus = new LinkedBlockingQueue<>();
        BroadcastReceiver sessionUpdatedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                PackageInstaller.SessionInfo info =
                        intent.getParcelableExtra(PackageInstaller.EXTRA_SESSION);
                if (info != null && info.getSessionId() == sessionId) {
                    if (info.isSessionReady() || info.isSessionFailed()) {
                        try {
                            sessionStatus.put(info);
                        } catch (InterruptedException e) {
                            Log.e(TAG, "Failed to put session info.", e);
                        }
                    }
                }
            }
        };
        IntentFilter sessionUpdatedFilter =
                new IntentFilter(PackageInstaller.ACTION_SESSION_UPDATED);

        Context context = InstrumentationRegistry.getContext();
        context.registerReceiver(sessionUpdatedReceiver, sessionUpdatedFilter);

        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionInfo info = installer.getSessionInfo(sessionId);

        try {
            if (info.isSessionReady() || info.isSessionFailed()) {
                sessionStatus.put(info);
            }

            info = sessionStatus.take();
            context.unregisterReceiver(sessionUpdatedReceiver);
            if (info.isSessionFailed()) {
                throw new AssertionError(info.getStagedSessionErrorMessage());
            }
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }
}
