package com.nill.superproxy;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class ProxyTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    private void updateTile() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(NillVpnService.running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel("Super Proxy");
        t.updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (NillVpnService.running) {
            NillVpnService.stopNow(this);
        } else {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(i);
        }
        updateTile();
    }
}
