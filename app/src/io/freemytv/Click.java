package io.freemytv;

import android.content.Context;
import android.media.AudioManager;

/**
 * Sound for the home screen (moving focus and opening): the system's own UI sounds, the same ones Settings plays, so
 * the home screen matches the rest of the TV and follows its "sound effects" setting. It only plays on the home
 * screen, never inside apps.
 */
final class Click {
    private AudioManager audio;

    Click(Context c) {
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) AppLog.w("system sounds unavailable");
    }

    void move() {
        if (audio != null && Prefs.sounds()) audio.playSoundEffect(AudioManager.FX_FOCUS_NAVIGATION_RIGHT);
    }

    void open() {
        if (audio != null && Prefs.sounds()) audio.playSoundEffect(AudioManager.FX_KEY_CLICK);
    }

    void release() {
        audio = null;
    }
}
