package com.example.devicefinder;

import android.content.Context;
import android.content.Intent;

/** Default-off user feature gate; deliberately separate from LAN chat transport. */
public final class LanTransferFeature {
    private static final String PREFS = "device_finder_prefs";
    private static final String KEY_ENABLED = "lan_file_transfer_enabled";
    private LanTransferFeature() {}

    public static boolean isEnabled(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    public static boolean setEnabled(Context context, boolean enabled) {
        Context app = context.getApplicationContext();
        boolean saved = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).commit();
        if (!saved) return false;
        try {
            app.startService(new Intent(app, TrackingService.class)
                    .setAction("ACTION_LAN_TRANSFER_FEATURE_TOGGLED"));
        } catch (Exception failure) {
            FileLogger.log(app, "LAN_TRANSFER", "Feature toggle saved; listener refresh deferred: "
                    + failure.getClass().getSimpleName());
        }
        return true;
    }
}
