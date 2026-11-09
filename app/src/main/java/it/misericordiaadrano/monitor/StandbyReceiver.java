package it.misericordiaadrano.monitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/** Riceve le sveglie dello standby: spegne o riaccende lo schermo tramite il monitor. */
public class StandbyReceiver extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        String azione = intent.getAction();
        if (Standby.AZIONE_ACCENDI.equals(azione)) {
            // accende lo schermo (sui box che lo consentono) prima di riaprire il monitor
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "monitor:accendi");
                wl.acquire(15_000);
            }
        }
        if (Standby.AZIONE_SPEGNI.equals(azione) || Standby.AZIONE_ACCENDI.equals(azione)) {
            Intent i = new Intent(context, MainActivity.class);
            i.putExtra(Standby.EXTRA_AZIONE, azione);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(i);
        }
        // programma la prossima volta (domani)
        Standby.programma(context);
    }
}
