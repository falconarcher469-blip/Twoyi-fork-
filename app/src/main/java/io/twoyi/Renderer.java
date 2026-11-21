/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi;

import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;

/**
 * @author weishu
 * @date 2021/10/20.
 */
public class Renderer {

    private static final String TAG = "Renderer";
    private static volatile boolean sLoaded = false;

    static {
        try {
            System.loadLibrary("twoyi");
            sLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "libtwoyi.so not loaded", e);
        }
    }

    public static boolean isLoaded() {
        return sLoaded;
    }

    public static void safeInit(Surface surface, String loader, String dataDir, float xdpi, float ydpi, int fps) {
        if (sLoaded) {
            init(surface, loader, dataDir, xdpi, ydpi, fps);
        }
    }

    public static void safeResetWindow(Surface surface, int top, int left, int width, int height) {
        if (sLoaded) {
            resetWindow(surface, top, left, width, height);
        }
    }

    public static void safeRemoveWindow(Surface surface) {
        if (sLoaded) {
            removeWindow(surface);
        }
    }

    public static void safeHandleTouch(MotionEvent event) {
        if (sLoaded) {
            handleTouch(event);
        }
    }

    public static void safeSendKeycode(int keycode) {
        if (sLoaded) {
            sendKeycode(keycode);
        }
    }

    public static native void init(Surface surface, String loader, String dataDir, float xdpi, float ydpi, int fps);

    public static native void resetWindow(Surface surface, int top, int left, int width, int height);

    public static native void removeWindow(Surface surface);

    public static native void handleTouch(MotionEvent event);

    public static native void sendKeycode(int keycode);
}
