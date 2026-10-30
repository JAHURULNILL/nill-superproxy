package com.nill.superproxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Store store = new Store(context);
        if (!store.autoBoot()) return;
        String pid = store.lastProfileId();
        if (pid == null || store.profileById(pid) == null) return;
        // VPN consent persists across reboots once granted; if it was revoked, prepare() says so.
        if (VpnService.prepare(context) != null) return;
        NillVpnService.startWith(context, pid);
    }
}
