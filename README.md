# Mascota Virtual - Quick App para Huawei Watch GT3

Juego tipo Tamagotchi implementado como **Quick App (LiteWearable)** para
Huawei Watch GT3. La mascota tiene hambre, ánimo, energía y limpieza que
decaen con el tiempo real (incluso con la app cerrada); hay que cuidarla
con las acciones Comer / Jugar / Limpiar / Dormir. Si se descuida
demasiado tiempo se enferma y, si sigue sin atención, muere (se puede
empezar de nuevo con un huevo nuevo).

## Estructura

```
src/
  manifest.json        # config de la Quick App (paquete, router, deviceType)
  app.ux                # ciclo de vida de la app
  common/pet.js          # lógica de la mascota (stats, evolución, persistencia)
  pages/home/index.ux    # pantalla única: cara animada, barras, botones
  Common/logo.png        # icono placeholder (reemplazar por uno propio)
```

## Requisitos para compilar

Esta Quick App usa el framework `hap-toolkit` (el mismo que usa el IDE
Quick App / DevEco Studio para dispositivos LiteWearable). Necesitás:

1. Node.js 14+ instalado.
2. `npm install` en la raíz del proyecto (instala `hap-toolkit`).
3. Certificados de firma en `sign/` (se generan/descargan desde
   [Huawei AppGallery Connect](https://developer.huawei.com/consumer/es/)
   al registrar la app; no están incluidos en este repo).

```bash
npm install
npm run watch    # sirve en modo debug, escanear QR con Huawei Health / Quick App IDE
npm run build     # genera el paquete .rpk para distribución
```

Para probar en el reloj real: instalar **Huawei Health** en el celular
vinculado al GT3, activar "Cargador de Quick Apps" en las opciones de
desarrollador del reloj (o usar el simulador de DevEco Studio), y escanear
el QR que expone `npm run watch`.

## Notas

- `deviceType: ["liteWearable"]` en `manifest.json` restringe la app a
  relojes livianos tipo GT3 (no Wear OS / Android).
- El decaimiento de stats se calcula por tiempo transcurrido real
  (`Date.now()` guardado en `@system.storage`), así que al reabrir la app
  se "pone al día" aunque haya estado cerrada.
- `src/Common/logo.png` es un placeholder de 64x64; reemplazalo por el
  ícono definitivo antes de publicar.
- Los valores de decaimiento/evolución están en `src/common/pet.js`
  (constantes `DECAY` y `STAGE_THRESHOLDS`) para ajustar fácilmente el
  balance del juego.
