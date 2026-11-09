package it.misericordiaadrano.monitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

/**
 * Monitor Trasporti – Misericordia di Adrano
 * Apre la pagina del monitor a schermo pieno e la ricarica in caso di errore.
 * Menu nascosto: premere 5 volte INDIETRO (o il tasto MENU del telecomando).
 * Standby programmato: alle ore impostate spegne lo schermo e lo riaccende al mattino.
 */
public class MainActivity extends Activity {

    // Indirizzo proposto al primo avvio (modificabile dal menu nascosto)
    private static final String URL_PREDEFINITO = "https://www.TUODOMINIO.it/monitorTrasporti.php";
    private static final long RIPROVA_MS = 30_000;

    private WebView web;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int pressioniIndietro = 0;
    private long primaPressione = 0;
    private boolean inStandby = false;
    private String azioneInAttesa = null;   // SPEGNI / ACCENDI arrivata dalla sveglia

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        // il monitor si mostra anche sopra la schermata di blocco (serve per la riaccensione del mattino)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true);

        prefs = getSharedPreferences("config", MODE_PRIVATE);

        web = new WebView(this);
        setContentView(web);
        configuraWebView();
        schermoPieno();

        if (prefs.getString("url", null) == null) {
            chiediUrl(true);
        } else {
            web.loadUrl(prefs.getString("url", URL_PREDEFINITO));
        }
        Standby.programma(this);
        azioneInAttesa = getIntent().getStringExtra(Standby.EXTRA_AZIONE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        azioneInAttesa = intent.getStringExtra(Standby.EXTRA_AZIONE);
    }

    // ---------- Standby ----------

    private DevicePolicyManager dpm() {
        return (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
    }

    private boolean puoSpegnere() {
        DevicePolicyManager d = dpm();
        return d != null && d.isAdminActive(Standby.admin(this));
    }

    /** spegne lo schermo: ferma la pagina (niente suoni) e blocca il box */
    private void entraStandby() {
        inStandby = true;
        handler.removeCallbacksAndMessages(null);
        web.loadUrl("about:blank");
        web.setBackgroundColor(0xFF000000);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) setTurnScreenOn(false);
        if (puoSpegnere()) {
            try { dpm().lockNow(); return; } catch (SecurityException ignored) { }
        }
        // senza permesso: schermo nero e luminosità al minimo (lo spegne la TV o il risparmio energetico)
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.01f;
        getWindow().setAttributes(lp);
        Toast.makeText(this, "Standby: premi un tasto per riaccendere", Toast.LENGTH_LONG).show();
    }

