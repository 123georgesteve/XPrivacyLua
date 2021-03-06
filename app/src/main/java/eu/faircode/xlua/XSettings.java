/*
    This file is part of XPrivacy/Lua.

    XPrivacy/Lua is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    XPrivacy/Lua is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with XPrivacy/Lua.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2017-2018 Marcel Bokhorst (M66B)
 */

package eu.faircode.xlua;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Parcelable;
import android.os.Process;
import android.os.StrictMode;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import de.robv.android.xposed.XposedBridge;

class XSettings {
    private final static String TAG = "XLua.Settings";

    private final static Object lock = new Object();

    private static int version = -1;
    private static Map<String, XHook> hooks = null;
    private static SQLiteDatabase db = null;
    private static ReentrantReadWriteLock dbLock = new ReentrantReadWriteLock(true);

    final static String cChannelName = "xlua";

    static Uri URI = Uri.parse("content://settings/system");
    static String ACTION_DATA_CHANGED = XSettings.class.getPackage().getName() + ".DATA_CHANGED";

    static Bundle call(Context context, String arg, Bundle extras) throws Throwable {
        Log.i(TAG, "Call " + arg + " uid=" + Process.myUid() + " cuid=" + Binder.getCallingUid());

        synchronized (lock) {
            if (version < 0)
                version = getVersion(context);
            if (hooks == null)
                hooks = getHooks(context);
            if (db == null)
                db = getDatabase();
        }

        StrictMode.ThreadPolicy originalPolicy = StrictMode.getThreadPolicy();
        try {
            StrictMode.allowThreadDiskReads();
            StrictMode.allowThreadDiskWrites();
            switch (arg) {
                case "getVersion":
                    return getVersion(context, extras);
                case "putHooks":
                    return putHooks(context, extras);
                case "getHooks":
                    return getHooks(context, extras);
                case "getApps":
                    return getApps(context, extras);
                case "assignHooks":
                    return assignHooks(context, extras);
                case "getAssignedHooks":
                    return getAssignedHooks(context, extras);
                case "report":
                    return report(context, extras);
                case "getSetting":
                    return getSetting(context, extras);
                case "putSetting":
                    return putSetting(context, extras);
                case "clearData":
                    return clearData(context, extras);
                default:
                    return null;
            }
        } finally {
            StrictMode.setThreadPolicy(originalPolicy);
        }
    }

    private static Bundle getVersion(Context context, Bundle extras) throws Throwable {
        Bundle result = new Bundle();
        result.putInt("version", version);
        return result;
    }

    private static Bundle putHooks(Context context, Bundle extras) throws Throwable {
        enforcePermission(context);

        extras.setClassLoader(XSettings.class.getClassLoader());
        ArrayList<XHook> put = extras.getParcelableArrayList("hooks");

        synchronized (lock) {
            hooks.clear();
            for (XHook hook : put)
                hooks.put(hook.getId(), hook);
        }

        Log.i(TAG, "Set hooks=" + hooks.size());

        return new Bundle();
    }

    private static Bundle getHooks(Context context, Bundle extras) throws Throwable {
        Bundle result = new Bundle();

        synchronized (lock) {
            result.putParcelableArrayList("hooks", new ArrayList<Parcelable>(hooks.values()));
        }

        return result;
    }

