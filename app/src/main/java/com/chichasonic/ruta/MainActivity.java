package com.chichasonic.ruta;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.os.*;
import android.view.View;
import android.widget.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Panel nativo del vendedor, independiente del navegador web. */
public class MainActivity extends Activity {
    private static final int REQ_LOCATION = 1001;
    private static final int REQ_NOTIFICATION = 1002;
    private EditText email, password;
    private Button login, start, stop, logout, testConnection;
    private TextView state, status, driver;
    private View loginBox, controlBox;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statusRefresh = new Runnable() {
        @Override public void run() { refreshUi(); handler.postDelayed(this, 3000L); }
    };

    @Override public void onCreate(Bundle stateBundle) {
        super.onCreate(stateBundle);
        setContentView(R.layout.activity_main);
        email = findViewById(R.id.email); password = findViewById(R.id.password);
        login = findViewById(R.id.loginButton); start = findViewById(R.id.startButton);
        stop = findViewById(R.id.stopButton); logout = findViewById(R.id.logoutButton);
        testConnection = findViewById(R.id.testConnectionButton);
        state = findViewById(R.id.stateLabel); status = findViewById(R.id.statusText);
        driver = findViewById(R.id.driverLabel);
        loginBox = findViewById(R.id.loginBox); controlBox = findViewById(R.id.controlBox);
        email.setText("chichasoniconline@gmail.com");
        login.setOnClickListener(v -> login());
        start.setOnClickListener(v -> prepareStart());
        stop.setOnClickListener(v -> sendStop());
        logout.setOnClickListener(v -> logout());
        testConnection.setOnClickListener(v -> checkConnection());
        findViewById(R.id.batteryButton).setOnClickListener(v -> {
            Intent settings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
            startActivity(settings);
        });
        findViewById(R.id.publicButton).setOnClickListener(v ->
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SonicApi.MAP_URL))));
        refreshUi();
    }

    private void login() {
        String e = email.getText().toString().trim();
        String p = password.getText().toString();
        if (e.isEmpty() || p.isEmpty()) { status.setText("Introduce correo y contraseña."); return; }
        login.setEnabled(false);
        status.setText("Conectando y verificando permiso de vendedor…");
        worker.execute(() -> {
            try {
                SonicApi.login(getApplicationContext(), e, p);
                runOnUiThread(() -> { password.setText(""); status.setText("Vendedor autorizado. Listo para comenzar."); refreshUi(); });
            } catch (Exception err) {
                runOnUiThread(() -> status.setText("No se pudo entrar: " + err.getMessage()));
            } finally { runOnUiThread(() -> login.setEnabled(true)); }
        });
    }
    private void checkConnection() {
        if (!SonicApi.hasSession(this)) { status.setText("Inicia sesión primero."); return; }
        testConnection.setEnabled(false);
        status.setText("Comprobando autorización y Supabase…");
        worker.execute(() -> {
            try {
                String reply = SonicApi.checkConnection(getApplicationContext());
                runOnUiThread(() -> status.setText("✓ " + reply));
            } catch (Exception err) {
                runOnUiThread(() -> status.setText("Error de conexión: " + err.getMessage()));
            } finally {
                runOnUiThread(() -> testConnection.setEnabled(true));
            }
        });
    }
    private void prepareStart() {
        if (TrackingService.running) return;
        if (!SonicApi.hasSession(this)) { status.setText("Primero inicia sesión."); return; }
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Android puede ejecutar el servicio sin este permiso, pero se solicita
            // para que el usuario vea claramente que su ubicación se comparte.
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
            return;
        }
        begin();
    }
    private void begin() {
        try {
            startForegroundService(new Intent(this, TrackingService.class).setAction(TrackingService.START));
            status.setText("Iniciando GPS. Comprueba que aparezca la notificación de Chicha Sonic.");
        } catch (Exception e) { status.setText("Android no permitió iniciar GPS: " + e.getMessage()); }
        refreshUi();
    }
    private void sendStop() {
        startService(new Intent(this, TrackingService.class).setAction(TrackingService.STOP));
        status.setText("Deteniendo transmisión y ocultando la ubicación…");
        handler.postDelayed(this::refreshUi, 1200L);
    }
    private void logout() {
        if (TrackingService.running || TrackingService.stopping) { status.setText("Termina el recorrido antes de cerrar sesión."); return; }
        SonicApi.logout(this); refreshUi(); status.setText("Sesión cerrada.");
    }
    @Override public void onRequestPermissionsResult(int req, String[] permissions, int[] grant) {
        super.onRequestPermissionsResult(req, permissions, grant);
        if (req == REQ_LOCATION) {
            boolean ok = false;
            for (int value : grant) if (value == PackageManager.PERMISSION_GRANTED) ok = true;
            if (ok) prepareStart();
            else status.setText("Debes permitir la ubicación para enviar el GPS.");
        } else if (req == REQ_NOTIFICATION) {
            // Si se denegaron notificaciones, la app seguirá indicando el servicio en Android.
            begin();
        }
    }
    private void refreshUi() {
        boolean signed = SonicApi.hasSession(this);
        boolean active = TrackingService.running && !TrackingService.stopping;
        loginBox.setVisibility(signed ? View.GONE : View.VISIBLE);
        controlBox.setVisibility(signed ? View.VISIBLE : View.GONE);
        driver.setText(signed ? SonicApi.loggedEmail(this) : "Vendedor autorizado");
        state.setText(active ? "GPS en recorrido · pantalla bloqueable" : "Recorrido detenido");
        start.setEnabled(!active);
        stop.setEnabled(active);
        logout.setEnabled(!active);
        testConnection.setEnabled(!active);
        if (signed) {
            String msg = getSharedPreferences("chicha_gps_status", MODE_PRIVATE).getString("message", "Listo para comenzar.");
            long last = getSharedPreferences("chicha_gps_status", MODE_PRIVATE).getLong("gpsMs", 0L);
            if (active && last > 0L) {
                long seconds = Math.max(0L, (System.currentTimeMillis() - last) / 1000L);
                if (seconds > 45L) msg += "\n⚠ Última transmisión confirmada hace " + seconds + " s.";
            }
            status.setText(msg);
        }
    }
    @Override protected void onResume() { super.onResume(); handler.post(statusRefresh); }
    @Override protected void onPause() { handler.removeCallbacks(statusRefresh); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacks(statusRefresh); worker.shutdown(); super.onDestroy(); }
}
