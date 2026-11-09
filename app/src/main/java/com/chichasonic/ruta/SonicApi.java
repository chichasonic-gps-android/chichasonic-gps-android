package com.chichasonic.ruta;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import java.util.concurrent.TimeUnit;

/** Supabase: clave PUBLICABLE + JWT del vendedor, validado por las políticas RLS. */
public final class SonicApi {
    private SonicApi() {}
    static final String BASE = "https://uwqmzbgpmogwjjfokqck.supabase.co";
    static final String KEY = "sb_publishable_v4mRHd43MVWo0_KsPIVtTw_KdI2wqWC";
    static final String MAP_URL = "https://withered-union-19b2.chichasoniconline.workers.dev/";
    private static final String PREF = "chicha_session_v1";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    /** OkHttp admite PATCH sin depender de HttpURLConnection.setRequestMethod(). */
    private static JSONObject request(String path, String method, JSONObject body, String token) throws Exception {
        RequestBody bytes = body == null ? null : RequestBody.create(JSON, body.toString());
        Request.Builder builder = new Request.Builder()
                .url(BASE + path)
                .header("apikey", KEY)
                .header("Accept", "application/json");
        if (token != null && !token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        if ("PATCH".equals(method)) builder.header("Prefer", "return=representation");
        if ("POST".equals(method)) builder.post(bytes);
        else if ("PATCH".equals(method)) builder.patch(bytes);
        else if ("GET".equals(method)) builder.get();
        else throw new IllegalArgumentException("Método HTTP no permitido");

        try (Response response = HTTP.newCall(builder.build()).execute()) {
            ResponseBody responseBody = response.body();
            String text = responseBody == null ? "" : responseBody.string();
            if (!response.isSuccessful()) {
                String msg = "Supabase HTTP " + response.code();
                try {
                    JSONObject err = new JSONObject(text);
                    String detail = err.optString("msg", err.optString("message", err.optString("error_description", "")));
                    if (!detail.isEmpty()) msg += ": " + detail;
                } catch (Exception ignored) {}
                throw new Exception(msg);
            }
            String clean = text.trim();
            if (clean.startsWith("[")) return new JSONObject().put("array", new JSONArray(clean));
            if (clean.startsWith("{")) return new JSONObject(clean);
            return new JSONObject();
        }
    }

    static void login(Context c, String email, String password) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("email", email); payload.put("password", password);
        JSONObject result = request("/auth/v1/token?grant_type=password", "POST", payload, null);
        String access = result.getString("access_token");
        String refresh = result.getString("refresh_token");
        String userId = result.getJSONObject("user").getString("id");
        // Verificar que la cuenta está autorizada por RLS, no basta estar autenticada.
        JSONObject admins = request("/rest/v1/sonic_admins?select=user_id&user_id=eq." + userId, "GET", null, access);
        if (admins.getJSONArray("array").length() == 0) throw new SecurityException("Esta cuenta no está autorizada como vendedor.");
        save(c, access, refresh, userId, email, result.optLong("expires_in", 3600));
    }

    private static synchronized void save(Context c, String access, String refresh, String id, String email, long seconds) {
        long expiry = System.currentTimeMillis() + Math.max(seconds, 60) * 1000;
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
          .putString("access", access).putString("refresh", refresh)
          .putString("userId", id).putString("email", email)
          .putLong("expires", expiry).apply();
    }
    static String loggedEmail(Context c) { return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("email", ""); }
    static boolean hasSession(Context c) { return !c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("refresh", "").isEmpty(); }
    static void logout(Context c) { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply(); }

    static synchronized String validToken(Context c) throws Exception {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String token = p.getString("access", "");
        if (p.getLong("expires", 0) > System.currentTimeMillis() + 90000 && !token.isEmpty()) return token;
        String refresh = p.getString("refresh", "");
        if (refresh.isEmpty()) throw new Exception("No hay sesión. Inicia sesión otra vez.");
        JSONObject result = request("/auth/v1/token?grant_type=refresh_token", "POST",
                new JSONObject().put("refresh_token", refresh), null);
        String newToken = result.getString("access_token");
        save(c, newToken, result.getString("refresh_token"), p.getString("userId", ""),
            p.getString("email", ""), result.optLong("expires_in", 3600));
        return newToken;
    }

    /** Comprobación sin modificaciones: sesión válida, permisos y conexión con sonic_live. */
    static String checkConnection(Context c) throws Exception {
        JSONObject row = request("/rest/v1/sonic_live?id=eq.1&select=id,is_live,last_seen", "GET", null, validToken(c));
        if (!row.has("array") || row.getJSONArray("array").length() != 1)
            throw new Exception("La fila GPS no está visible para el vendedor. Revisa RLS.");
        JSONObject data = row.getJSONArray("array").getJSONObject(0);
        return "Supabase conectado. Fila GPS autorizada. Estado público: " +
            (data.optBoolean("is_live", false) ? "activo" : "detenido") + ".";
    }

    /** Información de la próxima parada y aviso público; misma tabla del panel web. */
    static JSONObject getRouteInfo(Context c) throws Exception {
        JSONObject result = request("/rest/v1/sonic_info?id=eq.1&select=next_stop,announcement", "GET", null, validToken(c));
        if (!result.has("array") || result.getJSONArray("array").length() != 1)
            throw new Exception("No se pudo consultar la próxima parada (sonic_info / RLS).");
        return result.getJSONArray("array").getJSONObject(0);
    }

    /** Actualiza ÚNICAMENTE los campos visibles de rutas, nunca toca sonic_live/GPS. */
    static void saveRouteInfo(Context c, String nextStop, String announcement) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("next_stop", nextStop.trim());
        payload.put("announcement", announcement.trim());
        payload.put("updated_at", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US).format(new java.util.Date()));
        JSONObject updated = request("/rest/v1/sonic_info?id=eq.1&select=id", "PATCH", payload, validToken(c));
        if (!updated.has("array") || updated.getJSONArray("array").length() != 1)
            throw new Exception("No se pudo guardar la parada. Revisa políticas RLS.");
    }

    static void setLocation(Context c, boolean live, Double lat, Double lng, Float accuracy) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("is_live", live);
        payload.put("latitude", lat == null ? JSONObject.NULL : lat);
        payload.put("longitude", lng == null ? JSONObject.NULL : lng);
        payload.put("accuracy", accuracy == null ? JSONObject.NULL : Math.max(0, accuracy));
        payload.put("last_seen", live ? new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US).format(new java.util.Date()) : JSONObject.NULL);
        payload.put("updated_at", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US).format(new java.util.Date()));
        JSONObject updated = request("/rest/v1/sonic_live?id=eq.1&select=id", "PATCH", payload, validToken(c));
        if (!updated.has("array") || updated.getJSONArray("array").length() != 1)
            throw new Exception("Supabase no aceptó la actualización GPS. Revisa permisos RLS.");
    }
}
