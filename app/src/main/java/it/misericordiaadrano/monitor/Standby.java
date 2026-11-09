package it.misericordiaadrano.monitor;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Calendar;

/**
 * Standby programmato del box: a un'ora spegne lo schermo, a un'altra lo riaccende.
 * Gli orari si cambiano dal menu nascosto (5 volte INDIETRO).
 */
public final class Standby {

    public static final String AZIONE_SPEGNI = "it.misericordiaadrano.monitor.SPEGNI";
    public static final String AZIONE_ACCENDI = "it.misericordiaadrano.monitor.ACCENDI";
    public static final String EXTRA_AZIONE = "azione";

    static final String P_ATTIVO = "standby_attivo";
    static final String P_SPEGNI = "standby_spegni";
    static final String P_ACCENDI = "standby_accendi";
    static final String ORA_SPEGNI = "21:30";
    static final String ORA_ACCENDI = "06:30";

    private Standby() { }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("config", Context.MODE_PRIVATE);
    }

    static boolean attivo(Context c) { return prefs(c).getBoolean(P_ATTIVO, true); }
    static String oraSpegni(Context c) { return prefs(c).getString(P_SPEGNI, ORA_SPEGNI); }
    static String oraAccendi(Context c) { return prefs(c).getString(P_ACCENDI, ORA_ACCENDI); }

    static ComponentName admin(Context c) { return new ComponentName(c, AdminReceiver.class); }

    /** "HH:MM" valida? */
    static boolean oraValida(String s) {
        if (s == null || !s.matches("\\d{1,2}:\\d{2}")) return false;
        String[] p = s.split(":");
        int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
        return h >= 0 && h <= 23 && m >= 0 && m <= 59;
    }

    static String normalizza(String s) {
        String[] p = s.split(":");
        return String.format(java.util.Locale.ITALY, "%02d:%02d", Integer.parseInt(p[0]), Integer.parseInt(p[1]));
    }

    /** prossimo istante (in millisecondi) in cui l'orologio segna "HH:MM" */
    static long prossimo(String hhmm) {
        String[] p = hhmm.split(":");
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(p[0]));
        c.set(Calendar.MINUTE, Integer.parseInt(p[1]));
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis() + 1000) c.add(Calendar.DAY_OF_YEAR, 1);
        return c.getTimeInMillis();
    }

    private static PendingIntent pi(Context c, String azione, int codice) {
        Intent i = new Intent(c, StandbyReceiver.class).setAction(azione);
        int flag = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flag |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, codice, i, flag);
    }

    /** (ri)programma lo spegnimento e la riaccensione; se il standby è disattivato li cancella */
    static void programma(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent spegni = pi(c, AZIONE_SPEGNI, 1);
        PendingIntent accendi = pi(c, AZIONE_ACCENDI, 2);
        am.cancel(spegni);
        am.cancel(accendi);
        if (!attivo(c)) return;
        sveglia(am, prossimo(oraSpegni(c)), spegni);
        sveglia(am, prossimo(oraAccendi(c)), accendi);
    }

    private static void sveglia(AlarmManager am, long quando, PendingIntent p) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, p);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, p);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, quando, p);
            }
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, quando, p);
        }
    }
}
