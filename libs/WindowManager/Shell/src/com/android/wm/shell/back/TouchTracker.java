/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.back;

import android.annotation.FloatRange;
import android.os.SystemProperties;
import android.util.MathUtils;
import android.view.MotionEvent;
import android.view.RemoteAnimationTarget;
import android.window.BackEvent;
import android.window.BackMotionEvent;

/**
 * Helper class to record the touch location for gesture and generate back events.
 */
class TouchTracker {
    private static final String PREDICTIVE_BACK_PROGRESS_THRESHOLD_PROP =
            "persist.wm.debug.predictive_back_progress_threshold";
    private static final int PROGRESS_THRESHOLD = SystemProperties
            .getInt(PREDICTIVE_BACK_PROGRESS_THRESHOLD_PROP, -1);
    private float mProgressThreshold;
    /**
     * Location of the latest touch event
     */
    private float mLatestTouchX;
    private float mLatestTouchY;
    private boolean mTriggerBack;

    /**
     * Location of the initial touch event of the back gesture.
     */
    private float mInitTouchX;
    private float mInitTouchY;
    private float mLatestVelocityX;
    private float mLatestVelocityY;
    private float mStartThresholdX;
    private int mSwipeEdge;
    private boolean mCancelled;

    void update(float touchX, float touchY, float velocityX, float velocityY) {
        /**
         * If back was previously cancelled but the user has started swiping in the forward
         * direction again, restart back.
         */
        if (mCancelled && ((touchX > mLatestTouchX && mSwipeEdge == BackEvent.EDGE_LEFT)
                || touchX < mLatestTouchX && mSwipeEdge == BackEvent.EDGE_RIGHT)) {
            mCancelled = false;
            mStartThresholdX = touchX;
        }
        mLatestTouchX = touchX;
        mLatestTouchY = touchY;
        mLatestVelocityX = velocityX;
        mLatestVelocityY = velocityY;
    }

    void setTriggerBack(boolean triggerBack) {
        if (mTriggerBack != triggerBack && !triggerBack) {
            mCancelled = true;
        }
        mTriggerBack = triggerBack;
    }

    void setGestureStartLocation(float touchX, float touchY, int swipeEdge) {
        mInitTouchX = touchX;
        mInitTouchY = touchY;
        mSwipeEdge = swipeEdge;
        mStartThresholdX = mInitTouchX;
    }

    void reset() {
        mInitTouchX = 0;
        mInitTouchY = 0;
        mStartThresholdX = 0;
        mCancelled = false;
        mTriggerBack = false;
        mSwipeEdge = BackEvent.EDGE_LEFT;
    }

    BackMotionEvent createStartEvent(RemoteAnimationTarget target) {
        return new BackMotionEvent(
                /* touchX = */ mInitTouchX,
                /* touchY = */ mInitTouchY,
                /* progress = */ 0,
                /* velocityX = */ 0,
                /* velocityY = */ 0,
                /* swipeEdge = */ mSwipeEdge,
                /* departingAnimationTarget = */ target);
    }

    BackMotionEvent createProgressEvent() {
        float progress = 0;
        // Progress is always 0 when back is cancelled and not restarted.
        if (!mCancelled) {
            progress = getProgress(mLatestTouchX);
        }
        return createProgressEvent(progress);
    }

    /**
     * Progress value computed from the touch position.
     *
     * @param touchX the X touch position of the {@link MotionEvent}.
     * @return progress value
     */
    @FloatRange(from = 0.0, to = 1.0)
    float getProgress(float touchX) {
        // If back is committed, progress is the distance between the last and first touch
        // point, divided by the max drag distance. Otherwise, it's the distance between
        // the last touch point and the starting threshold, divided by max drag distance.
        // The starting threshold is initially the first touch location, and updated to
        // the location everytime back is restarted after being cancelled.
        float startX = mTriggerBack ? mInitTouchX : mStartThresholdX;
        float deltaX = Math.abs(startX - touchX);
        float maxX = getMaxX();
        maxX = maxX == 0 ? 1 : maxX;
        return MathUtils.constrain(deltaX / maxX, 0, 1);
    }

    /**
     * Maximum X value (in pixels).
     * Progress is considered to be completed (1f) when this limit is exceeded.
     */
    float getMaxX() {
        return PROGRESS_THRESHOLD >= 0 ? PROGRESS_THRESHOLD : mProgressThreshold;
    }

    BackMotionEvent createProgressEvent(float progress) {
        return new BackMotionEvent(
                /* touchX = */ mLatestTouchX,
                /* touchY = */ mLatestTouchY,
                /* progress = */ progress,
                /* velocityX = */ mLatestVelocityX,
                /* velocityY = */ mLatestVelocityY,
                /* swipeEdge = */ mSwipeEdge,
                /* departingAnimationTarget = */ null);
    }

    public void setProgressThreshold(float progressThreshold) {
        mProgressThreshold = progressThreshold;
    }
}
