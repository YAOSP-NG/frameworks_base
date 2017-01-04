/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import libcore.icu.LocaleData;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.format.DateFormat;
import android.text.style.CharacterStyle;
import android.text.style.RelativeSizeSpan;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.view.Display;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.DemoMode;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Digital clock for the status bar.
 */
public class Clock extends TextView implements DemoMode, Tunable {

    private boolean mAttached;
    private Calendar mCalendar;
    private String mClockFormatString;
    private SimpleDateFormat mClockFormat;
    private SimpleDateFormat mContentDescriptionFormat;
    private Locale mLocale;

    public static final String CLOCK_SHOW          = "clock_show";
    public static final String CLOCK_SECONDS       = "clock_seconds";
    public static final String CLOCK_AM_PM_STYLE   = "clock_am_pm_style";
    public static final String CLOCK_STYLE         = "clock_style";
    public static final String CLOCK_DATE_SHOW     = "clock_date_SHOW";
    public static final String CLOCK_DATE_STYLE    = "clock_date_style";
    public static final String CLOCK_DATE_FORMAT   = "clock_date_format";
    public static final String CLOCK_DATE_POSITION = "clock_date_position";

    public static final int CLOCK_SHOW_DISABLED            = 0;
    public static final int CLOCK_SHOW_ENABLED             = 1;

    public static final int CLOCK_SECONDS_DISABLED         = 0;
    public static final int CLOCK_SECONDS_ENABLED          = 1;

    public static final int CLOCK_STYLE_RIGHT_CLOCK        = 0;
    public static final int CLOCK_STYLE_CENTER_CLOCK       = 1;
    public static final int CLOCK_STYLE_LEFT_CLOCK         = 2;

    public static final int CLOCK_AM_PM_STYLE_NONE         = 0;
    public static final int CLOCK_AM_PM_STYLE_SMALL        = 1;
    public static final int CLOCK_AM_PM_STYLE_NORMAL       = 2;

    public static final int CLOCK_DATE_SHOW_NONE           = 0;
    public static final int CLOCK_DATE_SHOW_SMALL          = 1;
    public static final int CLOCK_DATE_SHOW_NORMAL         = 2;

    public static final int CLOCK_DATE_STYLE_NORMAL        = 0;
    public static final int CLOCK_DATE_STYLE_LOWERCASE     = 1;
    public static final int CLOCK_DATE_STYLE_UPPERCASE     = 2;

    public static final int CLOCK_DATE_POSITION_LEFT       = 0;
    public static final int CLOCK_DATE_POSITION_RIGHT      = 1;

    protected boolean mShowClock;
    protected boolean mShowSeconds;
    protected int mClockAmPmStyle;
    protected int mClockStyle;
    protected int mClockDateShow;
    protected int mClockDateStyle;
    protected int mClockDatePosition;
    protected int mClockAndDateWidth;
    protected String mClockDateFormat;
    private StatusBarIconController mStatusBarIconController;
    private Handler mSecondsHandler;

    public Clock(Context context) {
        this(context, null);
    }

