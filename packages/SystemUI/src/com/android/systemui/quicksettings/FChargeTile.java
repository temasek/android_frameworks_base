/*
 * Copyright (C) 2012 Slimroms Project
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

package com.android.systemui.quicksettings;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsController;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import android.os.FileObserver;

public class FChargeTile extends QuickSettingsTile {

    private final boolean DBG = false;

    protected boolean mEnabled = false;
    private String mFchargePath;
    private FileObserver mObserver;
    final Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
                mEnabled = isFastChargeOn();
                if (DBG) Log.e("FChargeToggle", "Fast charge file modified to " +
                                (mEnabled ? "1" : "0"));
                updateTileState();
                updateQuickSettings();
        }
    };

    public FChargeTile(Context context, final QuickSettingsController qsc) {
        super(context, qsc);
        mFchargePath = mContext.getResources()
                                .getString(com.android.internal.R.string.config_fastChargePath);
        mEnabled = isFastChargeOn();
        mObserver = new FileObserver(mFchargePath, FileObserver.MODIFY) {
            @Override
            public void onEvent(int event, String file) {
                if (DBG) Log.e("FChargeToggle", "Fast charge file modified "+event);
                mHandler.sendMessage(mHandler.obtainMessage());
            }
        };
        mObserver.startWatching();
        mOnClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                        mEnabled = !mEnabled;
                        if (DBG) Log.e("FChargeToggle", "switching to fast charge: "+
                                (mEnabled ? "1" : "0"));
                        FileWriter fwriter = new FileWriter(mFchargePath);
                        BufferedWriter bwriter = new BufferedWriter(fwriter);
                        bwriter.write(mEnabled ? "1" : "0");
                        bwriter.close();
                    } catch (IOException e) {
                        Log.e("FChargeToggle", "Couldn't write fast_charge file");
                    }
            }
        };
    }

    @Override
    void onPostCreate() {
        updateTileState();
        updateQuickSettings();
    }

    public boolean isFastChargeOn() {
        try {
            FileReader reader = new FileReader(mFchargePath);
            BufferedReader breader = new BufferedReader(reader);
            String line = breader.readLine();
            breader.close();
            return (line.equals("1"));
        } catch (IOException e) {
            Log.e("FChargeToggle", "Couldn't read fast_charge file");
        }
        return false;
    }

    private void updateTileState() {
        String label = mContext.getString(R.string.quick_settings_fcharge);
        if(mEnabled) {
            mDrawable = R.drawable.ic_qs_fcharge_on;
            mLabel = label;
        } else {
            mDrawable = R.drawable.ic_qs_fcharge_off;
            mLabel = label + " " + mContext.getString(R.string.quick_settings_label_disabled);
        }
    }

    @Override
    public void onDestroy(){
        if (mObserver != null) {
            mObserver.stopWatching();
        }
    }
}