    private static Bundle getApps(Context context, Bundle extras) throws Throwable {
        Map<String, XApp> apps = new HashMap<>();

        int cuid = Binder.getCallingUid();
        int userid = Util.getUserId(cuid);

        // Access package manager as system user
        long ident = Binder.clearCallingIdentity();
        try {
            // Get installed apps for current user
            PackageManager pm = Util.createContextForUser(context, userid).getPackageManager();
            for (ApplicationInfo ai : pm.getInstalledApplications(0))
                if (!"android".equals(ai.packageName)) {
                    int esetting = pm.getApplicationEnabledSetting(ai.packageName);
                    boolean enabled = (ai.enabled &&
                            (esetting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
                                    esetting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED));
                    boolean persistent = ((ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0 ||
                            "android".equals(ai.packageName));

                    XApp app = new XApp();
                    app.uid = ai.uid;
                    app.packageName = ai.packageName;
                    app.icon = ai.icon;
                    app.label = (String) pm.getApplicationLabel(ai);
                    app.enabled = enabled;
                    app.persistent = persistent;
                    app.assignments = new ArrayList<>();
                    apps.put(app.packageName + ":" + app.uid, app);
                }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        Log.i(TAG, "Installed apps=" + apps.size() + " cuid=" + cuid);

        // Get assigned hooks
        dbLock.readLock().lock();
        try {
            db.beginTransaction();
            try {
                Cursor cursor = null;
                try {
                    int start = Util.getUserUid(userid, 0);
                    int end = Util.getUserUid(userid, Process.LAST_APPLICATION_UID);
                    cursor = db.query(
                            "assignment",
                            new String[]{"package", "uid", "hook", "installed", "used", "restricted", "exception"},
                            "uid >= ? AND uid <= ?",
                            new String[]{Integer.toString(start), Integer.toString(end)},
                            null, null, null);
                    int colPkg = cursor.getColumnIndex("package");
                    int colUid = cursor.getColumnIndex("uid");
                    int colHook = cursor.getColumnIndex("hook");
                    int colInstalled = cursor.getColumnIndex("installed");
                    int colUsed = cursor.getColumnIndex("used");
                    int colRestricted = cursor.getColumnIndex("restricted");
                    int colException = cursor.getColumnIndex("exception");
                    while (cursor.moveToNext()) {
                        String pkg = cursor.getString(colPkg);
                        int uid = cursor.getInt(colUid);
                        String hookid = cursor.getString(colHook);
                        if (apps.containsKey(pkg + ":" + uid)) {
                            XApp app = apps.get(pkg + ":" + uid);
                            synchronized (lock) {
                                if (hooks.containsKey(hookid)) {
                                    XAssignment assignment = new XAssignment(hooks.get(hookid));
                                    assignment.installed = cursor.getLong(colInstalled);
                                    assignment.used = cursor.getLong(colUsed);
                                    assignment.restricted = (cursor.getInt(colRestricted) == 1);
                                    assignment.exception = cursor.getString(colException);
                                    app.assignments.add(assignment);
                                } else
                                    Log.w(TAG, "Hook " + hookid + " not found");
                            }
                        } else
                            Log.i(TAG, "Package " + pkg + ":" + uid + " not found");
                    }
                } finally {
                    if (cursor != null)
                        cursor.close();
                }

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            dbLock.readLock().unlock();
        }

        Bundle result = new Bundle();
        result.putParcelableArrayList("apps", new ArrayList(apps.values()));
        return result;
    }

    private static Bundle assignHooks(Context context, Bundle extras) throws Throwable {
        enforcePermission(context);

        List<String> hookids = extras.getStringArrayList("hooks");
        String packageName = extras.getString("packageName");
        int uid = extras.getInt("uid");
        boolean delete = extras.getBoolean("delete");
        boolean kill = extras.getBoolean("kill");

        dbLock.writeLock().lock();
        try {
            db.beginTransaction();
            try {
                for (String hookid : hookids)
                    if (delete) {
                        Log.i(TAG, packageName + ":" + uid + "/" + hookid + " deleted");
                        long rows = db.delete("assignment",
                                "hook = ? AND package = ? AND uid = ?",
                                new String[]{hookid, packageName, Integer.toString(uid)});
                        if (rows < 0)
                            throw new Throwable("Error deleting assignment");
                    } else {
                        Log.i(TAG, packageName + ":" + uid + "/" + hookid + " added");
                        ContentValues cv = new ContentValues();
                        cv.put("package", packageName);
                        cv.put("uid", uid);
                        cv.put("hook", hookid);
                        cv.put("installed", -1);
                        cv.put("used", -1);
                        cv.put("restricted", 0);
                        cv.putNull("exception");
                        long rows = db.insertWithOnConflict("assignment", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                        if (rows < 0)
                            throw new Throwable("Error inserting assignment");
                    }

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            dbLock.writeLock().unlock();
        }

        if (kill) {
            // Access activity manager as system user
            long ident = Binder.clearCallingIdentity();
            try {
                // public void forceStopPackageAsUser(String packageName, int userId)
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                Method mForceStop = am.getClass().getMethod("forceStopPackageAsUser", String.class, int.class);
                mForceStop.invoke(am, packageName, Util.getUserId(uid));
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        return new Bundle();
    }

    private static Bundle getAssignedHooks(Context context, Bundle extras) throws Throwable {
        ArrayList<XHook> assigned = new ArrayList<>();

        String packageName = extras.getString("packageName");
        int uid = extras.getInt("uid");

        dbLock.readLock().lock();
        try {
            db.beginTransaction();
            try {
                Cursor cursor = null;
                try {
                    cursor = db.query(
                            "assignment",
                            new String[]{"hook"},
                            "package = ? AND uid = ?",
                            new String[]{packageName, Integer.toString(uid)},
                            null, null, null);
                    int colHook = cursor.getColumnIndex("hook");
                    while (cursor.moveToNext()) {
                        String hookid = cursor.getString(colHook);
                        synchronized (lock) {
                            if (hooks.containsKey(hookid)) {
                                XHook hook = hooks.get(hookid);
                                if ("android.content.ContentResolver".equals(hook.getClassName())) {
                                    String className = context.getContentResolver().getClass().getName();
                                    hook.setClassName(className);
                                    Log.i(TAG, hook.getId() + " class name=" + className);
                                }
                                assigned.add(hook);
                            } else
                                Log.w(TAG, "Hook " + hookid + " not found");
                        }
                    }
                } finally {
                    if (cursor != null)
                        cursor.close();
                }

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            dbLock.readLock().unlock();
        }

        Bundle result = new Bundle();
        result.putParcelableArrayList("hooks", assigned);
        return result;
    }

    @SuppressLint("MissingPermission")
    private static Bundle report(Context context, Bundle extras) throws Throwable {
        String hook = extras.getString("hook");
        String packageName = extras.getString("packageName");
        int uid = extras.getInt("uid");
        String event = extras.getString("event");
        Bundle data = extras.getBundle("data");

        if (uid != Binder.getCallingUid())
            throw new SecurityException();

        Log.i(TAG, "Hook " + hook + " pkg=" + packageName + ":" + uid + " event=" + event);
        for (String key : data.keySet())
            Log.i(TAG, key + "=" + data.get(key));

        // Store event
        dbLock.writeLock().lock();
        try {
            db.beginTransaction();
            try {
                ContentValues cv = new ContentValues();
                if ("install".equals(event))
                    cv.put("installed", new Date().getTime());
                else if ("use".equals(event)) {
                    cv.put("used", new Date().getTime());
                    if (data.containsKey("restricted"))
                        cv.put("restricted", data.getInt("restricted"));
                }
                if (data.containsKey("exception"))
                    cv.put("exception", data.getString("exception"));

                long rows = db.update("assignment", cv,
                        "package = ? AND uid = ? AND hook = ?",
                        new String[]{packageName, Integer.toString(uid), hook});
                if (rows < 1)
                    Log.i(TAG, packageName + ":" + uid + "/" + hook + " not updated");

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            dbLock.writeLock().unlock();
        }

        long ident = Binder.clearCallingIdentity();
        try {
            // Notify data changed
            // TODO: batch
            Intent intent = new Intent();
            intent.setAction(ACTION_DATA_CHANGED);
            intent.setPackage(XSettings.class.getPackage().getName());
            intent.putExtra("packageName", packageName);
            intent.putExtra("uid", uid);
            context.sendBroadcastAsUser(intent, Util.getUserHandle(uid));

            // Notify exception
            if (data.containsKey("exception")) {
                Context ctx = Util.createContextForUser(context, Util.getUserId(uid));
                PackageManager pm = ctx.getPackageManager();
                String self = XSettings.class.getPackage().getName();
                Resources resources = pm.getResourcesForApplication(self);

                Notification.Builder builder = new Notification.Builder(ctx);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    builder.setChannelId(cChannelName);
                builder.setSmallIcon(android.R.drawable.ic_dialog_alert);
                builder.setContentTitle(resources.getString(R.string.msg_exception, hook));
                builder.setContentText(pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)));

                builder.setPriority(Notification.PRIORITY_HIGH);
                builder.setCategory(Notification.CATEGORY_STATUS);
                builder.setVisibility(Notification.VISIBILITY_SECRET);

                // Main
                Intent main = ctx.getPackageManager().getLaunchIntentForPackage(self);
                main.putExtra(ActivityMain.EXTRA_SEARCH_PACKAGE, packageName);
                PendingIntent pi = PendingIntent.getActivity(ctx, uid, main, 0);
                builder.setContentIntent(pi);

                builder.setAutoCancel(true);

                Util.notifyAsUser(ctx, "xlua_exception", uid, builder.build(), Util.getUserId(uid));
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        return new Bundle();
    }

    private static Bundle getSetting(Context context, Bundle extras) throws Throwable {
        int userid = extras.getInt("user");
        String category = extras.getString("category");
        String name = extras.getString("name");

        String value = null;
        dbLock.readLock().lock();
        try {
            db.beginTransaction();
            try {
                Cursor cursor = null;
                try {
                    cursor = db.query("setting", new String[]{"value"},
                            "user = ? AND category = ? AND name = ?",
                            new String[]{Integer.toString(userid), category, name},
                            null, null, null);
                    if (cursor.moveToNext())
                        value = (cursor.isNull(0) ? null : cursor.getString(0));
                } finally {
                    if (cursor != null)
                        cursor.close();
                }

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            dbLock.readLock().unlock();
        }

        Log.i(TAG, "Get setting " + userid + ":" + category + ":" + name + "=" + value);
        Bundle result = new Bundle();
        result.putString("value", value);
        return result;
    }

    private static Bundle putSetting(Context context, Bundle extras) throws Throwable {
        enforcePermission(context);

        int userid = extras.getInt("user");
        String category = extras.getString("category");
        String name = extras.getString("name");
        String value = extras.getString("value");
        Log.i(TAG, "Put setting  " + userid + ":" + category + ":" + name + "=" + value);

        dbLock.writeLock().lock();
        try {
            db.beginTransaction();
            try {
                if (value == null) {
                    db.delete(
                            "setting",
                            "user = ? AND category = ? AND name = ?",
                            new String[]{Integer.toString(userid), category, name});
                } else {
                    ContentValues cv = new ContentValues();
                    cv.put("user", userid);
                    cv.put("category", category);
                    cv.put("name", name);
                    cv.put("value", value);
                    db.insertWithOnConflict("setting", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                }

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            dbLock.writeLock().unlock();
        }

        return new Bundle();
    }

    private static Bundle clearData(Context context, Bundle extras) throws Throwable {
        enforcePermission(context);

        int userid = extras.getInt("user");
        Log.i(TAG, "Clearing data user=" + userid);

        dbLock.writeLock().lock();
        try {
            db.beginTransaction();
            try {
                if (userid == 0) {
                    db.delete("assignment", null, null);
                    db.delete("setting", null, null);
                } else {
                    int start = Util.getUserUid(userid, 0);
                    int end = Util.getUserUid(userid, Process.LAST_APPLICATION_UID);
                    db.delete(
                            "assignment",
                            "uid >= ? AND uid <= ?",
                            new String[]{Integer.toString(start), Integer.toString(end)});
                    db.delete(
                            "setting",
                            "user = ?",
                            new String[]{Integer.toString(userid)});
                }

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            dbLock.writeLock().unlock();
        }

        return new Bundle();
    }

    private static void enforcePermission(Context context) throws SecurityException {
        // Access package manager as system user
        long ident = Binder.clearCallingIdentity();
        try {
            int cuid = Util.getAppId(Binder.getCallingUid());
            if (cuid == Process.SYSTEM_UID)
                return;
            String self = XSettings.class.getPackage().getName();
            int puid = context.getPackageManager().getApplicationInfo(self, 0).uid;
            if (cuid != puid)
                throw new SecurityException("Calling uid " + cuid + " <> package uid " + puid);
        } catch (Throwable ex) {
            throw new SecurityException("Error determining package uid", ex);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private static int getVersion(Context context) throws Throwable {
        String self = XSettings.class.getPackage().getName();
        PackageInfo pi = context.getPackageManager().getPackageInfo(self, 0);
        Log.i(TAG, "Loaded module version " + pi.versionCode);
        return pi.versionCode;
    }

    private static Map<String, XHook> getHooks(Context context) throws Throwable {
        Map<String, XHook> result = new HashMap<>();
        PackageManager pm = context.getPackageManager();
        String self = XSettings.class.getPackage().getName();
        ApplicationInfo ai = pm.getApplicationInfo(self, 0);
        for (XHook hook : XHook.readHooks(ai.publicSourceDir))
            result.put(hook.getId(), hook);
        Log.i(TAG, "Loaded hooks=" + result.size());
        return result;
    }

    private static SQLiteDatabase getDatabase() {
        // Build database file
        File dbFile = new File(
                Environment.getDataDirectory() + File.separator +
                        "system" + File.separator +
                        "xlua" + File.separator +
                        "xlua.db");
        dbFile.getParentFile().mkdirs();

        // Open database
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbFile, null);
        Log.i(TAG, "Database version=" + db.getVersion() + " file=" + dbFile);

        // Set database file permissions
        // Owner: rwx (system)
        // Group: rwx (system)
        // World: ---
        Util.setPermissions(dbFile.getParentFile().getAbsolutePath(), 0770, Process.SYSTEM_UID, Process.SYSTEM_UID);
        File[] files = dbFile.getParentFile().listFiles();
        if (files != null)
            for (File file : files)
                Util.setPermissions(file.getAbsolutePath(), 0770, Process.SYSTEM_UID, Process.SYSTEM_UID);

        dbLock.writeLock().lock();
        try {
            // Upgrade database if needed
            if (db.needUpgrade(1)) {
                db.beginTransaction();
                try {
                    // http://www.sqlite.org/lang_createtable.html
                    db.execSQL("CREATE TABLE assignment (package TEXT NOT NULL, uid INTEGER NOT NULL, hook TEXT NOT NULL, installed INTEGER, used INTEGER, restricted INTEGER, exception TEXT)");
                    db.execSQL("CREATE UNIQUE INDEX idx_assignment ON assignment(package, uid, hook)");

                    db.execSQL("CREATE TABLE setting (user INTEGER, category TEXT NOT NULL, name TEXT NOT NULL, value TEXT)");
                    db.execSQL("CREATE UNIQUE INDEX idx_setting ON setting(user, category, name)");

                    db.setVersion(1);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }

            // Reset usage data
            ContentValues cv = new ContentValues();
            cv.put("installed", -1);
            cv.putNull("exception");
            long rows = db.update("assignment", cv, null, null);
            Log.i(TAG, "Reset assigned hook data count=" + rows);

            return db;
        } finally {
            dbLock.writeLock().unlock();
        }
    }

    static boolean isAvailable(Context context) {
        try {
            String self = XSettings.class.getPackage().getName();
            PackageInfo pi = context.getPackageManager().getPackageInfo(self, 0);
            Bundle result = context.getContentResolver()
                    .call(XSettings.URI, "xlua", "getVersion", new Bundle());
            return (result != null && pi.versionCode == result.getInt("version"));
        } catch (Throwable ex) {
            Log.e(TAG, Log.getStackTraceString(ex));
            XposedBridge.log(ex);
            return false;
        }
    }

    static boolean getSettingBoolean(Context context, String category, String name) {
        return getSettingBoolean(context, Util.getUserId(Process.myUid()), category, name);
    }

    static boolean getSettingBoolean(Context context, int user, String category, String name) {
        Bundle args = new Bundle();
        args.putInt("user", user);
        args.putString("category", category);
        args.putString("name", name);
        Bundle result = context.getContentResolver()
                .call(XSettings.URI, "xlua", "getSetting", args);
        return Boolean.parseBoolean(result.getString("value"));
    }

    static void putSettingBoolean(Context context, String category, String name, boolean value) {
        Bundle args = new Bundle();
        args.putInt("user", Util.getUserId(Process.myUid()));
        args.putString("category", category);
        args.putString("name", name);
        args.putString("value", Boolean.toString(value));
        context.getContentResolver()
                .call(XSettings.URI, "xlua", "putSetting", args);
    }
}
