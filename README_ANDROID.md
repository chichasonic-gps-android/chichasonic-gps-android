# Chicha Sonic Ruta V4 — transmisor Android con pantalla bloqueada

**Objetivo:** conservar la web pública de Cloudflare que funciona, y reemplazar únicamente el sistema que envía coordenadas desde el teléfono del vendedor. La web de clientes no se instala ni se modifica.

## Por qué no alcanza una actualización de Netlify

`navigator.geolocation.watchPosition()` puede suspenderse con Android bloqueado; convertir la web en PWA o añadir Wake Lock no garantiza seguimiento continuo. Esta app **nativa Android** solicita ubicación y ejecuta un `Foreground Service` con notificación persistente. El vendedor la enciende y la detiene manualmente. No se inicia al encender el teléfono y no guarda historial de rutas.

## Instalación y construcción (Windows)

1. Descarga Android Studio desde https://developer.android.com/studio. En la instalación acepta **Android SDK Platform 35** y **Build Tools 35** cuando se soliciten.
2. Descomprime este ZIP en una carpeta común, sin rutas con caracteres raros.
3. En Windows, haz doble clic en **`CREAR_APK_WINDOWS.bat`**. El archivo comprueba que Android SDK 35 esté instalado, descarga Gradle **8.11.1** desde `services.gradle.org` (solo la primera vez) y ejecuta `assembleDebug`.
4. Si prefieres Android Studio, abre la carpeta como proyecto. Para un import directo podría pedir generar Gradle Wrapper; se puede generar con `gradle wrapper --gradle-version 8.11.1` usando el Gradle ya descargado.
5. Si la compilación falla, comparte el error de la consola para corregirlo.
6. El APK de prueba estará en `app/build/outputs/apk/debug/app-debug.apk`. Cópialo al Android del vendedor e instálalo. Android podría solicitar permitir instalaciones de esta fuente.
7. Para distribución permanente se debe firmar un APK de lanzamiento con tu propia clave de firma: **Build → Generate Signed Bundle / APK**. No publiques ni compartas el archivo de firma/contraseña.

## Cómo probar

1. Abre **Chicha Sonic Ruta** en Android e inicia sesión con **chichasoniconline@gmail.com** y su contraseña actual. La app comprueba la autorización contra `sonic_admins`.
2. Pulsa **Comenzar recorrido** mientras la aplicación esté visible. Concede **Ubicación precisa** y permiso de **notificaciones**. Se mostrará una notificación permanente que permite detener el seguimiento.
3. Confirma que el estado cambie a **GPS enviado a Supabase** con hora reciente.
4. Abre https://withered-union-19b2.chichasoniconline.workers.dev/ desde otro celular y verifica que aparece la moto.
5. Bloquea el Android, camina unos 100–200 metros y verifica desde el segundo teléfono si la posición se actualiza.
6. Pulsa **Terminar recorrido** en la app o desde su notificación; espera a que se confirme. Si falla internet, la ubicación vieja desaparece para clientes al llegar a 120 segundos.

## Mejoras de esta tercera versión

- Logo original en el ícono Android y enlace actualizado a la web de Cloudflare.
- Botón de ajustes para configurar batería y ubicación desde Android.
- Mejora de reintento de transmisión usando **únicamente posiciones recientes**.
- Control de apagado para que no quede una actualización antigua después de detener la ruta.
- Comprobación de antigüedad GPS con reloj monotónico de Android.
- Se mantiene el mismo proyecto y las mismas políticas RLS de Supabase.

## Configuración recomendada del móvil

- Ajustes → Aplicaciones → Chicha Sonic Ruta → **Batería** → **Sin restricciones** / Permitir en segundo plano (nombres varían por marca).
- Activa **Ubicación** y permite **Ubicación precisa**.
- Activa datos móviles. No fuerces la detención de la app ni uses apps que maten procesos.
- Inicia el recorrido antes de bloquear la pantalla. En Android 14+ no se permite iniciar un nuevo servicio de ubicación desde una app que ya esté en segundo plano sin excepciones específicas.

## Privacidad y seguridad

