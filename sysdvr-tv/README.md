# SysDVR TV

Cliente nativo de [SysDVR](https://github.com/exelix11/SysDVR) para **Android TV / Chromecast con Google TV**.
Kotlin puro, sin FFmpeg ni SDL: H.264 por hardware con `MediaCodec` y audio con `AudioTrack` en baja latencia.

## Descarga directa del APK

Para la app **Downloader** en el Chromecast:

```
https://github.com/wongmisterius/Apps/releases/download/v0.1.0/sysdvr-tv.apk
```

Última versión (siempre la más reciente):

```
https://github.com/wongmisterius/Apps/releases/latest/download/sysdvr-tv.apk
```

### Instalación en el Chromecast con Google TV

1. *Ajustes → Sistema → Acerca de →* pulsar 7 veces *Compilación del SO de Android TV* para activar las opciones de desarrollador.
2. *Ajustes → Apps → Seguridad y restricciones → Fuentes desconocidas →* habilitar **Downloader**.
3. Abrir Downloader, pegar la URL de arriba e instalar.
4. La app aparece en el launcher como **SysDVR TV**.

## Uso

1. En la Switch: SysDVR 6.x en modo **TCP Bridge** (SysDVR-Settings) y un juego abierto.
2. Abrir SysDVR TV. Las consolas que emiten el beacon UDP (puerto 19999) aparecen en la lista; si no, escribir la IP (por defecto `192.168.1.101`, se guarda la última usada) y pulsar **Conectar**.
3. Durante el stream, cualquier tecla del D-pad muestra el overlay con el estado y el botón **Reconectar**. *Atrás* vuelve a la pantalla de conexión.

## Detalles técnicos

| Componente | Implementación |
|---|---|
| Protocolo | TCP Bridge: video en `:9911`, audio en `:9922`; hello `SysDVR\|03`, request de 16 bytes, respuesta de 4 (v02) o 72 bytes (v03) |
| Reconexión | Automática por socket (cada stream independiente), resincronización por magic `CCCCCCCC` |
| Descubrimiento | Escucha del beacon UDP `SysDVR\|ver\|proto\|serial` en el puerto 19999 |
| Video | H.264 720p30, `MediaCodec` asíncrono por hardware, SPS/PPS precargados, `KEY_LOW_LATENCY`, render inmediato de cada frame, NAL replay |
| Audio | PCM 16 bits / 48 kHz / estéreo, `AudioTrack` `PERFORMANCE_MODE_LOW_LATENCY`, escritura no bloqueante (no acumula latencia) |
| Build | minSdk 26, targetSdk 34, `abiFilters` armeabi-v7a + arm64-v8a, sin dependencias |

El APK no contiene librerías `.so`: al no tener código nativo es independiente de la ABI y se ejecuta en el userspace
de 32 bits (armeabi-v7a) del Chromecast. La CI lista el contenido del APK y falla si aparece código nativo de otra ABI
o si hay `.so` sin la variante armeabi-v7a.

## Compilar

Lo compila GitHub Actions (`.github/workflows/sysdvr-tv.yml` en la raíz del repo) en cada push a `main`; los tags `v*` publican un Release con el APK.

```
./gradlew assembleDebug
```

El APK se firma con la keystore de debug incluida en `keystore/` para que las actualizaciones se instalen encima de la versión anterior.
