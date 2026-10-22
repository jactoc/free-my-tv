package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** System notices for the main process: the TV booted, or this app was updated. Not exported. */
public class HomeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            HomeMode.bootCheck(context);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // App updated: complete Home TV mode if something is missing (the full check only when it looks so).
            if (!HomeMode.looksIntact(context)) HomeMode.verify(context);
            HomeMode.ensureRemoteService(context, 3_000);
            // It updated itself (Updates) from the home screen: Android brought back the app that was open before it.
            if (Updates.justInstalled(context)) {
                try {
                    context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException e) {
                    AppLog.w("back to the home screen: " + e.getMessage());
                }
            }
        }
    }
}
