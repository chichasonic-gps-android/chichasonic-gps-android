package com.chichasonic.ruta;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.*;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Servicio de primer plano: continúa cuando Android apaga la pantalla. */
public class TrackingService extends Service implements LocationListener {
    static final String START = "com.chichasonic.ruta.START";
    static final String STOP = "com.chichasonic.ruta.STOP";
    static final String CHANNEL = "chichasonic_ruta_gps";
    static final int NOTIFICATION = 301;
    static volatile boolean running = false;
    static volatile boolean stopping = false;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean posting = new AtomicBoolean(false);
    private LocationManager locationManager;
    private Location latest;
    private long lastLocationElapsedMs = 0;
    private long lastPublishedMs = 0;
    private long startedElapsedMs = 0;
    // PARTIAL_WAKE_LOCK mantiene la CPU activa con la pantalla apagada; no enciende la pantalla.
    // Android/OEM pueden restringir la red; no garantiza envíos continuos en todos los móviles.
    private PowerManager.WakeLock cpuWakeLock;
    private static final long CPU_LOCK_TIMEOUT_MS = 20 * 60 * 1000L;
    private static final long CPU_LOCK_RENEW_MS = 10 * 60 * 1000L;
    private final Runnable renewCpuWakeLock = new Runnable() {
        @Override public void run() {
            if (!running || stopping) return;
            // Renovación con plazo finito y liberación explícita al terminar el recorrido.
            try {
                if (cpuWakeLock != null) {
                    if (cpuWakeLock.isHeld()) cpuWakeLock.release();
                    cpuWakeLock.acquire(CPU_LOCK_TIMEOUT_MS);
                }
            } catch (Exception ignored) {
                status("GPS activo, pero Android no permitió mantener la CPU despierta.");
            }
            handler.postDelayed(this, CPU_LOCK_RENEW_MS);
        }
    };

