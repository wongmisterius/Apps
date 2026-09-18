# Photo3D — Fotos con efecto 3D (parecido a iPhone) para Android

App Android nativa (Kotlin + Jetpack Compose) que saca fotos y las muestra
con un efecto de profundidad/paralaje al mover o inclinar el teléfono,
similar a las "fotos espaciales" / retratos con profundidad de iPhone.

El iPhone logra esto con sensores LiDAR o con dos cámaras (estéreo). La
mayoría de los Android no tiene esos sensores, así que esta app estima la
profundidad con IA on-device a partir de una sola foto:

1. **Captura**: CameraX toma la foto en la cámara trasera.
2. **Profundidad**: ML Kit Selfie Segmentation separa sujeto (cerca) de
   fondo (lejos); el resultado se suaviza para lograr una transición
   gradual entre planos en vez de un recorte duro. Funciona mejor con
   retratos/personas; para escenas sin sujeto claro, la foto se muestra
   plana (sin paralaje).
3. **Render 3D**: la foto se dibuja como una malla en OpenGL ES donde cada
   vértice se desplaza en el eje Z según su profundidad. Una cámara virtual
   se traslada lateralmente según la orientación del teléfono (sensor
   `TYPE_ROTATION_VECTOR`, con fallback a acelerómetro), generando el
   efecto de paralaje al inclinar o mover el dispositivo.

## Estructura

```
app/src/main/java/com/photo3d/spatial/
  MainActivity.kt          navegación (cámara / galería / visor) y permisos
  camera/                  captura de foto con CameraX
  depth/                   estimación de mapa de profundidad (ML Kit)
  gl/                      renderer OpenGL ES del efecto paralaje 3D
  sensors/                 lectura de inclinación del teléfono
  data/                    modelo y almacenamiento de fotos 3D en disco
  ui/                      pantallas Compose (cámara, galería, visor)
```

Cada foto se guarda en el almacenamiento privado de la app como dos
archivos: `<id>.jpg` (imagen) y `<id>.depth` (grilla binaria de
profundidad de 64×64).

## Requisitos para compilar

- Android Studio (Koala o más nuevo) o Gradle 8.7 + Android SDK
  (compileSdk/targetSdk 34, minSdk 26) instalados.
- JDK 17.

```bash
./gradlew assembleDebug     # genera app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug      # instala en un dispositivo/emulador conectado

# APK más liviano (R8 + shrink de recursos, firmado con la clave de debug
# para poder instalarlo sin configurar un keystore propio):
./gradlew assembleRelease   # genera app/build/outputs/apk/release/app-release.apk
```

Para probar el efecto de paralaje hace falta un dispositivo físico (el
emulador no simula bien el sensor de rotación); apuntá a una persona u
objeto con fondo despejado para que la segmentación separe bien los planos.

El paquete solo incluye la arquitectura `arm64-v8a` (todos los celulares
Android de los últimos ~8 años) para mantener el APK liviano; para probar
en un emulador x86_64 agregá esa arquitectura en `abiFilters` dentro de
`app/build.gradle.kts`.

### Instalar el APK manualmente

1. Copiá el `.apk` al teléfono (por cable, Drive, WhatsApp, etc).
2. Abrilo desde el explorador de archivos del teléfono.
3. Si Android bloquea la instalación, activá "Instalar apps desconocidas"
   para esa app (Config → Apps → acceso especial), y volvé a intentar.

## Limitaciones conocidas

- La profundidad es una aproximación (2 planos suavizados), no un mapa de
  profundidad real como el que da un LiDAR: funciona mejor en retratos que
  en paisajes o escenas complejas.
- Al inclinar mucho el teléfono pueden verse pequeños huecos en los bordes
  donde el fondo queda "descubierto" detrás del sujeto (limitación propia
  de reconstruir 3D desde una sola foto 2D); por eso el desplazamiento de
  la cámara virtual (`PARALLAX_AMPLITUDE` en `ParallaxRenderer.kt`) se
  mantiene moderado.
- Próximos pasos posibles: usar un modelo de profundidad monocular general
  (p. ej. MiDaS) para que el efecto funcione también en escenas sin
  personas, y exportar/compartir la foto 3D en un formato propio.