    /** riaccende lo schermo e riapre il monitor */
    @SuppressWarnings("deprecation")
    private void esciStandby() {
        inStandby = false;
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        if (Build.VERSION.SDK_INT >= 26) {
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null) km.requestDismissKeyguard(this, null);
        }
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        schermoPieno();
        web.loadUrl(prefs.getString("url", URL_PREDEFINITO));
    }

    private void configuraWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        // disegno con la scheda grafica: pagina più fluida sui box poco potenti
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        // Fondamentale: consente ai suoni di avviso di partire senza toccare lo schermo
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(s.getUserAgentString() + " MonitorTrasportiBox/1.0");

        CookieManager.getInstance().setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        }

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) mostraErroreERiprova();
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int code, String desc, String url) {
                mostraErroreERiprova();
            }
        });
    }

    private void mostraErroreERiprova() {
        web.loadData("<html><body style='background:#111;color:#eee;font-family:sans-serif;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0'>"
                + "<div style='text-align:center'><h1>Connessione non disponibile</h1>"
                + "<p>Nuovo tentativo tra 30 secondi…</p></div></body></html>", "text/html", "UTF-8");
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> web.loadUrl(prefs.getString("url", URL_PREDEFINITO)), RIPROVA_MS);
    }

    private void schermoPieno() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) schermoPieno();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        schermoPieno();
        String a = azioneInAttesa;
        azioneInAttesa = null;
        if (Standby.AZIONE_SPEGNI.equals(a)) {
            if (!inStandby) entraStandby();
        } else if (inStandby) {
            // sveglia del mattino oppure box riacceso a mano con il telecomando
            esciStandby();
        }
    }

    // ---------- Menu nascosto ----------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (inStandby) {           // in standby (schermo nero) qualsiasi tasto lo riaccende
            esciStandby();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            mostraMenu();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            long ora = System.currentTimeMillis();
            if (ora - primaPressione > 3000) {
                primaPressione = ora;
                pressioniIndietro = 0;
            }
            if (++pressioniIndietro >= 5) {
                pressioniIndietro = 0;
                mostraMenu();
            }
            return true; // INDIETRO non chiude il monitor
        }
        return super.onKeyDown(keyCode, event);
    }

    private void mostraMenu() {
        String stato = Standby.attivo(this)
                ? "attivo " + Standby.oraSpegni(this) + " → " + Standby.oraAccendi(this)
                : "disattivato";
        String[] voci = {"Ricarica pagina", "Cambia indirizzo", "Permesso avvio automatico",
                "Standby adesso", "Orari standby (" + stato + ")",
                Standby.attivo(this) ? "Disattiva standby automatico" : "Attiva standby automatico",
                "Permesso spegnimento schermo" + (puoSpegnere() ? " (già dato)" : ""), "Esci"};
        new AlertDialog.Builder(this)
                .setTitle("Monitor Trasporti")
                .setItems(voci, (d, i) -> {
                    switch (i) {
                        case 0: web.reload(); break;
                        case 1: chiediUrl(false); break;
                        case 2: apriPermessoOverlay(); break;
                        case 3: entraStandby(); break;
                        case 4: chiediOrari(); break;
                        case 5:
                            prefs.edit().putBoolean(Standby.P_ATTIVO, !Standby.attivo(this)).apply();
                            Standby.programma(this);
                            Toast.makeText(this, Standby.attivo(this) ? "Standby automatico attivo" : "Standby automatico disattivato",
                                    Toast.LENGTH_SHORT).show();
                            break;
                        case 6: chiediPermessoSpegnimento(); break;
                        case 7: finishAffinity(); break;
                    }
                })
                .show();
    }

    private void chiediOrari() {
        final EditText spegni = new EditText(this);
        spegni.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        spegni.setHint("Spegni alle (es. 21:30)");
        spegni.setText(Standby.oraSpegni(this));
        final EditText accendi = new EditText(this);
        accendi.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        accendi.setHint("Riaccendi alle (es. 06:30)");
        accendi.setText(Standby.oraAccendi(this));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(spegni);
        box.addView(accendi);
        new AlertDialog.Builder(this)
                .setTitle("Orari standby: spegni / riaccendi")
                .setView(box)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Salva", (d, w) -> {
                    String a = spegni.getText().toString().trim().replace('.', ':');
                    String b = accendi.getText().toString().trim().replace('.', ':');
                    if (!Standby.oraValida(a) || !Standby.oraValida(b)) {
                        Toast.makeText(this, "Orari non validi: scrivi ad esempio 21:30 e 06:30", Toast.LENGTH_LONG).show();
                        return;
                    }
                    prefs.edit().putString(Standby.P_SPEGNI, Standby.normalizza(a))
                            .putString(Standby.P_ACCENDI, Standby.normalizza(b))
                            .putBoolean(Standby.P_ATTIVO, true).apply();
                    Standby.programma(this);
                    Toast.makeText(this, "Standby: spegne alle " + Standby.oraSpegni(this)
                            + ", riaccende alle " + Standby.oraAccendi(this), Toast.LENGTH_LONG).show();
                })
                .show();
    }

    private void chiediPermessoSpegnimento() {
        if (puoSpegnere()) {
            Toast.makeText(this, "Permesso già dato: lo standby spegne lo schermo", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Standby.admin(this));
            i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Serve solo per spegnere lo schermo del monitor durante la notte.");
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Questo box non lo consente: in standby lo schermo resterà nero",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void chiediUrl(boolean primoAvvio) {
        final EditText campo = new EditText(this);
        campo.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        campo.setText(prefs.getString("url", URL_PREDEFINITO));
        campo.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Indirizzo del monitor")
                .setView(campo)
                .setCancelable(!primoAvvio)
                .setPositiveButton("Salva", (d, w) -> {
                    String url = campo.getText().toString().trim();
                    if (!url.startsWith("http")) url = "https://" + url;
                    prefs.edit().putString("url", url).apply();
                    web.loadUrl(url);
                    if (primoAvvio) apriPermessoOverlay();
                })
                .show();
    }

    private void apriPermessoOverlay() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Attiva \"Mostra sopra altre app\" per l'avvio automatico",
                    Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Toast.makeText(this, "Impostazione non disponibile su questo box: "
                        + "imposta l'app come schermata Home", Toast.LENGTH_LONG).show();
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            Toast.makeText(this, "Avvio automatico già autorizzato", Toast.LENGTH_SHORT).show();
        }
    }
}
