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

import java.util.ArrayList;
import java.util.List;

import com.ceco.marshmallow.gravitybox.ModStatusBar.StatusBarState;
import com.ceco.marshmallow.gravitybox.ledcontrol.QuietHours;
import com.ceco.marshmallow.gravitybox.ledcontrol.QuietHoursActivity;
import com.ceco.marshmallow.gravitybox.managers.AppLauncher;
import com.ceco.marshmallow.gravitybox.managers.KeyguardStateMonitor;
import com.ceco.marshmallow.gravitybox.managers.SysUiManagers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaMetadata;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_InitPackageResources.InitPackageResourcesParam;

public class ModLockscreen {
    private static final String CLASS_PATH = "com.android.keyguard";
    private static final String TAG = "GB:ModLockscreen";
    public static final String PACKAGE_NAME = "com.android.systemui";

    private static final String CLASS_KG_PASSWORD_VIEW = CLASS_PATH + ".KeyguardPasswordView";
    private static final String CLASS_KG_PIN_VIEW = CLASS_PATH + ".KeyguardPINView";
    private static final String CLASS_KG_PASSWORD_TEXT_VIEW = CLASS_PATH + ".PasswordTextView";
    private static final String CLASS_KG_PASSWORD_TEXT_VIEW_PIN = CLASS_PATH + ".PasswordTextViewForPin";
    private static final String CLASS_KGVIEW_MEDIATOR = "com.android.systemui.keyguard.KeyguardViewMediator";
    private static final String CLASS_LOCK_PATTERN_VIEW = "com.android.internal.widget.LockPatternView";
    private static final String ENUM_DISPLAY_MODE = "com.android.internal.widget.LockPatternView.DisplayMode";
    private static final String CLASS_SB_WINDOW_MANAGER = "com.android.systemui.statusbar.phone.StatusBarWindowManager";
    private static final String CLASS_KG_VIEW_MANAGER = "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager";
    private static final String CLASS_CARRIER_TEXT = CLASS_PATH + ".CarrierText";
    private static final String CLASS_NOTIF_ROW = "com.android.systemui.statusbar.ExpandableNotificationRow";
    private static final String CLASS_KG_BOTTOM_AREA_VIEW = "com.android.systemui.statusbar.phone.KeyguardBottomAreaView";

    private static final boolean DEBUG = false;
    private static final boolean DEBUG_KIS = false;

    private static int MSG_DISMISS_KEYGUARD = 1;

    private static enum DirectUnlock { OFF, STANDARD, SEE_THROUGH };
    private static enum UnlockPolicy { DEFAULT, NOTIF_NONE, NOTIF_ONGOING };
    private static enum BottomAction { DEFAULT, PHONE, CUSTOM };

    private static XSharedPreferences mPrefs;
    private static XSharedPreferences mQhPrefs;
    private static Context mContext;
    private static Context mGbContext;
    private static Bitmap mCustomBg;
    private static QuietHours mQuietHours;
    private static Object mPhoneStatusBar;
    private static DirectUnlock mDirectUnlock = DirectUnlock.OFF;
    private static UnlockPolicy mDirectUnlockPolicy = UnlockPolicy.DEFAULT;
    private static LockscreenAppBar mAppBar;
    private static boolean mSmartUnlock;
    private static UnlockPolicy mSmartUnlockPolicy;
    private static DismissKeyguardHandler mDismissKeyguardHandler;
    private static GestureDetector mGestureDetector;
    private static List<TextView> mCarrierTextViews = new ArrayList<>();
    private static KeyguardStateMonitor mKgMonitor;
    private static LockscreenPinScrambler mPinScrambler;
    private static AppLauncher.AppInfo mLeftAction;

