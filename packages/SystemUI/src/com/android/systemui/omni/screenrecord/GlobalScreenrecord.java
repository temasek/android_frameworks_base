/*
 * Copyright (C) 2011 The Android Open Source Project
 * Modifications Copyright (C) The OmniROM Project
 * Modifications Copyright (C) crDroid Android
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
 *
 * Per article 5 of the Apache 2.0 License, some modifications to this code
 * were made by the OmniROM Project.
 *
 * Modifications Copyright (C) 2013 The OmniROM Project
 * Modifications Copyright (C) 2015 crDroid Android
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package com.android.systemui.omni.screenrecord;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Point;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import com.android.systemui.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

class GlobalScreenrecord {
    private static final String TAG = "GlobalScreenrecord";

    private static final int SCREENRECORD_NOTIFICATION_ID = 42;
    private static final int MSG_TASK_ENDED = 1;
    private static final int MSG_TASK_ERROR = 2;

    private static final String RECORDER_FOLDER = "ScreenRecorder";
    private static final String RECORDER_PATH =
            Environment.getExternalStorageDirectory().getAbsolutePath()
            + File.separator + RECORDER_FOLDER;

    private static final String TMP_PATH = RECORDER_PATH
            + File.separator + "__tmp_screenrecord.mp4";

    private Context mContext;
    private Handler mHandler;
    private NotificationManager mNotificationManager;
    private Resources mResources;

    private CaptureThread mCaptureThread;

    private class CaptureThread extends Thread {
        public void run() {
            final String size = getVideoDimensions();
            final String bit_rate = String.valueOf(Settings.System.getInt(
                    mContext.getContentResolver(),
                    Settings.System.SCREEN_RECORDER_BITRATE,
                    mResources.getInteger(R.integer.config_screenRecorderFramerate)));

            Runtime rt = Runtime.getRuntime();
            String[] cmds = new String[] {"/system/bin/screenrecord",
                    "--size", size, "--bit-rate", bit_rate, TMP_PATH};
            try {
                Process proc = rt.exec(cmds);
                BufferedReader br = new BufferedReader(new InputStreamReader(proc.getInputStream()));

                while (!isInterrupted()) {
                    if (br.ready()) {
                        String log = br.readLine();
                        Log.d(TAG, log);
                    }

                    try {
                        int code = proc.exitValue();

                        // If the recording is still running, we won't reach here,
                        // but will land in the catch block below.
                        Message msg = Message.obtain(mHandler, MSG_TASK_ENDED, code, 0, null);
                        mHandler.sendMessage(msg);

                        // No need to stop the process, so we can exit this method early
                        return;
                    } catch (IllegalThreadStateException ignore) {
                        // ignored
                    }
                }

                // Terminate the recording process
                // HACK: There is no way to send SIGINT to a process, so we... hack
                rt.exec(new String[]{"killall", "-2", "screenrecord"});
            } catch (IOException e) {
                // Notify something went wrong
                Message msg = Message.obtain(mHandler, MSG_TASK_ERROR);
                mHandler.sendMessage(msg);

                // Log the error as well
                Log.e(TAG, "Error while starting the screenrecord process", e);
            }
        }
    };


    /**
     * @param context everything needs a context :(
     */
    public GlobalScreenrecord(Context context) {
        mContext = context;
        mHandler = new Handler() {
            public void handleMessage(Message msg) {
                if (msg.what == MSG_TASK_ENDED) {
                    // The screenrecord process stopped, act as if user
                    // requested the record to stop.
                    stopScreenrecord();
                } else if (msg.what == MSG_TASK_ERROR) {
                    mCaptureThread = null;
                    // TODO: Notify the error
                }
            }
        };

        mNotificationManager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        mResources = mContext.getResources();
    }

    public boolean isRecording() {
        return (mCaptureThread != null);
    }

    /**
     * Starts recording the screen.
     */
    void takeScreenrecord() {
        if (mCaptureThread != null) {
            Log.e(TAG, "Capture Thread is already running, ignoring screenrecord start request");
            return;
        }

        mCaptureThread = new CaptureThread();
        mCaptureThread.start();

        updateNotification();
    }

    public void updateNotification(){
        // Display a notification
        Notification.Builder builder = new Notification.Builder(mContext)
            .setTicker(mResources.getString(R.string.screenrecord_notif_ticker))
            .setContentTitle(mResources.getString(R.string.screenrecord_notif_title))
            .setSmallIcon(R.drawable.ic_capture_video)
            .setWhen(System.currentTimeMillis())
            .setOngoing(true);

        Intent stopIntent = new Intent(mContext, TakeScreenrecordService.class)
            .setAction(TakeScreenrecordService.ACTION_STOP);
        PendingIntent stopPendIntent = PendingIntent.getService(mContext, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT);

        Intent pointerIntent = new Intent(mContext, TakeScreenrecordService.class)
            .setAction(TakeScreenrecordService.ACTION_TOGGLE_POINTER);
        PendingIntent pointerPendIntent = PendingIntent.getService(mContext, 0, pointerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT);

        boolean showTouches = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SHOW_TOUCHES, 0, UserHandle.USER_CURRENT) != 0;
        int togglePointerIconId = showTouches ?
                R.drawable.ic_pointer_off :
                R.drawable.ic_pointer_on;
        int togglePointerStringId = showTouches ?
                R.string.screenrecord_notif_pointer_off :
                R.string.screenrecord_notif_pointer_on;
        builder
            .addAction(com.android.internal.R.drawable.ic_media_stop,
                mResources.getString(R.string.screenrecord_notif_stop), stopPendIntent)
            .addAction(togglePointerIconId,
                mResources.getString(togglePointerStringId), pointerPendIntent);

        Notification notif = builder.build();
        mNotificationManager.notify(SCREENRECORD_NOTIFICATION_ID, notif);
    }

    /**
     * Stops recording the screen.
     */
    void stopScreenrecord() {
        if (mCaptureThread == null) {
            Log.e(TAG, "No capture thread, cannot stop screen recording!");
            return;
        }

        mNotificationManager.cancel(SCREENRECORD_NOTIFICATION_ID);

        try {
            mCaptureThread.interrupt();
        } catch (Exception e) { /* ignore */ }

        // Wait a bit and copy the output file to a safe place
        while (mCaptureThread.isAlive()) {
            // wait...
        }

        // Give a second to screenrecord to finish the file
        mHandler.postDelayed(new Runnable() { public void run() {
            mCaptureThread = null;

            String fileName = "SCR_" + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".mp4";
            File path = new File(RECORDER_PATH);

            if (!path.exists()) {
                if (!path.mkdir()) {
                    Log.e(TAG, "Cannot create output directory " + RECORDER_PATH);
                    return;
                }
            }

            File input = new File(TMP_PATH);
            final File output = new File(path, fileName);

            Log.d(TAG, "Copying file to " + output.getAbsolutePath());

            try {
                copyFileUsingStream(input, output);
                input.delete();
            } catch (IOException e) {
                Log.e(TAG, "Unable to copy output file", e);
                Message msg = Message.obtain(mHandler, MSG_TASK_ERROR);
                mHandler.sendMessage(msg);
            }

            // Make it appear in gallery, run MediaScanner
            MediaScannerConnection.scanFile(mContext,
                new String[] { output.getAbsolutePath() }, null,
                new MediaScannerConnection.OnScanCompletedListener() {
                public void onScanCompleted(String path, Uri uri) {
                    Log.i(TAG, "MediaScanner done scanning " + path);
                }
            });
        } }, 2000);
    }


    private static void copyFileUsingStream(File source, File dest) throws IOException {
        InputStream is = null;
        OutputStream os = null;
        try {
            is = new FileInputStream(source);
            os = new FileOutputStream(dest);
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
        } finally {
            is.close();
            os.close();
        }
    }

    private String getVideoDimensions() {
        WindowManager wm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        final Display display = wm.getDefaultDisplay();
        String dimensionString = Settings.System.getString(mContext.getContentResolver(),
                Settings.System.SCREEN_RECORDER_OUTPUT_DIMENSIONS);
        if (TextUtils.isEmpty(dimensionString)) {
            dimensionString = mResources.getString(R.string.config_screenRecorderOutputDimensions);
        }
        int[] dimensions = parseDimensions(dimensionString);
        if (dimensions == null) {
            dimensions = new int[] {720, 1280};
        }

        // if rotation is Surface.ROTATION_90,270 and width>height swap
        final Point p = new Point();
        display.getRealSize(p);
        if ((display.getRotation() == Surface.ROTATION_90 || display.getRotation() == Surface.ROTATION_270) && (p.x > p.y)) {
            int tmp = dimensions[0];
            dimensions[0] = dimensions[1];
            dimensions[1] = tmp;
        }

        return dimensions[0] + "x" + dimensions[1];
    }

    private static int[] parseDimensions(String dimensions) {
        String[] tmp = dimensions.split("x");
        if (tmp.length < 2) return null;
        int[] dims = new int[2];
        try {
            dims[0] = Integer.valueOf(tmp[0]);
            dims[1] = Integer.valueOf(tmp[1]);
        } catch (NumberFormatException e) {
            return null;
        }

        return dims;
    }
}
