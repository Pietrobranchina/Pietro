package it.misericordiaadrano.monitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Apre il monitor all'accensione del box. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Standby.programma(context);   // riprogramma lo standby dopo l'accensione
        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(i);
    }
}