    private static boolean mInStealthMode;
    private static Object mPatternDisplayMode; 

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(GravityBoxSettings.ACTION_LOCKSCREEN_SETTINGS_CHANGED)
                 || action.equals(GravityBoxSettings.ACTION_PREF_LOCKSCREEN_BG_CHANGED)) {
                mPrefs.reload();
                prepareCustomBackground();
                prepareLeftAction();
                if (DEBUG) log("Settings reloaded");
            } else if (action.equals(KeyguardImageService.ACTION_KEYGUARD_IMAGE_UPDATED)) {
                if (DEBUG_KIS) log("ACTION_KEYGUARD_IMAGE_UPDATED received");
                setLastScreenBackground(true);
            } else if (action.equals(QuietHoursActivity.ACTION_QUIET_HOURS_CHANGED)) {
                mQhPrefs.reload();
                mQuietHours = new QuietHours(mQhPrefs);
                if (DEBUG) log("QuietHours settings reloaded");
            } else if (action.equals(GravityBoxSettings.ACTION_PREF_LOCKSCREEN_SHORTCUT_CHANGED)) {
                if (mAppBar != null) {
                    if (intent.hasExtra(GravityBoxSettings.EXTRA_LS_SHORTCUT_SLOT)) {
                        mAppBar.updateAppSlot(intent.getIntExtra(GravityBoxSettings.EXTRA_LS_SHORTCUT_SLOT, 0),
                            intent.getStringExtra(GravityBoxSettings.EXTRA_LS_SHORTCUT_VALUE));
                    }
                    if (intent.hasExtra(GravityBoxSettings.EXTRA_LS_SAFE_LAUNCH)) {
                        mAppBar.setSafeLaunchEnabled(intent.getBooleanExtra(
                                GravityBoxSettings.EXTRA_LS_SAFE_LAUNCH, false));
                    }
                }
            }
        }
    };

    public static String getUmcInsecureFieldName() {
        switch (Build.VERSION.SDK_INT) {
            default: return "mCanSkipBouncer";
        }
    }

    public static void initResources(final XSharedPreferences prefs, final InitPackageResourcesParam resparam) {
        try {
            // Lockscreen: disable menu key in lock screen
            Utils.TriState triState = Utils.TriState.valueOf(prefs.getString(
                    GravityBoxSettings.PREF_KEY_LOCKSCREEN_MENU_KEY, "DEFAULT"));
            if (DEBUG) log(GravityBoxSettings.PREF_KEY_LOCKSCREEN_MENU_KEY + ": " + triState);
            if (triState != Utils.TriState.DEFAULT) {
                resparam.res.setReplacement(PACKAGE_NAME, "bool", "config_disableMenuKeyInLockScreen",
                        triState == Utils.TriState.DISABLED);
                if (DEBUG) log("config_disableMenuKeyInLockScreen: " + (triState == Utils.TriState.DISABLED));
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static void init(final XSharedPreferences prefs, final ClassLoader classLoader) {
        try {
            mPrefs = prefs;
            mQhPrefs = new XSharedPreferences(GravityBox.PACKAGE_NAME, "quiet_hours");
            mQuietHours = new QuietHours(mQhPrefs);

            final Class<?> kgPasswordViewClass = XposedHelpers.findClass(CLASS_KG_PASSWORD_VIEW, classLoader);
            final Class<?> kgPINViewClass = XposedHelpers.findClass(CLASS_KG_PIN_VIEW, classLoader);
            final Class<?> kgPasswordTextViewClass = XposedHelpers.findClass(CLASS_KG_PASSWORD_TEXT_VIEW, classLoader);
            final Class<?> kgViewMediatorClass = XposedHelpers.findClass(CLASS_KGVIEW_MEDIATOR, classLoader);
            final Class<?> lockPatternViewClass = XposedHelpers.findClass(CLASS_LOCK_PATTERN_VIEW, classLoader);
            final Class<? extends Enum> displayModeEnum = (Class<? extends Enum>) XposedHelpers.findClass(ENUM_DISPLAY_MODE, classLoader);
            final Class<?> sbWindowManagerClass = XposedHelpers.findClass(CLASS_SB_WINDOW_MANAGER, classLoader); 

            String setupMethodName = "setupLocked";
            XposedHelpers.findAndHookMethod(kgViewMediatorClass, setupMethodName, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    mContext = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                    mGbContext = Utils.getGbContext(mContext);
                    if (SysUiManagers.KeyguardMonitor == null) {
                        SysUiManagers.createKeyguardMonitor(mContext, mPrefs);
                    }
                    mKgMonitor = SysUiManagers.KeyguardMonitor;
                    mKgMonitor.setMediator(param.thisObject);

                    prepareCustomBackground();
                    prepareGestureDetector();
                    prepareLeftAction();

                    IntentFilter intentFilter = new IntentFilter();
                    intentFilter.addAction(GravityBoxSettings.ACTION_LOCKSCREEN_SETTINGS_CHANGED);
                    intentFilter.addAction(KeyguardImageService.ACTION_KEYGUARD_IMAGE_UPDATED);
                    intentFilter.addAction(QuietHoursActivity.ACTION_QUIET_HOURS_CHANGED);
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_LOCKSCREEN_BG_CHANGED);
                    intentFilter.addAction(GravityBoxSettings.ACTION_PREF_LOCKSCREEN_SHORTCUT_CHANGED);
                    mContext.registerReceiver(mBroadcastReceiver, intentFilter);
                    if (DEBUG) log("Keyguard mediator constructed");
                }
            });

            XposedHelpers.findAndHookMethod(ModStatusBar.CLASS_PHONE_STATUSBAR, classLoader,
                    "updateMediaMetaData", boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    if (mPhoneStatusBar == null) {
                        mPhoneStatusBar = param.thisObject;
                    }

                    int state = XposedHelpers.getIntField(mPhoneStatusBar, "mState");
                    if (state != StatusBarState.KEYGUARD && state != StatusBarState.SHADE_LOCKED) {
                        if (DEBUG) log("updateMediaMetaData: Invalid status bar state: " + state);
                        return;
                    }

                    View backDrop = (View) XposedHelpers.getObjectField(mPhoneStatusBar, "mBackdrop");
                    ImageView backDropBack = (ImageView) XposedHelpers.getObjectField(
                            mPhoneStatusBar, "mBackdropBack");
                    if (backDrop == null || backDropBack == null) {
                        if (DEBUG) log("updateMediaMetaData: called too early");
                        return;
                    }

                    boolean hasArtwork = false;
                    MediaMetadata mm = (MediaMetadata) XposedHelpers.getObjectField(
                            mPhoneStatusBar, "mMediaMetadata");
                    if (mm != null) {
                        hasArtwork = mm.getBitmap(MediaMetadata.METADATA_KEY_ART) != null ||
                                mm.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null;
                    }
                    if (DEBUG) log("updateMediaMetaData: hasArtwork=" + hasArtwork);

                    // custom background
                    if (!hasArtwork && mCustomBg != null) {
                        backDrop.animate().cancel();
                        backDropBack.animate().cancel();
                        backDropBack.setImageBitmap(mCustomBg);
                        if ((Boolean) XposedHelpers.getBooleanField(
                                mPhoneStatusBar, "mScrimSrcModeEnabled")) {
                            PorterDuffXfermode xferMode = (PorterDuffXfermode) XposedHelpers
                                    .getObjectField(mPhoneStatusBar, "mSrcXferMode");
                            XposedHelpers.callMethod(backDropBack.getDrawable().mutate(),
                                    "setXfermode", xferMode);
                        }
                        backDrop.setVisibility(View.VISIBLE);
                        backDrop.animate().alpha(1f);
                        if (DEBUG) log("updateMediaMetaData: showing custom background");
                    }

                    // opacity
                    if (hasArtwork || mCustomBg != null) {
                        backDropBack.getDrawable().clearColorFilter();
                        final int opacity = mPrefs.getInt(
                                GravityBoxSettings.PREF_KEY_LOCKSCREEN_BACKGROUND_OPACITY, 100);
                        if (opacity != 100) {
                            final int alpha = (int) ((1 - opacity / 100f) * 255);
                            final int overlayColor = Color.argb(alpha, 0, 0, 0);
                            backDropBack.getDrawable().mutate()
                                .setColorFilter(overlayColor, PorterDuff.Mode.SRC_OVER);
                            if (DEBUG) log("updateMediaMetaData: opacity set");
                        }
                    }
                }
            });

            final Utils.TriState triState = Utils.TriState.valueOf(prefs.getString(
                    GravityBoxSettings.PREF_KEY_LOCKSCREEN_ROTATION, "DEFAULT"));
            if (triState != Utils.TriState.DEFAULT) {
                XposedHelpers.findAndHookMethod(sbWindowManagerClass, "shouldEnableKeyguardScreenRotation",
                        new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        if (DEBUG) log("shouldEnableKeyguardScreenRotation called");
                        try {
                            if (Utils.isMtkDevice()) {
                                return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                            } else {
                                return (triState == Utils.TriState.ENABLED);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                            return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                        }
                    }
                });
            }

            XposedHelpers.findAndHookMethod(kgPasswordViewClass, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    if (!mPrefs.getBoolean(
                            GravityBoxSettings.PREF_KEY_LOCKSCREEN_QUICK_UNLOCK, false)) return;

                    final TextView passwordEntry = 
                            (TextView) XposedHelpers.getObjectField(param.thisObject, "mPasswordEntry");
                    if (passwordEntry == null) return;

                    passwordEntry.addTextChangedListener(new TextWatcher() {
                        @Override
                        public void afterTextChanged(Editable s) {
                            doQuickUnlock(param.thisObject, passwordEntry.getText().toString());
                        }
                        @Override
                        public void beforeTextChanged(CharSequence arg0,int arg1, int arg2, int arg3) { }
                        @Override
                        public void onTextChanged(CharSequence arg0, int arg1, int arg2, int arg3) { }
                    });
                }
            });

            XposedHelpers.findAndHookMethod(kgPINViewClass, "onFinishInflate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    if (prefs.getBoolean(GravityBoxSettings.PREF_KEY_LOCKSCREEN_PIN_SCRAMBLE, false)) {
                        mPinScrambler = new LockscreenPinScrambler((ViewGroup)param.thisObject);
                        if (Utils.isXperiaDevice()) {
                            mPinScrambler.scramble();
                        }
                    }
                    if (mPrefs.getBoolean(GravityBoxSettings.PREF_KEY_LOCKSCREEN_QUICK_UNLOCK, false)) {
                        final View passwordEntry = 
                                (View) XposedHelpers.getObjectField(param.thisObject, "mPasswordEntry");
                        if (passwordEntry != null) {
                            XposedHelpers.setAdditionalInstanceField(passwordEntry, "gbPINView",
                                    param.thisObject);
                        }
                    }
                }
            });

            if (!Utils.isXperiaDevice()) {
                XposedHelpers.findAndHookMethod(kgPINViewClass, "resetState", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                        if (prefs.getBoolean(GravityBoxSettings.PREF_KEY_LOCKSCREEN_PIN_SCRAMBLE, false) &&
                                mPinScrambler != null) {
                            mPinScrambler.scramble();
                        }
                    }
                });
            }

            XposedHelpers.findAndHookMethod(kgPasswordTextViewClass, "append", char.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    if (!mPrefs.getBoolean(
                            GravityBoxSettings.PREF_KEY_LOCKSCREEN_QUICK_UNLOCK, false)) return;

                    Object pinView = XposedHelpers.getAdditionalInstanceField(param.thisObject, "gbPINView");
                    if (pinView != null) {
                        if (DEBUG) log("quickUnlock: PasswordText belongs to PIN view");
                        String entry = (String) XposedHelpers.getObjectField(param.thisObject, "mText");
                        doQuickUnlock(pinView, entry);
                    }
                }
            });

            if (Utils.isOxygenOs35Rom()) {
                XposedHelpers.findAndHookMethod(CLASS_KG_PASSWORD_TEXT_VIEW_PIN, classLoader,
                        "append", char.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                        if (!mPrefs.getBoolean(
                                GravityBoxSettings.PREF_KEY_LOCKSCREEN_QUICK_UNLOCK, false)) return;
                        Object pinView = XposedHelpers.getAdditionalInstanceField(param.thisObject, "gbPINView");
                        if (pinView != null) {
                            if (DEBUG) log("quickUnlock: OnePlus3T PasswordTextViewForPin");
                            String entry = (String) XposedHelpers.getObjectField(param.thisObject, "mText");
                            doQuickUnlock(pinView, entry);
                        }
                    }
                });
            }

            XposedHelpers.findAndHookMethod(lockPatternViewClass, "onDraw", Canvas.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                    beforeLockPatternDraw(displayModeEnum, param.thisObject);
                }

                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    afterLockPatternDraw(param.thisObject);
                }
            });

            XposedHelpers.findAndHookMethod(kgViewMediatorClass, "playSounds", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                    if (mQuietHours.isSystemSoundMuted(QuietHours.SystemSound.SCREEN_LOCK)) {
                        param.setResult(null);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(CLASS_KG_VIEW_MANAGER, classLoader, "onScreenTurnedOff",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    mKgMonitor.unregisterListener(mKgStateListener);
                    mDirectUnlock = DirectUnlock.valueOf(prefs.getString(
                            GravityBoxSettings.PREF_KEY_LOCKSCREEN_DIRECT_UNLOCK, "OFF"));
                    mDirectUnlockPolicy = UnlockPolicy.valueOf(prefs.getString(
                            GravityBoxSettings.PREF_KEY_LOCKSCREEN_DIRECT_UNLOCK_POLICY, "DEFAULT"));
                    mSmartUnlock = prefs.getBoolean(GravityBoxSettings.PREF_KEY_LOCKSCREEN_SMART_UNLOCK, false);
                    mSmartUnlockPolicy = UnlockPolicy.valueOf(prefs.getString(
                            GravityBoxSettings.PREF_KEY_LOCKSCREEN_SMART_UNLOCK_POLICY, "DEFAULT"));
                    if (mSmartUnlock && mDismissKeyguardHandler == null) {
                        mDismissKeyguardHandler = new DismissKeyguardHandler();
                    }
                    updateCarrierText();
                }
            });

            XposedHelpers.findAndHookMethod(CLASS_KG_VIEW_MANAGER, classLoader, "onScreenTurnedOn",
                    new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    if (!mKgMonitor.isSecured()) {
                        if (DEBUG) log("onScreenTurnedOn: noop as keyguard is not secured");
                        return;
                    }

                    if (!mKgMonitor.isTrustManaged()) {
                        if (canTriggerDirectUnlock()) {
                            if (mDirectUnlock == DirectUnlock.SEE_THROUGH) {
                                XposedHelpers.callMethod(mPhoneStatusBar, "showBouncer");
                            } else {
                                XposedHelpers.callMethod(mPhoneStatusBar, "makeExpandedInvisible");
                            }
                        }
                    } else if (canTriggerSmartUnlock()) {
                        mKgMonitor.registerListener(mKgStateListener);
                        if (!mKgMonitor.isLocked()) {
                            // previous state is insecure so we rather wait a second as smart lock can still
                            // decide to make it secure after a while. Seems to be necessary only for
                            // on-body detection. Other smart lock methods seem to always start with secured state
                            if (DEBUG) log("onScreenTurnedOn: Scheduling Keyguard dismiss");
                            mDismissKeyguardHandler.sendEmptyMessageDelayed(MSG_DISMISS_KEYGUARD, 1000);
                        }
                    }
                }
            });

            XposedHelpers.findAndHookMethod(ModStatusBar.CLASS_PHONE_STATUSBAR, classLoader,
                    "makeStatusBarView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    ViewGroup kgStatusView = (ViewGroup) XposedHelpers.getObjectField(
                            param.thisObject, "mKeyguardStatusView");
                    Resources res = kgStatusView.getResources();
                    // try mtk container first
                    int containerId = res.getIdentifier("mtk_keyguard_clock_container",
                            "id", PACKAGE_NAME);
                    if (containerId == 0) {
                        // fallback to AOSP container
                        containerId = res.getIdentifier(Utils.isOxygenOs35Rom() ? "clock_view" :
                                "keyguard_clock_container", "id", PACKAGE_NAME);
                    }
                    if (containerId != 0) {
                        ViewGroup container = (ViewGroup) kgStatusView.findViewById(containerId);
                        if (container != null) {
                            mAppBar = new LockscreenAppBar(mContext, mGbContext, container,
                                    param.thisObject, prefs);
                        }
                    }
                }
            });

            XposedHelpers.findAndHookMethod(ModStatusBar.CLASS_NOTIF_PANEL_VIEW, classLoader,
                    "onTouchEvent", MotionEvent.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                    if (mPrefs.getBoolean(GravityBoxSettings.PREF_KEY_LOCKSCREEN_D2TS, false) &&
                            mGestureDetector != null &&
                            (int) XposedHelpers.callMethod(
                                XposedHelpers.getObjectField(param.thisObject, "mStatusBar"),
                                "getBarState") == StatusBarState.KEYGUARD) {
                        mGestureDetector.onTouchEvent((MotionEvent) param.args[0]);
                    }
                }
            });

            if (!Utils.isXperiaDevice()) {
                XC_MethodHook carrierTextHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                        String text = mPrefs.getString(GravityBoxSettings.PREF_KEY_LOCKSCREEN_CARRIER_TEXT, "");
                        if (!mCarrierTextViews.contains(param.thisObject)) {
                            mCarrierTextViews.add((TextView) param.thisObject);
                        } 
                        if (text.isEmpty()) {
                            return;
                        } else {
                            ((TextView)param.thisObject).setText(text.trim().isEmpty() ? "" : text);
                        }
                    }
                };

                if (Utils.isSamsungRom()) {
                    XposedHelpers.findAndHookMethod(CLASS_CARRIER_TEXT,
                            classLoader, "updateCarrierText", Intent.class, carrierTextHook);
                } else {
                    XposedHelpers.findAndHookMethod(CLASS_CARRIER_TEXT,
                            classLoader, "updateCarrierText", carrierTextHook);
                }
            }

            XposedHelpers.findAndHookMethod(CLASS_KG_BOTTOM_AREA_VIEW, classLoader,
                    "canLaunchVoiceAssist", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                    if (BottomAction.valueOf(mPrefs.getString(
                            GravityBoxSettings.PREF_KEY_LOCKSCREEN_BLEFT_ACTION, "DEFAULT")) ==
                                BottomAction.PHONE) {
                        param.setResult(false);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(CLASS_KG_BOTTOM_AREA_VIEW, classLoader,
                    "updateLeftAffordanceIcon", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                    if (mLeftAction != null) {
                        ImageView v = (ImageView) XposedHelpers.getObjectField(
                                param.thisObject, "mLeftAffordanceView");
                        v.setImageDrawable(mLeftAction.getAppIcon());
                        v.setContentDescription(mLeftAction.getAppName());
                    }
                }
            });

            XposedHelpers.findAndHookMethod(CLASS_KG_BOTTOM_AREA_VIEW, classLoader,
                    "launchLeftAffordance", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(final MethodHookParam param) throws Throwable {
                    if (mLeftAction != null) {
                        SysUiManagers.AppLauncher.startActivity(mContext, mLeftAction.getIntent());
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    } 

    private static KeyguardStateMonitor.Listener mKgStateListener = new KeyguardStateMonitor.Listener() {
        @Override
        public void onKeyguardStateChanged() {
            final boolean trustManaged = mKgMonitor.isTrustManaged();
            final boolean insecure = !mKgMonitor.isLocked();
            if (DEBUG) log("updateMethodSecure: trustManaged=" + trustManaged +
                    "; insecure=" + insecure);
            if (trustManaged && insecure) {
                // either let already queued message to be handled or handle new one immediately
                if (!mDismissKeyguardHandler.hasMessages(MSG_DISMISS_KEYGUARD)) {
                    mDismissKeyguardHandler.sendEmptyMessage(MSG_DISMISS_KEYGUARD);
                }
            } else if (mDismissKeyguardHandler.hasMessages(MSG_DISMISS_KEYGUARD)) {
                // smart lock decided to make it secure so remove any pending dismiss keyguard messages
                mDismissKeyguardHandler.removeMessages(MSG_DISMISS_KEYGUARD);
                if (DEBUG) log("updateMethodSecure: pending keyguard dismiss cancelled");
            }
            if (mKgMonitor.isShowing()) {
                mKgMonitor.unregisterListener(this);
            }
        }
    };

    private static boolean canTriggerDirectUnlock() {
        return (mDirectUnlock != DirectUnlock.OFF &&
                    canTriggerUnlock(mDirectUnlockPolicy));
    }

    private static boolean canTriggerSmartUnlock() {
        return (mSmartUnlock && canTriggerUnlock(mSmartUnlockPolicy));
    }

    private static boolean canTriggerUnlock(UnlockPolicy policy) {
        if (policy == UnlockPolicy.DEFAULT) return true;

        try {
            ViewGroup stack = (ViewGroup) XposedHelpers.getObjectField(mPhoneStatusBar, "mStackScroller");
            int childCount = stack.getChildCount();
            int notifCount = 0;
            int notifClearableCount = 0;
            for (int i=0; i<childCount; i++) {
                View v = stack.getChildAt(i);
                if (v.getVisibility() != View.VISIBLE ||
                        !v.getClass().getName().equals(CLASS_NOTIF_ROW))
                    continue;
                notifCount++;
                if ((boolean) XposedHelpers.callMethod(v, "isClearable")) {
                    notifClearableCount++;
                }
            }
            return (policy == UnlockPolicy.NOTIF_NONE) ?
                    notifCount == 0 : notifClearableCount == 0;
        } catch (Throwable t) {
            XposedBridge.log(t);
            return true;
        }
    }

    private static class DismissKeyguardHandler extends Handler {
        public DismissKeyguardHandler() {
            super();
        }

        @Override
        public void handleMessage(Message msg) { 
            if (msg.what == MSG_DISMISS_KEYGUARD) {
                mKgMonitor.dismissKeyguard();
            }
        }
    };

    private static void doQuickUnlock(final Object securityView, final String entry) {
        if (entry.length() != mPrefs.getInt(
                GravityBoxSettings.PREF_KEY_LOCKSCREEN_PIN_LENGTH, 4)) return;

        AsyncTask.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Object lockPatternUtils = XposedHelpers.getObjectField(securityView, "mLockPatternUtils");
                    final Object lockSettings = XposedHelpers.callMethod(lockPatternUtils, "getLockSettings");
                    final int userId = mKgMonitor.getCurrentUserId();
                    final Object response = XposedHelpers.callMethod(lockSettings, "checkPassword", entry, userId);
                    final int code = (int)XposedHelpers.callMethod(response, "getResponseCode");
                    if (code == 0) {
                        final Object callback = XposedHelpers.getObjectField(securityView, "mCallback");
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    XposedHelpers.callMethod(callback, "reportUnlockAttempt", true, userId);
                                    XposedHelpers.callMethod(callback, "dismiss", true);
                                } catch (Throwable t) {
                                    log("Error dimissing keyguard: " + t.getMessage());
                                }
                            }
                        });
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);;
                }
            }
        });
    }

    private static synchronized void prepareCustomBackground() {
        try {
            if (mCustomBg != null) {
                mCustomBg = null;
            }
            final String bgType = mPrefs.getString(
                  GravityBoxSettings.PREF_KEY_LOCKSCREEN_BACKGROUND,
                  GravityBoxSettings.LOCKSCREEN_BG_DEFAULT);
    
            if (bgType.equals(GravityBoxSettings.LOCKSCREEN_BG_COLOR)) {
                int color = mPrefs.getInt(
                      GravityBoxSettings.PREF_KEY_LOCKSCREEN_BACKGROUND_COLOR, Color.BLACK);
                mCustomBg = Utils.drawableToBitmap(new ColorDrawable(color));
            } else if (bgType.equals(GravityBoxSettings.LOCKSCREEN_BG_IMAGE)) {
                String wallpaperFile = mGbContext.getFilesDir() + "/lockwallpaper";
                mCustomBg = BitmapFactory.decodeFile(wallpaperFile);
            } else if (bgType.equals(GravityBoxSettings.LOCKSCREEN_BG_LAST_SCREEN)) {
                setLastScreenBackground(false);
            }
    
            if (!bgType.equals(GravityBoxSettings.LOCKSCREEN_BG_LAST_SCREEN) &&
                    mCustomBg != null && mPrefs.getBoolean(
                    GravityBoxSettings.PREF_KEY_LOCKSCREEN_BACKGROUND_BLUR_EFFECT, false)) {
                mCustomBg = Utils.blurBitmap(mContext, mCustomBg, mPrefs.getInt(
                          GravityBoxSettings.PREF_KEY_LOCKSCREEN_BACKGROUND_BLUR_INTENSITY, 14));
            }
            if (DEBUG) log("prepareCustomBackground: type=" + bgType);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static synchronized void setLastScreenBackground(boolean refresh) {
        try {
            String kisImageFile = mGbContext.getFilesDir() + "/kis_image.png";
            mCustomBg = BitmapFactory.decodeFile(kisImageFile);
            if (refresh && mPhoneStatusBar != null) {
                XposedHelpers.callMethod(mPhoneStatusBar, "updateMediaMetaData", false);
            }
            if (DEBUG_KIS) log("setLastScreenBackground: Last screen background updated");
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void beforeLockPatternDraw(final Class<? extends Enum> displayModeEnum, final Object thisObject) {
        final Object patternDisplayMode = XposedHelpers.getObjectField(thisObject, "mPatternDisplayMode");
        final Boolean inStealthMode = XposedHelpers.getBooleanField(thisObject, "mInStealthMode");  

        if (!mPrefs.getBoolean(GravityBoxSettings.PREF_KEY_LOCKSCREEN_SHOW_PATTERN_ERROR, true) &&
                    mPatternDisplayMode == null && patternDisplayMode == Enum.valueOf(displayModeEnum, "Wrong")) {
            mInStealthMode = inStealthMode;
            mPatternDisplayMode = patternDisplayMode;
            XposedHelpers.setBooleanField(thisObject, "mInStealthMode", true);
            XposedHelpers.setObjectField(thisObject, "mPatternDisplayMode", Enum.valueOf(displayModeEnum, "Correct"));
        } else {
            mPatternDisplayMode = null;
        }
    }

    private static void afterLockPatternDraw(final Object thisObject) {
        if (null != mPatternDisplayMode) {
            XposedHelpers.setBooleanField(thisObject, "mInStealthMode", mInStealthMode);
            XposedHelpers.setObjectField(thisObject, "mPatternDisplayMode", mPatternDisplayMode);
            mInStealthMode = false;
            mPatternDisplayMode = null;
        }
    }

    private static void prepareGestureDetector() {
        try {
            mGestureDetector = new GestureDetector(mContext, 
                    new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    Intent intent = new Intent(ModHwKeys.ACTION_SLEEP);
                    mContext.sendBroadcast(intent);
                    return true;
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void updateCarrierText() {
        for (TextView tv : mCarrierTextViews) {
            try {
                if (Utils.isSamsungRom()) {
                    XposedHelpers.callMethod(tv, "updateCarrierText", (Intent) null);
                } else {
                    XposedHelpers.callMethod(tv, "updateCarrierText");
                }
            } catch (Throwable t) {
                XposedBridge.log(t);
            }
        }
    }

    private static void prepareLeftAction() {
        BottomAction type = BottomAction.valueOf(mPrefs.getString(
                GravityBoxSettings.PREF_KEY_LOCKSCREEN_BLEFT_ACTION, "DEFAULT"));
        String action = mPrefs.getString(GravityBoxSettings.PREF_KEY_LOCKSCREEN_BLEFT_ACTION_CUSTOM, null);
        if (type != BottomAction.CUSTOM || action == null || action.isEmpty()) {
            mLeftAction = null;
        } else if (SysUiManagers.AppLauncher != null &&
                (mLeftAction == null || !action.equals(mLeftAction.getValue()))) {
            mLeftAction = SysUiManagers.AppLauncher.createAppInfo();
            mLeftAction.setSizeDp(32);
            mLeftAction.initAppInfo(action);
        }
    }
}
