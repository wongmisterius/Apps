# Escáner de Red (Android)

App Android (Kotlin + Jetpack Compose + SQLite) que escanea la red WiFi a la que
está conectado el teléfono, lista los equipos conectados, permite registrarlos
con un nombre y muestra el fabricante a partir de la MAC, detectando las MAC
aleatorias/privadas.

## Funciones

- **Escaneo de la subred** (hasta /22): ICMP + sondeo TCP a puertos comunes
  (80, 443, 22, 445, 139, 53, 8080, 62078 iPhone, 8008 Chromecast, 9100 impresoras).
- **Identificación** de cada equipo vivo:
  - NetBIOS NBSTAT (UDP 137) → nombre **y MAC** (Windows, Samba, NAS, impresoras).
  - mDNS unicast PTR (UDP 5353) → nombre `.local` (iPhone/Mac, Android, Chromecast, Linux).
  - DNS inverso del router.
  - Tabla ARP (`/proc/net/arp` / `ip neigh`) cuando el sistema la permite.
- **Fabricante por MAC**: base IEEE embebida offline (MA-L, MA-M, MA-S y CID,
  ~54.000 asignaciones) en `app/src/main/assets/oui.tsv.gz`.
- **Detección de MAC aleatoria**: bit *localmente administrada* del primer octeto
  (segundo dígito hex `2`, `6`, `A` o `E`). Las MAC privadas de Android 10+,
  iOS 14+ y Windows siempre lo tienen encendido. En el cuadrante ELI (`xA`) se
  consulta además el registro CID de la IEEE.
- **Registro en SQLite** (`escaner_red.db`, tabla `dispositivos`): nombre, MAC,
  fabricante, si es aleatoria, última IP, hostname, notas, fecha de alta y de
  última vez visto. Se puede registrar desde el escaneo o agregar a mano.
  Al escanear, los equipos se emparejan con los registrados por MAC (o por
  hostname/IP si el registro no tiene MAC).

## Limitación importante de Android 10+

Desde Android 10 las apps **no pueden leer la tabla ARP** del sistema
(`/proc/net/arp` y `ip neigh` están bloqueados sin root), así que Android no
entrega la MAC de los demás equipos. La app la obtiene por NetBIOS cuando el
equipo responde; para el resto, toca el equipo y escribe la MAC que muestra el
panel de tu router: la app calcula el fabricante y si es aleatoria al instante.

En teléfonos con MAC aleatoria, el fabricante real solo se ve desactivando
"Dirección MAC aleatoria / privada" en los ajustes de esa red WiFi del teléfono.

## Compilar

Requisitos: JDK 17+ y Android SDK (platform 35).

```bash
./gradlew assembleDebug        # APK en app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # tests de MacUtils / NetBIOS
```

O abrir la carpeta `escaner-red-android/` en Android Studio.

Actualizar la base de fabricantes IEEE:

```bash
python3 tools/generar_oui.py
```

## Estructura

```
app/src/main/java/com/wongmisterius/escanerred/
  MainActivity.kt
  data/DispositivosDb.kt     # SQLiteOpenHelper + CRUD
  net/MacUtils.kt            # normalización y clasificación de MAC (aleatoria/global/...)
  net/OuiDatabase.kt         # búsqueda de fabricante (MA-S → MA-M → MA-L, CID)
  net/NetworkScanner.kt      # barrido de subred, ARP, orquestación
  net/NetBios.kt             # consulta NBSTAT (nombre + MAC)
  net/Mdns.kt                # PTR inverso por mDNS unicast
  ui/EscanerViewModel.kt
  ui/Pantallas.kt            # Compose: pestañas Red / Registrados, diálogo de registro
```

Permisos: `INTERNET`, `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE`,
`CHANGE_WIFI_MULTICAST_STATE` y ubicación (solo para que Android muestre el SSID).
