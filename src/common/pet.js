// Lógica pura de la mascota virtual (sin dependencias de UI).
// Todos los valores de stats van de 0 a 100.

const STORAGE_KEY = 'tamagotchi_state_v1';

// Tasas de decaimiento por minuto real.
const DECAY = {
  hunger: 2.2,   // baja "saciedad": hay que darle de comer
  happiness: 1.4,
  energy: 1.1,
  clean: 1.6
};

// Umbrales de edad (en minutos simulados) para evolucionar.
const STAGE_THRESHOLDS = [
  { stage: 'egg', minAge: 0 },
  { stage: 'baby', minAge: 5 },
  { stage: 'child', minAge: 180 },
  { stage: 'adult', minAge: 1440 }
];

function clamp(v) {
  return Math.max(0, Math.min(100, v));
}

function defaultState() {
  const now = Date.now();
  return {
    alive: true,
    sick: false,
    sleeping: false,
    ageMinutes: 0,
    hunger: 80,
    happiness: 80,
    energy: 80,
    clean: 100,
    sickMinutes: 0,
    lastUpdate: now,
    born: now
  };
}

function stageFor(ageMinutes) {
  let current = STAGE_THRESHOLDS[0].stage;
  for (const entry of STAGE_THRESHOLDS) {
    if (ageMinutes >= entry.minAge) current = entry.stage;
  }
  return current;
}

// Aplica el paso del tiempo real transcurrido desde lastUpdate.
function applyElapsed(state) {
  if (!state.alive) return state;

  const now = Date.now();
  const elapsedMs = now - state.lastUpdate;
  const elapsedMin = elapsedMs / 60000;
  if (elapsedMin <= 0) return state;

  state.ageMinutes += elapsedMin;

  if (state.sleeping) {
    // Dormido: recupera energía, casi no gasta el resto.
    state.energy = clamp(state.energy + elapsedMin * 3);
    state.hunger = clamp(state.hunger - elapsedMin * (DECAY.hunger * 0.3));
    state.clean = clamp(state.clean - elapsedMin * (DECAY.clean * 0.3));
  } else {
    state.hunger = clamp(state.hunger - elapsedMin * DECAY.hunger);
    state.happiness = clamp(state.happiness - elapsedMin * DECAY.happiness);
    state.energy = clamp(state.energy - elapsedMin * DECAY.energy);
    state.clean = clamp(state.clean - elapsedMin * DECAY.clean);
  }

  const critical = state.hunger === 0 || state.clean === 0 || state.happiness === 0;
  if (critical) {
    state.sick = true;
    state.sickMinutes += elapsedMin;
  } else if (state.sick && state.hunger > 30 && state.clean > 30 && state.happiness > 30) {
    state.sick = false;
    state.sickMinutes = 0;
  }

  // Si queda enferma demasiado tiempo sin atención, la mascota muere.
  if (state.sick && state.sickMinutes > 240) {
    state.alive = false;
  }

  state.lastUpdate = now;
  return state;
}

function load() {
  return new Promise((resolve) => {
    storageGet(STORAGE_KEY, (raw) => {
      let state;
      try {
        state = raw ? JSON.parse(raw) : defaultState();
      } catch (e) {
        state = defaultState();
      }
      state = applyElapsed(state);
      resolve(state);
    });
  });
}

function save(state) {
  return new Promise((resolve) => {
    storageSet(STORAGE_KEY, JSON.stringify(state), () => resolve(state));
  });
}

// Envoltorios sobre @system.storage, inyectados desde el componente
// para no acoplar este módulo al framework de Quick App.
let storageImpl = null;

function bindStorage(impl) {
  storageImpl = impl;
}

function storageGet(key, cb) {
  if (!storageImpl) return cb(null);
  storageImpl.get({
    key,
    success: (data) => cb(data),
    fail: () => cb(null)
  });
}

function storageSet(key, value, cb) {
  if (!storageImpl) return cb();
  storageImpl.set({
    key,
    value,
    success: () => cb(),
    fail: () => cb()
  });
}

function feed(state) {
  if (!state.alive || state.sleeping) return state;
  state.hunger = clamp(state.hunger + 30);
  state.happiness = clamp(state.happiness + 3);
  return state;
}

function play(state) {
  if (!state.alive || state.sleeping) return state;
  state.happiness = clamp(state.happiness + 25);
  state.energy = clamp(state.energy - 12);
  state.hunger = clamp(state.hunger - 8);
  return state;
}

function clean(state) {
  if (!state.alive) return state;
  state.clean = 100;
  return state;
}

function toggleSleep(state) {
  if (!state.alive) return state;
  state.sleeping = !state.sleeping;
  return state;
}

function reset() {
  return defaultState();
}

module.exports = {
  bindStorage,
  load,
  save,
  feed,
  play,
  clean,
  toggleSleep,
  reset,
  stageFor,
  clamp
};
