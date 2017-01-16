/*
 * Copyright (C) 2015 Peter Gregus for GravityBox Project (C3C076@xda)
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

package com.ceco.marshmallow.gravitybox;

import com.ceco.marshmallow.gravitybox.R;
import com.ceco.marshmallow.gravitybox.ModStatusBar.ContainerType;

import android.content.Intent;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.LinearLayout;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class BatteryStyleController implements BroadcastSubReceiver {
    private static final String TAG = "GB:BatteryStyleController";
    public static final String PACKAGE_NAME = "com.android.systemui";
    public static final String CLASS_BATTERY_CONTROLLER = 
            "com.android.systemui.statusbar.policy.BatteryController";
    private static final boolean DEBUG = false;

    public static final String ACTION_MTK_BATTERY_PERCENTAGE_SWITCH = 
            "mediatek.intent.action.BATTERY_PERCENTAGE_SWITCH";
    public static final String EXTRA_MTK_BATTERY_PERCENTAGE_STATE = "state";
    public static final String SETTING_MTK_BATTERY_PERCENTAGE = "battery_percentage";

    private enum KeyguardMode { DEFAULT, ALWAYS_SHOW, HIDDEN };

    private ContainerType mContainerType;
    private ViewGroup mContainer;
    private ViewGroup mSystemIcons;
    private Context mContext;
    private XSharedPreferences mPrefs;
    private int mBatteryStyle;
    private boolean mBatteryPercentTextEnabledSb;
    private boolean mBatteryPercentTextHeaderHide;
    private KeyguardMode mBatteryPercentTextKgMode;
    private boolean mMtkPercentTextEnabled;
    private StatusbarBatteryPercentage mPercentText;
    private CmCircleBattery mCircleBattery;
    private StatusbarBattery mStockBattery;
    private boolean mBatterySaverIndicationDisabled;

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    public BatteryStyleController(ContainerType containerType, ViewGroup container, XSharedPreferences prefs) throws Throwable {
        mContainerType = containerType;
        mContainer = container;
        mContext = container.getContext();
        mSystemIcons = (ViewGroup) mContainer.findViewById(
                mContext.getResources().getIdentifier("system_icons", "id", PACKAGE_NAME));

        if (mSystemIcons != null) {
            initPreferences(prefs);
            initLayout();
            createHooks();
            updateBatteryStyle();
        }
    }

    private void initPreferences(XSharedPreferences prefs) {
        mPrefs = prefs;
        mBatteryStyle = Integer.valueOf(prefs.getString(
                GravityBoxSettings.PREF_KEY_BATTERY_STYLE, "1"));
        mBatteryPercentTextEnabledSb = prefs.getBoolean(
                GravityBoxSettings.PREF_KEY_BATTERY_PERCENT_TEXT_STATUSBAR, false);
        mBatteryPercentTextHeaderHide = prefs.getBoolean(
                GravityBoxSettings.PREF_KEY_BATTERY_PERCENT_TEXT_HEADER_HIDE, false);
        mBatteryPercentTextKgMode = KeyguardMode.valueOf(prefs.getString(
                GravityBoxSettings.PREF_KEY_BATTERY_PERCENT_TEXT_KEYGUARD, "DEFAULT"));
        mMtkPercentTextEnabled = Utils.isMtkDevice() ?
                Settings.Secure.getInt(mContext.getContentResolver(), 
                        SETTING_MTK_BATTERY_PERCENTAGE, 0) == 1 : false;
        mBatterySaverIndicationDisabled = prefs.getBoolean(
                GravityBoxSettings.PREF_KEY_BATTERY_SAVER_INDICATION_DISABLE, false);
    }

    private void initLayout() throws Throwable {
        final String[] batteryPercentTextIds = new String[] { "battery_level", "percentage", "battery_text" };
        Resources res = mContext.getResources();
        Resources gbRes = Utils.getGbContext(mContext).getResources();
        
        if (!Utils.hasLenovoCustomUI()) {
            // inject percent text if it doesn't exist
            for (String bptId : batteryPercentTextIds) {
                final int bptResId = res.getIdentifier(bptId, "id", PACKAGE_NAME);
                if (bptResId != 0) {
                    View v = mContainer.findViewById(bptResId);
                    if (v != null && v instanceof TextView) {
                        mPercentText = new StatusbarBatteryPercentage((TextView) v, mPrefs, this);
                        if (DEBUG) log("Battery percent text found as: " + bptId);
                        break;
                    }
                }
            }
            if (mPercentText == null || Utils.isOxygenOs35Rom()) {
                TextView percentTextView = new TextView(mContext);
                LinearLayout.LayoutParams lParams = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                percentTextView.setLayoutParams(lParams);
                percentTextView.setPadding(
                        gbRes.getDimensionPixelSize(R.dimen.percent_text_padding_left),
                        0,
                        gbRes.getDimensionPixelSize(R.dimen.percent_text_padding_right),
                        0);
                percentTextView.setTextColor(Color.WHITE);
                percentTextView.setVisibility(View.GONE);
                mPercentText = new StatusbarBatteryPercentage(percentTextView, mPrefs, this);
                int offset = Utils.isOxygenOs35Rom() ? 2 : 1;
                mSystemIcons.addView(mPercentText.getView(), mSystemIcons.getChildCount()-offset);
                if (DEBUG) log("Battery percent text injected");
            }
        }

        // inject circle battery view
        mCircleBattery = new CmCircleBattery(mContext, this);
        LinearLayout.LayoutParams lParams = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lParams.gravity = Gravity.CENTER_VERTICAL;
        lParams.setMarginStart(gbRes.getDimensionPixelSize(R.dimen.circle_battery_padding_left));
        lParams.setMarginEnd(Utils.isOxygenOs35Rom() ?
                 gbRes.getDimensionPixelSize(R.dimen.circle_battery_padding_right_op3t) :
                 gbRes.getDimensionPixelSize(R.dimen.circle_battery_padding_right));
        mCircleBattery.setLayoutParams(lParams);
        mCircleBattery.setVisibility(View.GONE);
        int pos = Utils.isOxygenOs35Rom() ?
                mSystemIcons.getChildCount()-1 : mSystemIcons.getChildCount();
        mSystemIcons.addView(mCircleBattery, pos);
        if (DEBUG) log("CmCircleBattery injected");

        // find battery
        View stockBatteryView = mSystemIcons.findViewById(
                res.getIdentifier("battery", "id", PACKAGE_NAME));
        if (stockBatteryView != null) {
            mStockBattery = new StatusbarBattery(stockBatteryView);
        }

        // reposition percent text
        if (mPercentText != null && 
                mContainerType == ContainerType.STATUSBAR && "RIGHT".equals(mPrefs.getString(
                GravityBoxSettings.PREF_KEY_BATTERY_PERCENT_TEXT_POSITION, "RIGHT"))) {
            View v = mPercentText.getView();
            v.setPadding(
                    Utils.isOxygenOs35Rom() ? 0 :
                    gbRes.getDimensionPixelSize(R.dimen.percent_text_padding_right),
                    0,
                    Utils.isOxygenOs35Rom() ?
                     gbRes.getDimensionPixelSize(R.dimen.percent_text_padding_left_op3t) :
                     gbRes.getDimensionPixelSize(R.dimen.percent_text_padding_left),
                    0);
            ViewGroup vg = (ViewGroup) v.getParent();
            vg.removeView(v);
            pos = Utils.isOxygenOs35Rom() ? vg.getChildCount()-1 : vg.getChildCount();
            vg.addView(v, pos);
        }
    }

    private void updateBatteryStyle() {
        try {
            if (mStockBattery != null) {
                if (mBatteryStyle == GravityBoxSettings.BATTERY_STYLE_STOCK ||
                        mBatteryStyle == GravityBoxSettings.BATTERY_STYLE_STOCK_PERCENT) {
                    mStockBattery.getView().setVisibility(View.VISIBLE);
                    mStockBattery.setShowPercentage(mBatteryStyle == 
                            GravityBoxSettings.BATTERY_STYLE_STOCK_PERCENT);
                } else {
                    mStockBattery.getView().setVisibility(View.GONE);
                }
            }

            if (mCircleBattery != null) {
                mCircleBattery.setVisibility((mBatteryStyle == GravityBoxSettings.BATTERY_STYLE_CIRCLE ||
                        mBatteryStyle == GravityBoxSettings.BATTERY_STYLE_CIRCLE_PERCENT ||
                        mBatteryStyle == GravityBoxSettings.BATTERY_STYLE_CIRCLE_DASHED ||
                        mBatteryStyle == GravityBoxSettings.BATTERY_STYLE_CIRCLE_DASHED_PERCENT) ?
                                View.VISIBLE : View.GONE);
                mCircleBattery.setPercentage(
                        mBatteryStyle == GravityBoxSettings.BATTERY_STYLE_CIRCLE_PERCENT ||
                        mBatteryStyle == GravityBoxSettings.BATTERY_STYLE_CIRCLE_DASHED_PERCENT);
                mCircleBattery.setStyle(
                        mBatteryStyle == GravityBoxSettings.BATTERY_STYLE_CIRCLE_DASHED ||
                        mBatteryStyle == GravityBoxSettings.BATTERY_STYLE_CIRCLE_DASHED_PERCENT ?
                                CmCircleBattery.Style.DASHED : CmCircleBattery.Style.SOLID);
            }

            if (mPercentText != null) {
                switch (mContainerType) {
                    case STATUSBAR:
                        if (mBatteryPercentTextEnabledSb || mMtkPercentTextEnabled) {
                            mPercentText.setVisibility(View.VISIBLE);
                            mPercentText.updateText();
                        } else {
                            mPercentText.setVisibility(View.GONE);
                        }
                        break;
                    case KEYGUARD:
                    case HEADER:
                        XposedHelpers.callMethod(mContainer, "updateVisibilities");
                        break;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private void createHooks() {
        if (mContainerType == ContainerType.STATUSBAR) {
            try {
                Class<?> batteryControllerClass = XposedHelpers.findClass(CLASS_BATTERY_CONTROLLER,
                        mContext.getClassLoader());
                XposedHelpers.findAndHookMethod(batteryControllerClass, "onReceive", 
                        Context.class, Intent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        updateBatteryStyle();
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }

        if (mContainerType == ContainerType.KEYGUARD || mContainerType == ContainerType.HEADER) {
            try {
                XposedHelpers.findAndHookMethod(mContainer.getClass(), "onBatteryLevelChanged",
                        int.class, boolean.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (mPercentText != null) {
                            mPercentText.updateText();
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
            try {
                XposedHelpers.findAndHookMethod(mContainer.getClass(),
                        "updateVisibilities", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (DEBUG) log(mContainerType + ": updateVisibilities");
                        if (mPercentText != null) {
                            if (mContainerType == ContainerType.KEYGUARD) {
                                if (mBatteryPercentTextKgMode == KeyguardMode.ALWAYS_SHOW) {
                                    mPercentText.setVisibility(View.VISIBLE);
                                } else if (mBatteryPercentTextKgMode == KeyguardMode.HIDDEN) {
                                    mPercentText.setVisibility(View.GONE);
                                }
                            } else if (mContainerType == ContainerType.HEADER) {
                                if (mBatteryPercentTextHeaderHide) {
                                    mPercentText.setVisibility(View.GONE);
                                }
                            }
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
            try {
                XposedHelpers.findAndHookMethod(mContainer.getClass(), "onConfigurationChanged",
                        Configuration.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (mPercentText != null) {
                            mPercentText.setTextSize(Integer.valueOf(mPrefs.getString(
                                GravityBoxSettings.PREF_KEY_BATTERY_PERCENT_TEXT_SIZE, "16")));
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
    }

    public boolean isBatterySaverIndicationDisabled() {
        return mBatterySaverIndicationDisabled;
    }

    public ContainerType getContainerType() {
        return mContainerType;
    }

    @Override
    public void onBroadcastReceived(Context context, Intent intent) {
        String action = intent.getAction();
        if (action.equals(GravityBoxSettings.ACTION_PREF_BATTERY_STYLE_CHANGED) &&
                intent.hasExtra(GravityBoxSettings.EXTRA_BATTERY_STYLE)) {
            mBatteryStyle = intent.getIntExtra(GravityBoxSettings.EXTRA_BATTERY_STYLE, 1);
            if (DEBUG) log("mBatteryStyle changed to: " + mBatteryStyle);
            updateBatteryStyle();
        } else if (action.equals(GravityBoxSettings.ACTION_PREF_BATTERY_PERCENT_TEXT_CHANGED)) {
            if (intent.hasExtra(GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_STATUSBAR)) {
                mBatteryPercentTextEnabledSb = intent.getBooleanExtra(
                        GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_STATUSBAR, false);
                if (DEBUG) log("mBatteryPercentTextEnabledSb changed to: " + mBatteryPercentTextEnabledSb);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_HEADER_HIDE)) {
                mBatteryPercentTextHeaderHide = intent.getBooleanExtra(
                        GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_HEADER_HIDE, false);
                if (DEBUG) log("mBatteryPercentTextHeaderHide changed to: " + mBatteryPercentTextHeaderHide);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_KEYGUARD)) {
                mBatteryPercentTextKgMode = KeyguardMode.valueOf(intent.getStringExtra(
                        GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_KEYGUARD));
                if (DEBUG) log("mBatteryPercentTextEnabledKg changed to: " + mBatteryPercentTextKgMode);
            }
            updateBatteryStyle();
        } else if (action.equals(GravityBoxSettings.ACTION_PREF_BATTERY_PERCENT_TEXT_SIZE_CHANGED) &&
                intent.hasExtra(GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_SIZE) && mPercentText != null) {
                    int textSize = intent.getIntExtra(GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_SIZE, 0);
                    mPercentText.setTextSize(textSize);
                    if (DEBUG) log("PercentText size changed to: " + textSize);
        } else if (action.equals(GravityBoxSettings.ACTION_PREF_BATTERY_PERCENT_TEXT_STYLE_CHANGED)
                       && mPercentText != null) {
            if (intent.hasExtra(GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_STYLE)) {
                    String percentSign = intent.getStringExtra(GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_STYLE);
                    mPercentText.setPercentSign(percentSign);
                    if (DEBUG) log("PercentText sign changed to: " + percentSign);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_CHARGING)) {
                int chargingStyle = intent.getIntExtra(GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_CHARGING,
                        StatusbarBatteryPercentage.CHARGING_STYLE_NONE);
                mPercentText.setChargingStyle(chargingStyle);
                if (DEBUG) log("PercentText charging style changed to: " + chargingStyle);
            }
            if (intent.hasExtra(GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_CHARGING_COLOR)) {
                int chargingColor = intent.getIntExtra(
                        GravityBoxSettings.EXTRA_BATTERY_PERCENT_TEXT_CHARGING_COLOR, Color.GREEN);
                mPercentText.setChargingColor(chargingColor);
                if (DEBUG) log("PercentText charging color changed to: " + chargingColor);
            }
        } else if (action.equals(ACTION_MTK_BATTERY_PERCENTAGE_SWITCH)) {
            mMtkPercentTextEnabled = intent.getIntExtra(EXTRA_MTK_BATTERY_PERCENTAGE_STATE, 0) == 1;
            if (DEBUG) log("mMtkPercentText changed to: " + mMtkPercentTextEnabled);
            updateBatteryStyle();
        } else if (action.equals(GravityBoxSettings.ACTION_BATTERY_SAVER_CHANGED)) {
            if (intent.hasExtra(GravityBoxSettings.EXTRA_BS_INDICATION_DISABLE)) {
                mBatterySaverIndicationDisabled = intent.getBooleanExtra(
                        GravityBoxSettings.EXTRA_BS_INDICATION_DISABLE, false);
                if (mCircleBattery != null && mCircleBattery.isAttachedToWindow()
                        && mContainerType == ContainerType.STATUSBAR) {
                    mCircleBattery.postInvalidate();
                }
            }
        }
    }
}
