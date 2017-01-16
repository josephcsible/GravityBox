/*
 * Copyright (C) 2016 Peter Gregus for GravityBox Project (C3C076@xda)
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

package com.ceco.marshmallow.gravitybox.ledcontrol;

import java.io.File;
import java.text.DateFormatSymbols;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import com.ceco.marshmallow.gravitybox.GravityBoxSettings;
import com.ceco.marshmallow.gravitybox.R;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.preference.MultiSelectListPreference;
import android.preference.PreferenceFragment;

public class QuietHoursActivity extends Activity {

    public static final String PREF_KEY_QH_ENABLED = "pref_lc_qh_enabled";
    public static final String PREF_KEY_QH_START = "pref_lc_qh_start2";
    public static final String PREF_KEY_QH_END = "pref_lc_qh_end2";
    public static final String PREF_KEY_QH_START_ALT = "pref_lc_qh_start_alt2";
    public static final String PREF_KEY_QH_END_ALT = "pref_lc_qh_end_alt2";
    public static final String PREF_KEY_QH_MUTE_LED = "pref_lc_qh_mute_led";
    public static final String PREF_KEY_QH_MUTE_VIBE = "pref_lc_qh_mute_vibe";
    public static final String PREF_KEY_QH_MUTE_SYSTEM_SOUNDS = "pref_lc_qh_mute_system_sounds";
    public static final String PREF_KEY_QH_STATUSBAR_ICON = "pref_lc_qh_statusbar_icon";
    public static final String PREF_KEY_QH_MODE = "pref_lc_qh_mode";
    public static final String PREF_KEY_QH_INTERACTIVE = "pref_lc_qh_interactive";
    public static final String PREF_KEY_QH_WEEKDAYS = "pref_lc_qh_weekdays";
    public static final String PREF_KEY_MUTE_SYSTEM_VIBE = "pref_lc_qh_mute_system_vibe";

    public static final String ACTION_QUIET_HOURS_CHANGED = 
            "gravitybox.intent.action.QUIET_HOURS_CHANGED";
    public static final String ACTION_SET_QUIET_HOURS_MODE = 
            "gravitybox.intent.action.SET_QUIET_HOURS_MODE";
    public static final String EXTRA_QH_MODE = "qhMode";

    public static QuietHours.Mode setQuietHoursMode(final Context context, String mode) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("quiet_hours", Context.MODE_WORLD_READABLE);
            QuietHours qh = new QuietHours(prefs);
            if (!qh.enabled) {
                return null;
            }

            final QuietHours.Mode qhMode;
            if (mode != null) {
                qhMode = QuietHours.Mode.valueOf(mode);
            } else {
                switch (qh.mode) {
                    default:
                    case ON:
                        qhMode = QuietHours.Mode.OFF;
                        break;
                    case AUTO:
                        qhMode = qh.quietHoursActive() ? 
                                QuietHours.Mode.OFF : QuietHours.Mode.ON;
                        break;
                    case OFF:
                        qhMode = QuietHours.Mode.ON;
                        break;
                }
            }
            prefs.edit().putString(QuietHoursActivity.PREF_KEY_QH_MODE, qhMode.toString()).commit();
            Intent intent = new Intent(ACTION_QUIET_HOURS_CHANGED);
            context.sendBroadcast(intent);
            return qhMode;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static int getToastResIdFromMode(QuietHours.Mode mode) {
        switch (mode) {
            case ON: return R.string.quiet_hours_on;
            case OFF: return R.string.quiet_hours_off;
            case AUTO: return R.string.quiet_hours_auto;
            default: return 0;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        File file = new File(getFilesDir() + "/" + GravityBoxSettings.FILE_THEME_DARK_FLAG);
        if (file.exists()) {
            setTheme(R.style.AppThemeDark);
        }

        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction().replace(android.R.id.content, new PrefsFragment()).commit();
        }
    }

    public static class PrefsFragment extends PreferenceFragment implements OnSharedPreferenceChangeListener {

        private SharedPreferences mPrefs;
        private MultiSelectListPreference mPrefWeekDays;
        private MultiSelectListPreference mPrefSystemSounds;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            getPreferenceManager().setSharedPreferencesName("quiet_hours");
            getPreferenceManager().setSharedPreferencesMode(Context.MODE_WORLD_READABLE);
            mPrefs = getPreferenceManager().getSharedPreferences();

            addPreferencesFromResource(R.xml.led_control_quiet_hours_settings);
            setupWeekDaysPref();
            mPrefSystemSounds = (MultiSelectListPreference) findPreference(PREF_KEY_QH_MUTE_SYSTEM_SOUNDS);
        }

        private void setupWeekDaysPref() {
            mPrefWeekDays = (MultiSelectListPreference) findPreference(PREF_KEY_QH_WEEKDAYS); 
            String[] days = new DateFormatSymbols(Locale.getDefault()).getWeekdays();
            CharSequence[] entries = new CharSequence[7];
            CharSequence[] entryValues = new CharSequence[7];
            for (int i=1; i<=7; i++) {
                entries[i-1] = days[i];
                entryValues[i-1] = String.valueOf(i);
            }
            mPrefWeekDays.setEntries(entries);
            mPrefWeekDays.setEntryValues(entryValues);
            if (mPrefs.getStringSet(PREF_KEY_QH_WEEKDAYS, null) == null) {
                Set<String> value = new HashSet<String>(Arrays.asList("2","3","4","5","6"));
                mPrefs.edit().putStringSet(PREF_KEY_QH_WEEKDAYS, value).commit();
                mPrefWeekDays.setValues(value);
            }
        }

        private void updateSummaries() {
            String[] days = new DateFormatSymbols(Locale.getDefault()).getWeekdays();
            Set<String> values = new TreeSet<String>(mPrefWeekDays.getValues());
            String summary = "";
            for (String wday : values) {
                if (!summary.isEmpty()) summary += ", ";
                summary += days[Integer.valueOf(wday)];
            }
            mPrefWeekDays.setSummary(summary);

            CharSequence[] entries = mPrefSystemSounds.getEntries();
            CharSequence[] entryValues = mPrefSystemSounds.getEntryValues();
            values = mPrefSystemSounds.getValues();
            summary = "";
            if (values != null) {
                for (String value : values) {
                    for (int i=0; i<entryValues.length; i++) {
                        if (entryValues[i].equals(value)) {
                            if (!summary.isEmpty()) summary += ", ";
                            summary += entries[i];
                            break;
                        }
                    }
                }
            }
            mPrefSystemSounds.setSummary(summary);
        }

        @Override
        public void onResume() {
            super.onResume();
            mPrefs.registerOnSharedPreferenceChangeListener(this);
            updateSummaries();
        }

        @Override
        public void onPause() {
            mPrefs.unregisterOnSharedPreferenceChangeListener(this);
            super.onPause();
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            updateSummaries();
            Intent intent = new Intent(ACTION_QUIET_HOURS_CHANGED);
            prefs.edit().commit();
            getActivity().sendBroadcast(intent);
        }
    }
}
