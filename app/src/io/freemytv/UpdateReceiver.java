package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Android's answer about an update (Updates.install): its confirmation to show, or how the install ended. */
public class UpdateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @SuppressWarnings("deprecation") Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                try {
                    c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                } catch (RuntimeException e) {
                    AppLog.w("update: couldn't show the confirmation: " + e.getMessage());
                }
            }
            Updates.finished(PackageInstaller.STATUS_FAILURE, "no confirmation screen");
            return;
        }
        Updates.finished(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
    }
}
