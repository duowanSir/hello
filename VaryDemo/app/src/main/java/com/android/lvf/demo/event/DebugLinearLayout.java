package com.android.lvf.demo.event;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.LinearLayout;

import com.android.lvf.LLog;

/**
 * Created by slowergun on 2016/12/8.
 */
public class DebugLinearLayout extends LinearLayout {
    public static final String TAG = DebugLinearLayout.class.getSimpleName();

    public DebugLinearLayout(Context context) {
        this(context, null, 0);
    }

    public DebugLinearLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DebugLinearLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        boolean result = super.dispatchTouchEvent(ev);
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                LLog.d(TAG, "dispatchTouchEvent(down, " + result + ")");
                break;
            case MotionEvent.ACTION_MOVE:
                break;
            case MotionEvent.ACTION_UP:
                LLog.d(TAG, "dispatchTouchEvent(up, " + result + ")");
                break;
        }
        return result;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean result = super.onTouchEvent(ev);
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN:
                LLog.d(TAG, "onTouchEvent(down, " + result + ")");
                break;
            case MotionEvent.ACTION_MOVE:
                break;
            case MotionEvent.ACTION_UP:
                LLog.d(TAG, "onTouchEvent(up, " + result + ")");
                break;
        }
        return result;
    }
}