- Solo publica GPS cuando el vendedor lo activa de forma explícita.
- La notificación indica que la ubicación se comparte y tiene acción para detenerla.
- Usa **solo publishable key**, nunca clave secreta/service_role.
- El email y contraseña se procesan mediante HTTPS; la contraseña no se guarda. La sesión (access/refresh token) se guarda en las preferencias privadas de la app mientras está instalada. Para una versión distribuida, conviene agregar cifrado de tokens con Android Keystore.
- **No** solicita `ACCESS_BACKGROUND_LOCATION`: el servicio de ubicación se inicia con la actividad visible y permanece ejecutándose como **Foreground Service**, incluso al apagar pantalla. En algunos fabricantes y modos extremos de batería el sistema puede terminarlo; ningún enfoque garantiza 100% de entregas.
- La web pública ya oculta posiciones con antigüedad mayor a 120 segundos; eso reduce el riesgo de mostrar posiciones obsoletas si el dispositivo se apaga.
- En una primera versión no se añade historial GPS ni se envían ubicaciones cuando el recorrido está detenido.

## Integración existente (sin cambios a Supabase)

- Proyecto: Chicha Sonic (URL `https://uwqmzbgpmogwjjfokqck.supabase.co`).
- Auth: correo y contraseña existentes; RLS existente en `sonic_admins`.
- `PATCH /rest/v1/sonic_live?id=eq.1` con JWT de vendedor; columnas: `is_live`, `latitude`, `longitude`, `accuracy`, `last_seen`, `updated_at`.
- La página pública consulta `sonic_live` e ignora automáticamente señales antiguas.

## Limitaciones del paquete

**No hay APK compilado dentro del ZIP:** este entorno carece del Android SDK y no tiene acceso a Gradle para compilarlo. Los archivos fuente y XML se han revisado, pero la aplicación necesita **compilación y pruebas físicas reales** antes de llamarla lista para producción. No instales simultáneamente otros transmisores web, porque pueden sobrescribir el mismo registro.

**Nunca deben estar activados simultáneamente el GPS web del administrador y el transmisor nativo**, porque ambos escriben el mismo registro. El sitio público sigue funcionando independientemente.

## Si ya instalaste otra versión en Android

Si el APK se generó con una firma de depuración diferente, Android puede impedir actualizarlo encima de una versión anterior. Se puede desinstalar la versión de prueba anterior antes de instalar el APK V3 (esto borra la sesión local y habrá que iniciar sesión nuevamente). La base de datos de Supabase no se borra. No desinstales durante un recorrido activo: primero termina la transmisión.

## Prueba obligatoria antes de utilizar en la calle

1. Inicia sesión en Android.
2. Pulsa **PROBAR CONEXIÓN SUPABASE** y comprueba el mensaje «Supabase conectado. Fila GPS autorizada». Esta prueba no modifica la ubicación.
3. Inicia el GPS con la app visible y permiso de ubicación precisa.
4. Abre la web de Cloudflare desde otro teléfono. Comprueba que aparece ubicación y hora actualizada.
5. Bloquea el teléfono vendedor durante 5–10 minutos y desplázate a pie por un lugar seguro mientras verificas el mapa desde otro dispositivo.
6. Detén el recorrido y verifica que la posición desaparezca del mapa.
7. Repite la prueba con Wi-Fi apagado y datos móviles para asegurar cambio de red.

La continuidad de las coordenadas no está garantizada en todos los fabricantes Android; si falla revisa «Batería sin restricciones», permisos, conexión y configuraciones específicas del fabricante.


## Ajustes adicionales de V4 para bloqueo

- `PowerManager.PARTIAL_WAKE_LOCK` mantiene la CPU funcionando mientras el seguimiento está activo con pantalla apagada. Se libera al terminar. **Aumenta el consumo de batería**.
- Continúa siendo obligatorio permitir ubicación precisa y configurar la batería sin restricciones cuando el fabricante restrinja servicios en segundo plano. Un Wake Lock **no anula Doze ni garantiza internet**, por lo que la app alerta al perder transmisiones.
- Las actualizaciones de GPS al volver a activar un proveedor de ubicación se registran otra vez.
- El servicio debe iniciarse desde la app visible (restricción Android 14+). No se reinicia solo después de una terminación forzada.
- Este paquete necesita compilación en Android Studio y prueba física de 5–10 minutos con pantalla apagada.