    public Clock(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public Clock(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        TypedArray a = context.getTheme().obtainStyledAttributes(
                attrs,
                R.styleable.Clock,
                0, 0);
        try {
            mClockAmPmStyle = a.getInt(R.styleable.Clock_amPmStyle, CLOCK_AM_PM_STYLE_NONE);
        } finally {
            a.recycle();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();

            filter.addAction(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
            filter.addAction(Intent.ACTION_USER_SWITCHED);

            getContext().registerReceiverAsUser(mIntentReceiver, UserHandle.ALL, filter,
                    null, getHandler());

            TunerService.get(getContext()).addTunable(this, CLOCK_SHOW, CLOCK_SECONDS,
                    CLOCK_AM_PM_STYLE, CLOCK_STYLE, CLOCK_DATE_SHOW, CLOCK_DATE_STYLE,
                    CLOCK_DATE_POSITION, CLOCK_DATE_FORMAT);
        }

        // NOTE: It's safe to do these after registering the receiver since the receiver always runs
        // in the main thread, therefore the receiver can't run before this method returns.

        // The time zone may have changed while the receiver wasn't registered, so update the Time
        mCalendar = Calendar.getInstance(TimeZone.getDefault());

        // Make sure we update to the current time
        updateClock();
        updateShowSeconds();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            getContext().unregisterReceiver(mIntentReceiver);
            mAttached = false;
            TunerService.get(getContext()).removeTunable(this);
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_TIMEZONE_CHANGED)) {
                String tz = intent.getStringExtra("time-zone");
                mCalendar = Calendar.getInstance(TimeZone.getTimeZone(tz));
                if (mClockFormat != null) {
                    mClockFormat.setTimeZone(mCalendar.getTimeZone());
                }
            } else if (action.equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
                final Locale newLocale = getResources().getConfiguration().locale;
                if (! newLocale.equals(mLocale)) {
                    mLocale = newLocale;
                    mClockFormatString = ""; // force refresh
                }
            }
            updateClock();
        }
    };

    final void updateClock() {
        if (mDemoMode) return;
        if (mCalendar != null) {
            mCalendar.setTimeInMillis(System.currentTimeMillis());
            setText(getSmallTime());
            setContentDescription(mContentDescriptionFormat.format(mCalendar.getTime()));
        }
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (CLOCK_SHOW.equals(key)) {
            mShowClock = newValue == null ||
                    Integer.parseInt(newValue) == CLOCK_SHOW_ENABLED;

        } else if (CLOCK_SECONDS.equals(key)) {
            mShowSeconds = newValue != null &&
                    Integer.parseInt(newValue) != CLOCK_SECONDS_DISABLED;

        } else if (CLOCK_AM_PM_STYLE.equals(key)) {
            boolean is24 = DateFormat.is24HourFormat(
                    getContext(), ActivityManager.getCurrentUser());
            mClockAmPmStyle = newValue == null || is24 ?
                    CLOCK_AM_PM_STYLE_NONE : Integer.parseInt(newValue);
            mClockFormatString = ""; // force refresh

        } else if (CLOCK_STYLE.equals(key)) {
            mClockStyle = newValue == null ?
                    CLOCK_STYLE_RIGHT_CLOCK : Integer.parseInt(newValue);

        } else if (CLOCK_DATE_SHOW.equals(key)) {
            mClockDateShow = newValue == null ?
                    CLOCK_DATE_SHOW_NONE : Integer.parseInt(newValue);

        } else if (CLOCK_DATE_STYLE.equals(key)) {
            mClockDateStyle = newValue == null ?
                    CLOCK_DATE_STYLE_NORMAL : Integer.parseInt(newValue);

        } else if (CLOCK_DATE_POSITION.equals(key)) {
            mClockDatePosition = newValue == null ?
                    CLOCK_DATE_POSITION_LEFT : Integer.parseInt(newValue);

        } else if (CLOCK_DATE_FORMAT.equals(key)) {
            mClockDateFormat = newValue == null ?
                    "EEE" : newValue;
        }
        if (mAttached) {
            updateShowClock();
            updateShowSeconds();
            updateClock();
        }
        if (mStatusBarIconController != null) {
            mStatusBarIconController.setClockAndDateStatus(
                    mClockAndDateWidth, mClockStyle, mShowClock);
        }
    }

    private void updateShowSeconds() {
        if (mShowSeconds) {
            // Wait until we have a display to start trying to show seconds.
            if (mSecondsHandler == null && getDisplay() != null) {
                mSecondsHandler = new Handler();
                if (getDisplay().getState() == Display.STATE_ON) {
                    mSecondsHandler.postAtTime(mSecondTick,
                            SystemClock.uptimeMillis() / 1000 * 1000 + 1000);
                }
                IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
                filter.addAction(Intent.ACTION_SCREEN_ON);
                mContext.registerReceiver(mScreenReceiver, filter);
            }
        } else {
            if (mSecondsHandler != null) {
                mContext.unregisterReceiver(mScreenReceiver);
                mSecondsHandler.removeCallbacks(mSecondTick);
                mSecondsHandler = null;
                updateClock();
            }
        }
    }

    protected void updateShowClock() {
        if (mClockStyle == CLOCK_STYLE_RIGHT_CLOCK && mShowClock) {
            setVisibility(View.VISIBLE);
        } else {
            setVisibility(View.GONE);
        }
    }

    private final CharSequence getSmallTime() {
        Context context = getContext();
        boolean is24 = DateFormat.is24HourFormat(context, ActivityManager.getCurrentUser());
        LocaleData d = LocaleData.get(context.getResources().getConfiguration().locale);

        final char MAGIC1 = '\uEF00';
        final char MAGIC2 = '\uEF01';

        SimpleDateFormat sdf;
        String format = mShowSeconds
                ? is24 ? d.timeFormat_Hms : d.timeFormat_hms
                : is24 ? d.timeFormat_Hm : d.timeFormat_hm;
        if (!format.equals(mClockFormatString)) {
            mContentDescriptionFormat = new SimpleDateFormat(format);
            /*
             * Search for an unquoted "a" in the format string, so we can
             * add dummy characters around it to let us find it again after
             * formatting and change its size.
             */
            if (mClockAmPmStyle != CLOCK_AM_PM_STYLE_NORMAL) {
                int a = -1;
                boolean quoted = false;
                for (int i = 0; i < format.length(); i++) {
                    char c = format.charAt(i);

                    if (c == '\'') {
                        quoted = !quoted;
                    }
                    if (!quoted && c == 'a') {
                        a = i;
                        break;
                    }
                }

                if (a >= 0) {
                    // Move a back so any whitespace before AM/PM is also in the alternate size.
                    final int b = a;
                    while (a > 0 && Character.isWhitespace(format.charAt(a-1))) {
                        a--;
                    }
                    format = format.substring(0, a) + MAGIC1 + format.substring(a, b)
                        + "a" + MAGIC2 + format.substring(b + 1);
                }
            }
            mClockFormat = sdf = new SimpleDateFormat(format);
            mClockFormatString = format;
        } else {
            sdf = mClockFormat;
        }

        CharSequence dateString = null;

        String result = "";
        String timeResult = sdf.format(mCalendar.getTime());
        String dateResult = "";

        if (mClockDateShow != CLOCK_DATE_SHOW_NONE) {
            Date now = new Date();

            if (mClockDateFormat == null || mClockDateFormat.isEmpty()) {
                // Set dateString to short uppercase Weekday (Default for AOKP) if empty
                dateString = DateFormat.format("EEE", now);
            } else {
                dateString = DateFormat.format(mClockDateFormat, now);
            }
            if (mClockDateStyle == CLOCK_DATE_STYLE_LOWERCASE) {
                // When Date style is small, convert date to uppercase
                dateResult = dateString.toString().toLowerCase() + result;
            } else if (mClockDateStyle == CLOCK_DATE_STYLE_UPPERCASE) {
                dateResult = dateString.toString().toUpperCase() + result;
            } else {
                dateResult = dateString.toString() + result;
            }
            result = (mClockDatePosition == CLOCK_DATE_POSITION_LEFT) ?
                    dateResult + " " + timeResult : timeResult + " " + dateResult;
        } else {
            // No date, just show time
            result = timeResult;
        }

        SpannableStringBuilder formatted = new SpannableStringBuilder(result);

        if (mClockDateShow != CLOCK_DATE_SHOW_NORMAL) {
            if (dateString != null) {
                int dateStringLen = dateString.length();
                int timeStringOffset =
                        (mClockDatePosition == CLOCK_DATE_POSITION_RIGHT) ?
                        timeResult.length() + 1 : 0;
                if (mClockDateShow == CLOCK_DATE_SHOW_NONE) {
                    formatted.delete(0, dateStringLen);
                } else {
                    if (mClockDateShow == CLOCK_DATE_SHOW_SMALL) {
                        CharacterStyle style = new RelativeSizeSpan(0.7f);
                        formatted.setSpan(style, timeStringOffset,
                                          timeStringOffset + dateStringLen,
                                          Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
                    }
                }
            }
        }

        if (mClockAmPmStyle != CLOCK_AM_PM_STYLE_NORMAL) {
            int magic1 = result.indexOf(MAGIC1);
            int magic2 = result.indexOf(MAGIC2);
            if (magic1 >= 0 && magic2 > magic1) {
                if (mClockAmPmStyle == CLOCK_AM_PM_STYLE_NONE) {
                    formatted.delete(magic1, magic2+1);
                } else {
                    if (mClockAmPmStyle == CLOCK_AM_PM_STYLE_SMALL) {
                        CharacterStyle style = new RelativeSizeSpan(0.7f);
                        formatted.setSpan(style, magic1, magic2,
                                          Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
                    }
                    formatted.delete(magic2, magic2 + 1);
                    formatted.delete(magic1, magic1 + 1);
                }
            }
        }
        return formatted;
    }

    private boolean mDemoMode;

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        if (!mDemoMode && command.equals(COMMAND_ENTER)) {
            mDemoMode = true;
        } else if (mDemoMode && command.equals(COMMAND_EXIT)) {
            mDemoMode = false;
            updateClock();
        } else if (mDemoMode && command.equals(COMMAND_CLOCK)) {
            String millis = args.getString("millis");
            String hhmm = args.getString("hhmm");
            if (millis != null) {
                mCalendar.setTimeInMillis(Long.parseLong(millis));
            } else if (hhmm != null && hhmm.length() == 4) {
                int hh = Integer.parseInt(hhmm.substring(0, 2));
                int mm = Integer.parseInt(hhmm.substring(2));
                boolean is24 = DateFormat.is24HourFormat(
                        getContext(), ActivityManager.getCurrentUser());
                if (is24) {
                    mCalendar.set(Calendar.HOUR_OF_DAY, hh);
                } else {
                    mCalendar.set(Calendar.HOUR, hh);
                }
                mCalendar.set(Calendar.MINUTE, mm);
            }
            setText(getSmallTime());
            setContentDescription(mContentDescriptionFormat.format(mCalendar.getTime()));
        }
    }

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                if (mSecondsHandler != null) {
                    mSecondsHandler.removeCallbacks(mSecondTick);
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                if (mSecondsHandler != null) {
                    mSecondsHandler.postAtTime(mSecondTick,
                            SystemClock.uptimeMillis() / 1000 * 1000 + 1000);
                }
            }
        }
    };

    private final Runnable mSecondTick = new Runnable() {
        @Override
        public void run() {
            if (mCalendar != null) {
                updateClock();
            }
            mSecondsHandler.postAtTime(this, SystemClock.uptimeMillis() / 1000 * 1000 + 1000);
        }
    };

    public void setStatusBarIconController(StatusBarIconController statusBarIconController) {
        mStatusBarIconController = statusBarIconController;
    }

    @Override
    protected void onSizeChanged(int xNew, int yNew, int xOld, int yOld){
        super.onSizeChanged(xNew, yNew, xOld, yOld);
        mClockAndDateWidth = xNew;
        if (mStatusBarIconController != null) {
            mStatusBarIconController.setClockAndDateStatus(
                    mClockAndDateWidth, mClockStyle, mShowClock);
        }
    }
}