    private void keepCpuAwake() {
        try {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power != null) {
                cpuWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChichaSonic:RutaGPS");
                cpuWakeLock.setReferenceCounted(false);
                cpuWakeLock.acquire(CPU_LOCK_TIMEOUT_MS);
                handler.postDelayed(renewCpuWakeLock, CPU_LOCK_RENEW_MS);
            }
        } catch (Exception e) {
            status("GPS activo, revisa ahorro de batería del teléfono.");
        }
    }
    private void releaseCpuLock() {
        if (handler != null) handler.removeCallbacks(renewCpuWakeLock);
        if (cpuWakeLock != null) {
            try { if (cpuWakeLock.isHeld()) cpuWakeLock.release(); } catch (Exception ignored) {}
            cpuWakeLock = null;
        }
    }
    private Handler handler;
    private final Runnable healthCheck = new Runnable() {
        @Override public void run() {
            if (!running || stopping) return;
            long lastConfirmed = statusPrefs().getLong("gpsMs", 0);
            long age = lastConfirmed == 0
                    ? android.os.SystemClock.elapsedRealtime() - startedElapsedMs
                    : System.currentTimeMillis() - lastConfirmed;
            if (age > 45000L) {
                notifyState("Sin envío reciente · revisa GPS e internet");
                status("Sin señal confirmada reciente. Verifica GPS, datos y batería.");
            }
            // Si la red regresó, reintentar SOLO una coordenada muy reciente.
            // NUNCA refrescar una coordenada antigua como si fuera nueva.
            if (latest != null && android.os.SystemClock.elapsedRealtime() - lastLocationElapsedMs < 15000L
                    && System.currentTimeMillis() - lastPublishedMs >= 16000L) {
                tryPublish(latest);
            }
            handler.postDelayed(this, 15000L);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Chicha Sonic: ubicación compartida", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Notificación visible mientras el vendedor comparte la ubicación de la moto");
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) { stopRoute(); return START_NOT_STICKY; }
        if (!running && !stopping) startRoute();
        return START_NOT_STICKY; // No inicia solo tras reiniciar el teléfono.
    }

    private android.content.SharedPreferences statusPrefs() { return getSharedPreferences("chicha_gps_status", MODE_PRIVATE); }

    private Notification makeNotification(String detail) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, TrackingService.class).setAction(STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
            .setContentTitle("Chicha Sonic · GPS activo")
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "Terminar recorrido", stopPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    }
    private void notifyState(String detail) {
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION, makeNotification(detail));
    }
    private void status(String text) { statusPrefs().edit().putString("message", text).apply(); }

    private void startRoute() {
        if (!SonicApi.hasSession(this)) { status("Sin sesión, inicia sesión en el panel."); stopSelf(); return; }
        stopping = false;
        int fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION);
        int coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            status("Ubicación no autorizada. Vuelve al panel."); stopSelf(); return;
        }
        try {
            // Inicio con la aplicación visible, requisito de Android 14+.
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, makeNotification("Conectando GPS…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(NOTIFICATION, makeNotification("Conectando GPS…"));
            running = true;
            keepCpuAwake();
            startedElapsedMs = android.os.SystemClock.elapsedRealtime();
            statusPrefs().edit().putBoolean("tracking", true).putLong("gpsMs", 0L)
                .putString("message", "Servicio GPS activo. Esperando primera señal; puedes bloquear la pantalla.").apply();
            boolean registered = false;
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 7000L, 0f, this, Looper.getMainLooper());
                registered = true;
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 0f, this, Looper.getMainLooper());
                registered = true;
            }
            if (!registered) { status("Activa la ubicación del teléfono."); stopRoute(); return; }
            handler.postDelayed(healthCheck, 15000L);
        } catch (Exception error) {
            status("No se pudo iniciar GPS: " + error.getMessage());
            running = false;
            releaseCpuLock();
            try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
            stopSelf();
        }
    }

    @Override public void onLocationChanged(Location location) {
        if (!running || stopping || location == null) return;
        if (!isFresh(location)) return;
        // Una señal GPS excesivamente imprecisa produce saltos de ubicación.
        // Mantener última señal real en pantalla; nunca inventar una nueva posición.
        if (location.hasAccuracy() && (location.getAccuracy() < 0 || location.getAccuracy() > 500f)) return;
        if (location.getLatitude() < -90 || location.getLatitude() > 90 ||
            location.getLongitude() < -180 || location.getLongitude() > 180) return;
        latest = new Location(location);
        lastLocationElapsedMs = android.os.SystemClock.elapsedRealtime();
        tryPublish(latest);
    }

    private boolean isFresh(Location location) {
        long age = android.os.SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
        return age >= 0 && age <= 25000000000L;
    }

    private void tryPublish(Location location) {
        if (!running || stopping || !isFresh(location)) return;
        long now = System.currentTimeMillis();
        if (now - lastPublishedMs < 9500L || !posting.compareAndSet(false, true)) return;
        lastPublishedMs = now;
        double lat = location.getLatitude(), lng = location.getLongitude();
        float accuracy = location.hasAccuracy() ? location.getAccuracy() : 0f;
        try {
            io.execute(() -> {
                try {
                    if (!running || stopping) return;
                    SonicApi.setLocation(this, true, lat, lng, accuracy);
                    if (!running || stopping) return;
                    String time = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date());
                    statusPrefs().edit().putLong("gpsMs", System.currentTimeMillis())
                        .putString("message", "GPS enviado a Supabase a las " + time + " (±" + Math.round(accuracy) + " m)").apply();
                    notifyState("Compartiendo ubicación · " + time);
                } catch (Exception e) {
                    if (!stopping) {
                        status("Error de transmisión: " + e.getMessage());
                        notifyState("Sin conexión; la señal pública caducará");
                    }
                } finally { posting.set(false); }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            posting.set(false);
        }
    }

    @Override public void onProviderEnabled(String provider) {
        status("GPS disponible. Esperando posición…");
        if (!running || stopping || locationManager == null) return;
        try {
            if (LocationManager.GPS_PROVIDER.equals(provider))
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 7000L, 0f, this, Looper.getMainLooper());
            else if (LocationManager.NETWORK_PROVIDER.equals(provider))
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 0f, this, Looper.getMainLooper());
        } catch (SecurityException ignored) {
            status("Falta permiso de ubicación. Abre la aplicación para autorizar.");
        }
    }
    @Override public void onProviderDisabled(String provider) { status("Ubicación desactivada. Actívala para continuar."); }

    private void stopRoute() {
        if (stopping) return;
        if (!running) { stopSelf(); return; }
        stopping = true;
        running = false;
        handler.removeCallbacks(healthCheck);
        try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
        latest = null;
        statusPrefs().edit().putBoolean("tracking", false).putString("message", "Terminando recorrido…").apply();
        // El apagado se encola detrás de los envíos anteriores para evitar carreras.
        try {
            io.execute(() -> {
                try { SonicApi.setLocation(this, false, null, null, null); status("Recorrido detenido. Ubicación oculta para los clientes."); }
                catch (Exception e) { status("Recorrido detenido. Sin internet: última posición caducará en 120 s."); }
                finally {
                    handler.post(() -> {
                        releaseCpuLock();
                        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
                        stopSelf();
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            releaseCpuLock();
            stopSelf();
        }
    }

    @Override public void onDestroy() {
        running = false;
        stopping = false;
        latest = null;
        handler.removeCallbacks(healthCheck);
        releaseCpuLock();
        if (locationManager != null) { try { locationManager.removeUpdates(this); } catch (Exception ignored) {} }
        statusPrefs().edit().putBoolean("tracking", false).apply();
        io.shutdown();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i) { return null; }
}
