/*
 * Copyright (C) 2016 The Pure Nexus Project
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

package com.android.internal.util;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class DevUtils {
    private static final String TAG = DevUtils.class.getSimpleName();

    /**
     * Kills the top most / most recent user application, but leaves out the launcher.
     * This is function governed by {@link Settings.Secure.KILL_APP_LONGPRESS_BACK}.
     *
     * @param context the current context, used to retrieve the package manager.
     * @param userId the ID of the currently active user
     * @return {@code true} when a user application was found and closed.
     */
    public static String killForegroundApp(Context context, int userId) {
        try {
            return killForegroundAppInternal(context, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not kill foreground app");
        }
        return null;
    }

    private static String killForegroundAppInternal(Context context, int userId)
            throws RemoteException {
        try {
            PackageManager packageManager = context.getPackageManager();
            final Intent intent = new Intent(Intent.ACTION_MAIN);
            String defaultHomePackage = "com.android.launcher";
            intent.addCategory(Intent.CATEGORY_HOME);
            final ResolveInfo res = packageManager.resolveActivity(intent, 0);

            if (res.activityInfo != null && !res.activityInfo.packageName.equals("android")) {
                defaultHomePackage = res.activityInfo.packageName;
            }
            // Use UsageStats to determine foreground app
            UsageStatsManager usageStatsManager = (UsageStatsManager)
                context.getSystemService(Context.USAGE_STATS_SERVICE);
            long current = System.currentTimeMillis();
            long past = current - (1000 * 60 * 60); // uses snapshot of usage over past 60 minutes

            // Get the list, then sort it chronilogically so most recent usage is at start of list
            List<UsageStats> recentApps = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, past, current);
            Collections.sort(recentApps, new Comparator<UsageStats>() {
                @Override
                public int compare(UsageStats lhs, UsageStats rhs) {
                    long timeLHS = lhs.getLastTimeUsed();
                    long timeRHS = rhs.getLastTimeUsed();
                    if (timeLHS > timeRHS) {
                        return -1;
                    } else if (timeLHS < timeRHS) {
                        return 1;
                    }
                    return 0;
                }
            });

            IActivityManager am = ActivityManagerNative.getDefault();
            if (am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) return null;
            // Look for most recent usagestat with lastevent == 1 and grab package name
            // ...this seems to map to the UsageEvents.Event.MOVE_TO_FOREGROUND
            String pkg = null;
            for (int i = 0; i < recentApps.size(); i++) {
                UsageStats mostRecent = recentApps.get(i);
                if (mostRecent.mLastEvent == 1) {
                    pkg = mostRecent.mPackageName;
                    break;
                }
            }

            if (pkg != null && !pkg.equals("com.android.systemui")
                    && !pkg.equals(defaultHomePackage)) {
                am.forceStopPackage(pkg, UserHandle.USER_CURRENT);
                try {
                    String appName = (String) packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA));
                    return appName;
                } catch (PackageManager.NameNotFoundException e) {
                    // Just use pkg if issues getting appName
                    return pkg;
                }
            } else {
                return null;
            }
        } catch (RemoteException remoteException) {
            // Do nothing; just let it go.
        }
        return null;
    }
}
